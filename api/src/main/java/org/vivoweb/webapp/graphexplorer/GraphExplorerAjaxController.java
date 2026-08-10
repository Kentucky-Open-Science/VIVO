/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.graphexplorer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.ajax.VitroAjaxController;
import edu.cornell.mannlib.vitro.webapp.dao.jena.QueryUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;

/**
 * AJAX back end for the graph explorer page. Public endpoint (no
 * requiredActions override), read-only SPARQL SELECT queries.
 *
 * Actions (GET parameters):
 *   action=search&q=text        - label search, returns a JSON array of {uri, label, type}
 *   action=neighborhood&uri=u   - 1-hop neighborhood, returns {nodes:[...], links:[...]}
 *   action=overview             - most-connected people and co-authorship pairs, same shape
 */
@WebServlet(name = "GraphExplorerAjaxController", urlPatterns = {"/graphExplorerAjax"})
public class GraphExplorerAjaxController extends VitroAjaxController {

    private static final Log log = LogFactory.getLog(GraphExplorerAjaxController.class);

    private static final int SEARCH_LIMIT = 20;
    private static final int NEIGHBOR_LIMIT = 150;
    private static final int OVERVIEW_PEOPLE_LIMIT = 60;

    private static final String TYPE_PERSON = "person";
    private static final String TYPE_ORGANIZATION = "organization";
    private static final String TYPE_PUBLICATION = "publication";
    private static final String TYPE_GRANT = "grant";
    private static final String TYPE_CONCEPT = "concept";
    private static final String TYPE_OTHER = "other";

    /** Maps palette class URIs to short type names. */
    private static final Map<String, String> CLASS_TO_TYPE = new HashMap<String, String>();
    /** Lower rank wins when a node has several palette types. */
    private static final Map<String, Integer> TYPE_RANK = new HashMap<String, Integer>();
    static {
        CLASS_TO_TYPE.put("http://xmlns.com/foaf/0.1/Person", TYPE_PERSON);
        CLASS_TO_TYPE.put("http://xmlns.com/foaf/0.1/Organization", TYPE_ORGANIZATION);
        CLASS_TO_TYPE.put("http://purl.org/ontology/bibo/Document", TYPE_PUBLICATION);
        CLASS_TO_TYPE.put("http://vivoweb.org/ontology/core#Grant", TYPE_GRANT);
        CLASS_TO_TYPE.put("http://www.w3.org/2004/02/skos/core#Concept", TYPE_CONCEPT);
        TYPE_RANK.put(TYPE_PERSON, 0);
        TYPE_RANK.put(TYPE_ORGANIZATION, 1);
        TYPE_RANK.put(TYPE_PUBLICATION, 2);
        TYPE_RANK.put(TYPE_GRANT, 3);
        TYPE_RANK.put(TYPE_CONCEPT, 4);
        TYPE_RANK.put(TYPE_OTHER, 5);
    }

    private static final String PREFIXES = ""
        + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> \n"
        + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n"
        + "PREFIX foaf: <http://xmlns.com/foaf/0.1/> \n"
        + "PREFIX vivo: <http://vivoweb.org/ontology/core#> \n"
        + "PREFIX bibo: <http://purl.org/ontology/bibo/> \n"
        + "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> \n"
        + "PREFIX vitro: <http://vitro.mannlib.cornell.edu/ns/vitro/0.7#> \n";

    private static final String PALETTE_CLASS_FILTER =
        "FILTER(?cls IN (foaf:Person, foaf:Organization, bibo:Document, vivo:Grant, skos:Concept))";

    private static final String INTERNAL_PREDICATE_FILTERS = ""
        + "    FILTER(!STRSTARTS(STR(?p), \"http://vitro.mannlib.cornell.edu/ns/\")) \n"
        + "    FILTER(!STRSTARTS(STR(?p), \"http://www.w3.org/2002/07/owl#\")) \n"
        + "    FILTER(?p != rdf:type) \n";

    private static final String SEARCH_QUERY = PREFIXES
        + "SELECT DISTINCT ?uri ?label ?cls WHERE { \n"
        + "    VALUES ?cls { foaf:Person foaf:Organization bibo:Document vivo:Grant skos:Concept } \n"
        + "    ?uri a ?cls . \n"
        + "    ?uri rdfs:label ?label . \n"
        + "    FILTER(CONTAINS(LCASE(STR(?label)), \"%text%\")) \n"
        + "} LIMIT 80";

