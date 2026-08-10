/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.askvivo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cornell.mannlib.vitro.webapp.config.ConfigurationProperties;
import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.ajax.VitroAjaxController;
import edu.cornell.mannlib.vitro.webapp.dao.jena.QueryUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;

/**
 * AJAX endpoint for "Ask VIVO" natural-language search.
 *
 * POST parameters: question (required), history (optional JSON array of prior {question, answer}
 * pairs; the last three are used for conversational context).
 *
 * Flow: (1) LLM translates the question to SPARQL, (2) SparqlGuard validates it, (3) the query runs
 * against the VIVO triple store (with one broadened retry if it returns nothing), (4) the LLM
 * summarizes the rows into a prose answer. The response is always JSON and never a stack trace.
 */
@WebServlet(name = "AskVivoAjaxController", urlPatterns = {"/askVivoAjax"})
public class AskVivoAjaxController extends VitroAjaxController {

    private static final Log log = LogFactory.getLog(AskVivoAjaxController.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_HISTORY_TURNS = 3;
    private static final int MAX_ROWS_RETURNED = 50;
    private static final int MAX_ROWS_FOR_SUMMARY = 30;
    private static final int SPARQL_MAX_TOKENS = 900;
    private static final int SUMMARY_MAX_TOKENS = 600;
    private static final int DETAIL_EXCERPT_LENGTH = 300;

    // ------------------------------------------------------------------------
    // Rate limiting: a single token bucket shared across all users, since this
    // is a public page that hits a potentially paid API. 10 requests/minute.
    // ------------------------------------------------------------------------

    private static final int BUCKET_CAPACITY = 10;
    private static final double REFILL_TOKENS_PER_MILLI = BUCKET_CAPACITY / 60000.0;
    private static double bucketTokens = BUCKET_CAPACITY;
    private static long bucketLastRefillMillis = System.currentTimeMillis();

    private static synchronized boolean acquireRateLimitToken() {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0L, now - bucketLastRefillMillis);
        bucketTokens = Math.min((double) BUCKET_CAPACITY, bucketTokens + elapsed * REFILL_TOKENS_PER_MILLI);
        bucketLastRefillMillis = now;
        if (bucketTokens >= 1.0) {
            bucketTokens -= 1.0;
            return true;
        }
        return false;
    }

    // Per-session sliding window on top of the global bucket, so a single
    // client cannot drain the whole site's budget.
    private static final int SESSION_WINDOW_MAX = 4;
    private static final long SESSION_WINDOW_MILLIS = 60000L;
    private static final int SESSION_MAP_MAX_ENTRIES = 1000;
    private static final Map<String, List<Long>> SESSION_WINDOWS = new HashMap<String, List<Long>>();

    private static synchronized boolean acquireSessionSlot(String sessionId) {
        long now = System.currentTimeMillis();
        if (SESSION_WINDOWS.size() > SESSION_MAP_MAX_ENTRIES) {
            // Opportunistic cleanup: drop sessions with no recent requests.
            Iterator<Map.Entry<String, List<Long>>> it = SESSION_WINDOWS.entrySet().iterator();
            while (it.hasNext()) {
                List<Long> stamps = it.next().getValue();
                if (stamps.isEmpty() || now - stamps.get(stamps.size() - 1) > SESSION_WINDOW_MILLIS) {
                    it.remove();
                }
            }
        }
        List<Long> window = SESSION_WINDOWS.get(sessionId);
        if (window == null) {
            window = new ArrayList<Long>();
            SESSION_WINDOWS.put(sessionId, window);
        }
        Iterator<Long> it = window.iterator();
        while (it.hasNext()) {
            if (now - it.next() > SESSION_WINDOW_MILLIS) {
                it.remove();
            }
        }
        if (window.size() >= SESSION_WINDOW_MAX) {
            return false;
        }
        window.add(now);
        return true;
    }

    // ------------------------------------------------------------------------
    // Prompts
    // ------------------------------------------------------------------------

    private static final String SPARQL_SYSTEM_PROMPT = ""
            + "You translate natural-language questions about a VIVO research-profile knowledge graph into a\n"
            + "single SPARQL 1.1 SELECT query. Output ONLY the SPARQL query text, with no markdown fences and\n"
            + "no commentary.\n"
            + "\n"
            + "Declare every PREFIX you use. Available namespaces:\n"
            + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n"
            + "PREFIX foaf: <http://xmlns.com/foaf/0.1/>\n"
            + "PREFIX vivo: <http://vivoweb.org/ontology/core#>\n"
            + "PREFIX bibo: <http://purl.org/ontology/bibo/>\n"
            + "PREFIX obo: <http://purl.obolibrary.org/obo/>\n"
            + "PREFIX skos: <http://www.w3.org/2004/02/skos/core#>\n"
            + "PREFIX vitro: <http://vitro.mannlib.cornell.edu/ns/vitro/0.7#>\n"
            + "PREFIX vcard: <http://www.w3.org/2006/vcard/ns#>\n"
            + "\n"
            + "Core classes: people are ?p a foaf:Person. Organizations are ?o a foaf:Organization.\n"
            + "Publications are ?d a bibo:Document (subclasses include bibo:AcademicArticle, bibo:Book,\n"
            + "bibo:Chapter, vivo:ConferencePaper; inference materializes supertypes, so bibo:Document\n"
            + "matches all of them). Grants are ?g a vivo:Grant. Subject areas are ?c a skos:Concept.\n"
            + "vitro:mostSpecificType gives an entity's most specific class.\n"
            + "\n"
            + "Relationships are reified through intermediate entities:\n"
            + "- Authorship links a person to a publication:\n"
            + "  ?a a vivo:Authorship . ?a vivo:relates ?person . ?a vivo:relates ?doc .\n"
            + "  ?person a foaf:Person . ?doc a bibo:Document .\n"
            + "- Position links a person to an organization:\n"
            + "  ?org vivo:relatedBy ?pos . ?pos a vivo:Position . ?pos vivo:relates ?person .\n"
            + "  ?pos rdfs:label ?positionTitle . (vivo:relatedBy and vivo:relates are inverses.)\n"
            + "- Roles: a person bears a role via ?person obo:RO_0000053 ?role, and the role points back\n"
            + "  with ?role obo:RO_0000052 ?person (inheres in). A principal investigator on a grant:\n"
            + "  ?role a vivo:PrincipalInvestigatorRole . ?role obo:RO_0000052 ?person .\n"
            + "  ?role vivo:relatedBy ?grant . ?grant a vivo:Grant . The grant links back with\n"
            + "  ?grant vivo:relates ?role . Other role classes: vivo:CoPrincipalInvestigatorRole,\n"
            + "  vivo:InvestigatorRole, vivo:ResearcherRole.\n"
            + "- Research areas of a person: ?person vivo:hasResearchArea ?concept .\n"
            + "- Subject areas / topics / MeSH terms of a publication (the primary topic link in this\n"
            + "  system): ?doc vivo:hasSubjectArea ?concept . ?concept a skos:Concept ; rdfs:label ?name .\n"
            + "  The inverse is ?concept vivo:subjectAreaOf ?doc . Questions about topics, research\n"
            + "  areas, or MeSH terms of papers should use vivo:hasSubjectArea on the document.\n"
            + "- Publications may carry full text fields: ?doc bibo:abstract ?abstractText (the abstract)\n"
            + "  and ?doc bibo:content ?methodsText (the Methods section). Questions about techniques or\n"
            + "  methods used (e.g. who uses CRISPR or fMRI) should search these with\n"
            + "  FILTER(CONTAINS(LCASE(STR(?methodsText)), \"term\")) and can also check subject areas.\n"
            + "- Authors of harvested publications are often vcard name stubs, not foaf:Person:\n"
            + "  ?a a vivo:Authorship ; vivo:relates ?doc ; vivo:relates ?author .\n"
            + "  ?author a vcard:Individual ; vcard:hasName ?n . ?n vcard:familyName ?fam ;\n"
            + "  vcard:givenName ?giv . When searching for a person by name, check foaf:Person\n"
            + "  rdfs:label and vcard names with UNION.\n"
            + "\n"
            + "Dates: publications use ?doc vivo:dateTimeValue ?dtv . ?dtv vivo:dateTime ?dt .\n"
            + "Grants, positions and roles use intervals: ?x vivo:dateTimeInterval ?i .\n"
            + "?i vivo:start ?s . ?s vivo:dateTime ?sdt . ?i vivo:end ?e . ?e vivo:dateTime ?edt .\n"
            + "Date literals are xsd:dateTime; filter by year with FILTER(YEAR(?dt) = 2024).\n"
            + "\n"
            + "Rules:\n"
            + "- Labels come from rdfs:label.\n"
            + "- ALWAYS return the main entity as a URI column named ?uri with a label column named ?label:\n"
            + "  SELECT DISTINCT ?uri (STR(?lbl) AS ?label) WHERE { ... ?uri rdfs:label ?lbl ... }\n"
            + "- If you return additional entities, pair each URI variable ?x with a label variable named\n"
            + "  ?xLabel, e.g. ?grant and (STR(?glbl) AS ?grantLabel).\n"
            + "- Match names and titles case-insensitively with FILTER(CONTAINS(LCASE(STR(?lbl)), \"term\"))\n"
            + "  where \"term\" is lowercase. Never rely on exact string equality for names.\n"
            + "- Use DISTINCT. Add LIMIT 50 unless the question needs another limit (never above 200).\n"
            + "- Aggregates like COUNT are allowed when the question asks for totals.\n"
            + "- Output only the SPARQL query.";