    private static final String ROOT_QUERY = PREFIXES
        + "SELECT ?label ?cls WHERE { \n"
        + "    ?root rdfs:label ?label . \n"
        + "    OPTIONAL { ?root a ?cls . " + PALETTE_CLASS_FILTER + " } \n"
        + "} LIMIT 20";

    private static final String OUTGOING_QUERY = PREFIXES
        + "SELECT DISTINCT ?p ?node ?label ?cls WHERE { \n"
        + "    ?root ?p ?node . \n"
        + "    FILTER(isIRI(?node)) \n"
        + INTERNAL_PREDICATE_FILTERS
        + "    ?node rdfs:label ?label . \n"
        + "    OPTIONAL { ?node a ?cls . " + PALETTE_CLASS_FILTER + " } \n"
        + "} LIMIT " + NEIGHBOR_LIMIT;

    private static final String INCOMING_QUERY = PREFIXES
        + "SELECT DISTINCT ?p ?node ?label ?cls WHERE { \n"
        + "    ?node ?p ?root . \n"
        + "    FILTER(isIRI(?node)) \n"
        + INTERNAL_PREDICATE_FILTERS
        + "    ?node rdfs:label ?label . \n"
        + "    OPTIONAL { ?node a ?cls . " + PALETTE_CLASS_FILTER + " } \n"
        + "} LIMIT " + NEIGHBOR_LIMIT;

    private static final String OVERVIEW_PEOPLE_QUERY = PREFIXES
        + "SELECT ?person (MIN(?plabel) AS ?label) (COUNT(DISTINCT ?auth) AS ?cnt) WHERE { \n"
        + "    ?person a foaf:Person . \n"
        + "    ?person rdfs:label ?plabel . \n"
        + "    ?auth a vivo:Authorship . \n"
        + "    ?auth vivo:relates ?person . \n"
        + "} GROUP BY ?person ORDER BY DESC(?cnt) LIMIT " + OVERVIEW_PEOPLE_LIMIT;

    private static final String OVERVIEW_PAIRS_QUERY_TEMPLATE = PREFIXES
        + "SELECT ?p1 ?p2 (COUNT(DISTINCT ?doc) AS ?shared) WHERE { \n"
        + "    VALUES ?p1 { %values% } \n"
        + "    VALUES ?p2 { %values% } \n"
        + "    ?a1 a vivo:Authorship . \n"
        + "    ?a1 vivo:relates ?p1 . \n"
        + "    ?a1 vivo:relates ?doc . \n"
        + "    ?doc a bibo:Document . \n"
        + "    ?a2 a vivo:Authorship . \n"
        + "    ?a2 vivo:relates ?p2 . \n"
        + "    ?a2 vivo:relates ?doc . \n"
        + "    FILTER(STR(?p1) < STR(?p2)) \n"
        + "} GROUP BY ?p1 ?p2 HAVING (COUNT(DISTINCT ?doc) >= 2)";

    @Override
    protected void doRequest(VitroRequest vreq, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json;charset=UTF-8");
        ObjectMapper mapper = new ObjectMapper();
        try {
            String action = vreq.getParameter("action");
            if ("search".equals(action)) {
                doSearch(vreq, resp, mapper);
            } else if ("neighborhood".equals(action)) {
                doNeighborhood(vreq, resp, mapper);
            } else if ("overview".equals(action)) {
                doOverview(vreq, resp, mapper);
            } else {
                writeError(resp, mapper, "Unknown action");
            }
        } catch (Exception e) {
            log.error("Graph explorer AJAX request failed", e);
            writeError(resp, mapper, "Internal error");
        }
    }