    private static final String SUMMARY_SYSTEM_PROMPT = ""
            + "You are Ask VIVO, an assistant answering questions about a research-profile system.\n"
            + "You are given the user's question and the rows returned by a SPARQL query, as JSON.\n"
            + "Answer the question concisely in prose, using ONLY the information in the rows.\n"
            + "Refer to entities by their label, not their URI. Do not invent facts that are not in the\n"
            + "rows. If the rows are empty, say that no matching information was found and suggest that\n"
            + "the user rephrase the question.";

    // ------------------------------------------------------------------------
    // Request handling
    // ------------------------------------------------------------------------

    @Override
    protected void doRequest(VitroRequest vreq, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json;charset=UTF-8");
        ObjectNode result;
        try {
            result = handleRequest(vreq);
        } catch (Throwable t) {
            log.error("Ask VIVO request failed unexpectedly", t);
            result = errorNode("internal_error", null);
        }
        try {
            resp.getWriter().write(MAPPER.writeValueAsString(result));
        } catch (IOException e) {
            log.error("Ask VIVO could not write its response", e);
        }
    }

    private ObjectNode handleRequest(VitroRequest vreq) {
        if (!"POST".equalsIgnoreCase(vreq.getMethod())) {
            return errorNode("method_not_allowed", "Use POST.");
        }

        ConfigurationProperties props = ConfigurationProperties.getBean(vreq);
        String baseUrl = props.getProperty("llm.baseUrl");
        String model = props.getProperty("llm.model");
        String apiKey = props.getProperty("llm.apiKey", "");
        if (isBlank(baseUrl) || isBlank(model)) {
            return errorNode("not_configured", null);
        }

        String question = vreq.getParameter("question");
        if (isBlank(question)) {
            return errorNode("empty_question", null);
        }
        question = question.trim();

        if (!acquireSessionSlot(vreq.getSession(true).getId())) {
            return errorNode("rate_limited", null);
        }
        if (!acquireRateLimitToken()) {
            return errorNode("rate_limited", null);
        }

        List<String[]> history = parseHistory(vreq.getParameter("history"));
        LlmClient client = new LlmClient(baseUrl, apiKey, model);

        // PASS 1: natural language -> SPARQL, with one corrective retry on validation failure.
        String validated;
        try {
            String rawSparql = generateSparql(client, question, history, null);
            try {
                validated = SparqlGuard.validate(rawSparql);
            } catch (SparqlGuard.ValidationException first) {
                log.debug("Ask VIVO first query rejected: " + first.getMessage());
                String note = "Your previous response was rejected: " + first.getMessage()
                        + " Respond with a single corrected SPARQL SELECT query and nothing else.";
                String retrySparql = generateSparql(client, question, history, note);
                validated = SparqlGuard.validate(retrySparql);
            }
        } catch (IOException e) {
            log.error("Ask VIVO LLM call failed during query generation", e);
            return errorNode("llm_error", null);
        } catch (SparqlGuard.ValidationException e) {
            log.info("Ask VIVO could not obtain a valid query: " + e.getMessage());
            return errorNode("invalid_sparql", excerpt(e.getMessage()));
        }

        // Execute the query.
        List<Map<String, String>> rows;
        try {
            rows = executeQuery(validated, vreq);
        } catch (Exception e) {
            log.error("Ask VIVO query execution failed", e);
            return errorNode("query_error", excerpt(e.getMessage()));
        }

        // Zero rows: ask the LLM once for a broader formulation.
        if (rows.isEmpty()) {
            try {
                String note = "Note: the previous SPARQL query returned zero results. Previous query:\n"
                        + validated + "\nWrite a broader query for the same question: prefer"
                        + " FILTER(CONTAINS(LCASE(STR(?lbl)), \"term\")) over exact matching, use fewer"
                        + " type or date constraints, and consider alternative graph patterns.";
                String retryValidated = SparqlGuard.validate(generateSparql(client, question, history, note));
                List<Map<String, String>> retryRows = executeQuery(retryValidated, vreq);
                validated = retryValidated;
                rows = retryRows;
            } catch (Exception e) {
                log.debug("Ask VIVO broadened retry failed; keeping empty result", e);
            }
        }

        // PASS 2: summarize the rows into a prose answer.
        String answer;
        try {
            answer = summarize(client, question, rows);
        } catch (IOException e) {
            log.error("Ask VIVO LLM call failed during summarization", e);
            return errorNode("llm_error", null);
        }

        ObjectNode result = MAPPER.createObjectNode();
        result.put("answer", answer);
        result.put("sparql", validated);
        result.set("rows", rowsToJson(rows));
        result.set("entities", extractEntities(rows));
        return result;
    }

    // ------------------------------------------------------------------------
    // LLM passes
    // ------------------------------------------------------------------------

    private String generateSparql(LlmClient client, String question, List<String[]> history,
            String extraSystemNote) throws IOException {
        List<LlmClient.Message> messages = new ArrayList<LlmClient.Message>();
        messages.add(new LlmClient.Message("system", SPARQL_SYSTEM_PROMPT));
        if (extraSystemNote != null) {
            messages.add(new LlmClient.Message("system", extraSystemNote));
        }
        for (String[] turn : history) {
            messages.add(new LlmClient.Message("user", turn[0]));
            messages.add(new LlmClient.Message("assistant", turn[1]));
        }
        messages.add(new LlmClient.Message("user", question));
        return client.chat(messages, SPARQL_MAX_TOKENS);
    }

    private String summarize(LlmClient client, String question, List<Map<String, String>> rows)
            throws IOException {
        List<Map<String, String>> truncated = rows.size() > MAX_ROWS_FOR_SUMMARY
                ? rows.subList(0, MAX_ROWS_FOR_SUMMARY)
                : rows;
        String rowsJson = MAPPER.writeValueAsString(truncated);
        String user = "Question: " + question + "\n\nSPARQL result rows (JSON, showing "
                + truncated.size() + " of " + rows.size() + "):\n" + rowsJson;
        List<LlmClient.Message> messages = new ArrayList<LlmClient.Message>();
        messages.add(new LlmClient.Message("system", SUMMARY_SYSTEM_PROMPT));
        messages.add(new LlmClient.Message("user", user));
        return client.chat(messages, SUMMARY_MAX_TOKENS);
    }