    private void doSearch(VitroRequest vreq, HttpServletResponse resp, ObjectMapper mapper)
            throws IOException {
        String q = vreq.getParameter("q");
        ArrayNode results = mapper.createArrayNode();
        if (q == null || q.trim().isEmpty()) {
            mapper.writeValue(resp.getWriter(), results);
            return;
        }
        String text = escapeSparqlLiteral(q.trim().toLowerCase());
        String queryStr = SEARCH_QUERY.replace("%text%", text);
        Map<String, NodeInfo> found = new LinkedHashMap<String, NodeInfo>();
        try {
            ResultSet rs = QueryUtils.getQueryResults(queryStr, vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                String uri = uriOf(soln, "uri");
                String label = literalOf(soln, "label");
                if (uri == null || label == null) {
                    continue;
                }
                String type = typeOfClass(uriOf(soln, "cls"));
                mergeNode(found, uri, label, type, false);
            }
        } catch (Exception e) {
            log.error("Graph explorer search query failed", e);
        }
        int count = 0;
        for (Map.Entry<String, NodeInfo> entry : found.entrySet()) {
            if (count >= SEARCH_LIMIT) {
                break;
            }
            ObjectNode item = results.addObject();
            item.put("uri", entry.getKey());
            item.put("label", entry.getValue().label);
            item.put("type", entry.getValue().type);
            count++;
        }
        mapper.writeValue(resp.getWriter(), results);
    }

    private void doNeighborhood(VitroRequest vreq, HttpServletResponse resp, ObjectMapper mapper)
            throws IOException {
        String uri = vreq.getParameter("uri");
        if (!isSafeUri(uri)) {
            writeError(resp, mapper, "Missing or invalid uri parameter");
            return;
        }
        Map<String, NodeInfo> nodes = new LinkedHashMap<String, NodeInfo>();
        Set<String> linkKeys = new LinkedHashSet<String>();
        List<String[]> links = new ArrayList<String[]>();

        // The root node itself.
        String rootLabel = uri;
        String rootType = TYPE_OTHER;
        try {
            ResultSet rs = QueryUtils.getQueryResults(
                QueryUtils.subUriForQueryVar(ROOT_QUERY, "root", uri), vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                String label = literalOf(soln, "label");
                if (label != null) {
                    rootLabel = label;
                }
                String type = typeOfClass(uriOf(soln, "cls"));
                if (rank(type) < rank(rootType)) {
                    rootType = type;
                }
            }
        } catch (Exception e) {
            log.error("Graph explorer root query failed for " + uri, e);
        }
        mergeNode(nodes, uri, rootLabel, rootType, true);

        collectNeighbors(vreq, QueryUtils.subUriForQueryVar(OUTGOING_QUERY, "root", uri),
            uri, true, nodes, linkKeys, links);
        collectNeighbors(vreq, QueryUtils.subUriForQueryVar(INCOMING_QUERY, "root", uri),
            uri, false, nodes, linkKeys, links);

        mapper.writeValue(resp.getWriter(), toGraphJson(mapper, nodes, links));
    }

    private void doOverview(VitroRequest vreq, HttpServletResponse resp, ObjectMapper mapper)
            throws IOException {
        Map<String, NodeInfo> nodes = new LinkedHashMap<String, NodeInfo>();
        List<String[]> links = new ArrayList<String[]>();
        try {
            ResultSet rs = QueryUtils.getQueryResults(OVERVIEW_PEOPLE_QUERY, vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                String personUri = uriOf(soln, "person");
                String label = literalOf(soln, "label");
                if (personUri == null || label == null) {
                    continue;
                }
                mergeNode(nodes, personUri, label, TYPE_PERSON, false);
            }
        } catch (Exception e) {
            log.error("Graph explorer overview people query failed", e);
        }

        if (!nodes.isEmpty()) {
            StringBuilder values = new StringBuilder();
            for (String personUri : nodes.keySet()) {
                values.append("<").append(personUri).append("> ");
            }
            String pairsQuery = OVERVIEW_PAIRS_QUERY_TEMPLATE.replace("%values%", values.toString().trim());
            Set<String> linkKeys = new LinkedHashSet<String>();
            try {
                ResultSet rs = QueryUtils.getQueryResults(pairsQuery, vreq);
                while (rs.hasNext()) {
                    QuerySolution soln = rs.nextSolution();
                    String p1 = uriOf(soln, "p1");
                    String p2 = uriOf(soln, "p2");
                    if (p1 == null || p2 == null) {
                        continue;
                    }
                    String key = p1 + "|coauthor|" + p2;
                    if (linkKeys.add(key)) {
                        links.add(new String[] {p1, p2, "coauthor"});
                    }
                }
            } catch (Exception e) {
                log.error("Graph explorer overview pairs query failed", e);
            }
        }

        mapper.writeValue(resp.getWriter(), toGraphJson(mapper, nodes, links));
    }