    // ------------------------------------------------------------------------
    // Query execution and serialization
    // ------------------------------------------------------------------------

    private List<Map<String, String>> executeQuery(String queryStr, VitroRequest vreq) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        ResultSet results = QueryUtils.getQueryResults(queryStr, vreq);
        while (results.hasNext() && rows.size() < MAX_ROWS_RETURNED) {
            QuerySolution soln = results.nextSolution();
            rows.add(QueryUtils.querySolutionToStringValueMap(soln));
        }
        return rows;
    }

    private ArrayNode rowsToJson(List<Map<String, String>> rows) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Map<String, String> row : rows) {
            ObjectNode node = array.addObject();
            for (Map.Entry<String, String> entry : row.entrySet()) {
                node.put(entry.getKey(), entry.getValue());
            }
        }
        return array;
    }

    /**
     * Collect {uri, label} pairs from the rows: a URI-valued variable paired with its label
     * variable ("uri"/"label", or "x"/"xLabel", or "x"/"x_label"). Deduplicated by URI.
     */
    private ArrayNode extractEntities(List<Map<String, String>> rows) {
        Map<String, String> entities = new LinkedHashMap<String, String>();
        for (Map<String, String> row : rows) {
            for (Map.Entry<String, String> entry : row.entrySet()) {
                String value = entry.getValue();
                if (value == null
                        || !(value.startsWith("http://") || value.startsWith("https://"))) {
                    continue;
                }
                String label = findLabel(row, entry.getKey());
                if (label != null && !entities.containsKey(value)) {
                    entities.put(value, label);
                }
            }
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (Map.Entry<String, String> entity : entities.entrySet()) {
            ObjectNode node = array.addObject();
            node.put("uri", entity.getKey());
            node.put("label", entity.getValue());
        }
        return array;
    }

    private String findLabel(Map<String, String> row, String uriVarName) {
        String label = null;
        if ("uri".equals(uriVarName)) {
            label = row.get("label");
        }
        if (isBlank(label)) {
            label = row.get(uriVarName + "Label");
        }
        if (isBlank(label)) {
            label = row.get(uriVarName + "_label");
        }
        return isBlank(label) ? null : label;
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private List<String[]> parseHistory(String historyJson) {
        List<String[]> history = new ArrayList<String[]>();
        if (isBlank(historyJson)) {
            return history;
        }
        try {
            JsonNode parsed = MAPPER.readTree(historyJson);
            if (parsed.isArray()) {
                int start = Math.max(0, parsed.size() - MAX_HISTORY_TURNS);
                for (int i = start; i < parsed.size(); i++) {
                    JsonNode turn = parsed.get(i);
                    String turnQuestion = turn.path("question").asText("");
                    String turnAnswer = turn.path("answer").asText("");
                    if (!turnQuestion.isEmpty() && !turnAnswer.isEmpty()) {
                        history.add(new String[] {turnQuestion, turnAnswer});
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Ask VIVO ignoring unparseable history parameter", e);
        }
        return history;
    }

    private static ObjectNode errorNode(String code, String detail) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", code);
        if (detail != null && !detail.isEmpty()) {
            node.put("detail", detail);
        }
        return node;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        if (flattened.length() > DETAIL_EXCERPT_LENGTH) {
            return flattened.substring(0, DETAIL_EXCERPT_LENGTH) + "...";
        }
        return flattened;
    }
}