    private void collectNeighbors(VitroRequest vreq, String queryStr, String rootUri, boolean outgoing,
            Map<String, NodeInfo> nodes, Set<String> linkKeys, List<String[]> links) {
        try {
            ResultSet rs = QueryUtils.getQueryResults(queryStr, vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                String nodeUri = uriOf(soln, "node");
                String label = literalOf(soln, "label");
                String predicate = uriOf(soln, "p");
                if (nodeUri == null || label == null || predicate == null) {
                    continue;
                }
                if (nodeUri.equals(rootUri)) {
                    continue;
                }
                String type = typeOfClass(uriOf(soln, "cls"));
                mergeNode(nodes, nodeUri, label, type, false);
                String predLabel = localName(predicate);
                String source = outgoing ? rootUri : nodeUri;
                String target = outgoing ? nodeUri : rootUri;
                String key = source + "|" + predLabel + "|" + target;
                if (linkKeys.add(key)) {
                    links.add(new String[] {source, target, predLabel});
                }
            }
        } catch (Exception e) {
            log.error("Graph explorer neighborhood query failed for " + rootUri, e);
        }
    }

    private ObjectNode toGraphJson(ObjectMapper mapper, Map<String, NodeInfo> nodes, List<String[]> links) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode nodeArray = result.putArray("nodes");
        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            ObjectNode n = nodeArray.addObject();
            n.put("id", entry.getKey());
            n.put("label", entry.getValue().label);
            n.put("type", entry.getValue().type);
            n.put("root", entry.getValue().root);
        }
        ArrayNode linkArray = result.putArray("links");
        for (String[] link : links) {
            ObjectNode l = linkArray.addObject();
            l.put("source", link[0]);
            l.put("target", link[1]);
            l.put("label", link[2]);
        }
        return result;
    }

    private void mergeNode(Map<String, NodeInfo> nodes, String uri, String label, String type, boolean root) {
        NodeInfo existing = nodes.get(uri);
        if (existing == null) {
            nodes.put(uri, new NodeInfo(label, type, root));
        } else {
            if (rank(type) < rank(existing.type)) {
                existing.type = type;
            }
            if (root) {
                existing.root = true;
            }
        }
    }

    private String typeOfClass(String classUri) {
        if (classUri == null) {
            return TYPE_OTHER;
        }
        String type = CLASS_TO_TYPE.get(classUri);
        return (type == null) ? TYPE_OTHER : type;
    }

    private int rank(String type) {
        Integer r = TYPE_RANK.get(type);
        return (r == null) ? Integer.MAX_VALUE : r.intValue();
    }

    private String uriOf(QuerySolution soln, String varName) {
        RDFNode node = soln.get(varName);
        if (node == null || !node.isURIResource()) {
            return null;
        }
        return node.asResource().getURI();
    }

    private String literalOf(QuerySolution soln, String varName) {
        RDFNode node = soln.get(varName);
        if (node == null || !node.isLiteral()) {
            return null;
        }
        return node.asLiteral().getLexicalForm();
    }

    private String localName(String uri) {
        int hash = uri.lastIndexOf('#');
        if (hash >= 0 && hash < uri.length() - 1) {
            return uri.substring(hash + 1);
        }
        int slash = uri.lastIndexOf('/');
        if (slash >= 0 && slash < uri.length() - 1) {
            return uri.substring(slash + 1);
        }
        return uri;
    }

    private String escapeSparqlLiteral(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ");
    }

    /**
     * Reject anything that could break out of the &lt;uri&gt; syntax in a query.
     * Also rejects '$' and '\' because QueryUtils.subUriForQueryVar uses them
     * as regex-replacement metacharacters.
     */
    private boolean isSafeUri(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return false;
        }
        return !(uri.contains(">") || uri.contains("<") || uri.contains("\"")
            || uri.contains(" ") || uri.contains("\n") || uri.contains("\r")
            || uri.contains("\\") || uri.contains("{") || uri.contains("}")
            || uri.contains("$"));
    }

    private void writeError(HttpServletResponse resp, ObjectMapper mapper, String message)
            throws IOException {
        ObjectNode error = mapper.createObjectNode();
        error.put("error", message);
        mapper.writeValue(resp.getWriter(), error);
    }

    /** Accumulator for a node while queries are merged. */
    private static class NodeInfo {
        private String label;
        private String type;
        private boolean root;

        NodeInfo(String label, String type, boolean root) {
            this.label = label;
            this.type = type;
            this.root = root;
        }
    }
}
