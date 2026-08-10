/* $This file is distributed under the terms of the license in LICENSE$ */
package org.vivoweb.webapp.dashboard;

import java.io.IOException;

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
 * Supplies the statistics dashboard data as JSON. Public (no authorization
 * required). Results are cached in memory for five minutes so the public
 * page cannot hammer the triple store.
 */
@WebServlet(name = "DashboardAjaxController", urlPatterns = {"/dashboardAjax"})
public class DashboardAjaxController extends VitroAjaxController {

    private static final Log log = LogFactory.getLog(DashboardAjaxController.class);

    private static final long CACHE_LIFETIME_MILLIS = 5L * 60L * 1000L;

    private static final String PREFIXES = ""
        + "PREFIX foaf: <http://xmlns.com/foaf/0.1/> \n"
        + "PREFIX vivo: <http://vivoweb.org/ontology/core#> \n"
        + "PREFIX bibo: <http://purl.org/ontology/bibo/> \n"
        + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> \n"
        + "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> \n";

    private static final String RECENT_PUBLICATIONS_QUERY = PREFIXES
        + "SELECT DISTINCT ?doc (str(?labelRaw) AS ?label) ?dateTime WHERE { \n"
        + "    ?doc a bibo:Document ; \n"
        + "         rdfs:label ?labelRaw ; \n"
        + "         vivo:dateTimeValue ?dtv . \n"
        + "    ?dtv vivo:dateTime ?dateTime . \n"
        + "} ORDER BY DESC(?dateTime) LIMIT 10";

    private static final String TOP_RESEARCHERS_QUERY = PREFIXES
        + "SELECT ?person (str(?labelRaw) AS ?label) (COUNT(DISTINCT ?authorship) AS ?pubCount) WHERE { \n"
        + "    ?person a foaf:Person ; \n"
        + "            rdfs:label ?labelRaw . \n"
        + "    ?authorship a vivo:Authorship ; \n"
        + "                vivo:relates ?person . \n"
        + "} GROUP BY ?person ?labelRaw \n"
        + "ORDER BY DESC(?pubCount) LIMIT 10";

    private static final String TOP_ORGANIZATIONS_QUERY = PREFIXES
        + "SELECT ?org (str(?labelRaw) AS ?label) (COUNT(DISTINCT ?person) AS ?personCount) WHERE { \n"
        + "    ?org a foaf:Organization ; \n"
        + "         rdfs:label ?labelRaw . \n"
        + "    ?pos a vivo:Position ; \n"
        + "         vivo:relates ?org , ?person . \n"
        + "    ?person a foaf:Person . \n"
        + "} GROUP BY ?org ?labelRaw \n"
        + "ORDER BY DESC(?personCount) LIMIT 10";

    /** Immutable timestamped holder for the cached JSON payload. */
    private static final class CachedResult {
        private final long timestamp;
        private final String json;

        CachedResult(long timestamp, String json) {
            this.timestamp = timestamp;
            this.json = json;
        }
    }

    private static volatile CachedResult cache = null;
    private static final Object CACHE_LOCK = new Object();

    @Override
    protected void doRequest(VitroRequest vreq, HttpServletResponse resp)
            throws ServletException, IOException {
        resp.setContentType("application/json;charset=UTF-8");

        long now = System.currentTimeMillis();
        CachedResult cached = cache;
        if (cached != null && (now - cached.timestamp) < CACHE_LIFETIME_MILLIS) {
            resp.getWriter().write(cached.json);
            return;
        }

        // Double-checked under a lock so concurrent cold requests do not all
        // run the expensive count queries (thundering herd).
        synchronized (CACHE_LOCK) {
            cached = cache;
            if (cached != null && (now - cached.timestamp) < CACHE_LIFETIME_MILLIS) {
                resp.getWriter().write(cached.json);
                return;
            }
            String json = buildJson(vreq);
            cache = new CachedResult(System.currentTimeMillis(), json);
            resp.getWriter().write(json);
        }
    }

    private String buildJson(VitroRequest vreq) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        ObjectNode counts = root.putObject("counts");
        counts.put("people", getCount(vreq, "foaf:Person"));
        counts.put("publications", getCount(vreq, "bibo:Document"));
        counts.put("grants", getCount(vreq, "vivo:Grant"));
        counts.put("organizations", getCount(vreq, "foaf:Organization"));
        counts.put("concepts", getCount(vreq, "skos:Concept"));

        addRecentPublications(root.putArray("recentPublications"), vreq);
        addTopResearchers(root.putArray("topResearchers"), vreq);
        addTopOrganizations(root.putArray("topOrganizations"), vreq);

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to serialize dashboard JSON", e);
            return "{}";
        }
    }

    private long getCount(VitroRequest vreq, String typeCurie) {
        String queryStr = PREFIXES
            + "SELECT (COUNT(DISTINCT ?x) AS ?c) WHERE { ?x a " + typeCurie + " . }";
        try {
            ResultSet rs = QueryUtils.getQueryResults(queryStr, vreq);
            if (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                RDFNode node = soln.get("c");
                if (node != null && node.isLiteral()) {
                    return node.asLiteral().getLong();
                }
            }
        } catch (Exception e) {
            log.error("Dashboard count query failed for type " + typeCurie, e);
        }
        return 0L;
    }

    private void addRecentPublications(ArrayNode array, VitroRequest vreq) {
        try {
            ResultSet rs = QueryUtils.getQueryResults(RECENT_PUBLICATIONS_QUERY, vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                ObjectNode item = array.addObject();
                item.put("uri", nodeString(soln.get("doc")));
                item.put("label", nodeString(soln.get("label")));
                item.put("year", extractYear(nodeString(soln.get("dateTime"))));
            }
        } catch (Exception e) {
            log.error("Dashboard recent publications query failed", e);
        }
    }

    private void addTopResearchers(ArrayNode array, VitroRequest vreq) {
        try {
            ResultSet rs = QueryUtils.getQueryResults(TOP_RESEARCHERS_QUERY, vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                ObjectNode item = array.addObject();
                item.put("uri", nodeString(soln.get("person")));
                item.put("label", nodeString(soln.get("label")));
                item.put("publications", literalLong(soln.get("pubCount")));
            }
        } catch (Exception e) {
            log.error("Dashboard top researchers query failed", e);
        }
    }

    private void addTopOrganizations(ArrayNode array, VitroRequest vreq) {
        try {
            ResultSet rs = QueryUtils.getQueryResults(TOP_ORGANIZATIONS_QUERY, vreq);
            while (rs.hasNext()) {
                QuerySolution soln = rs.nextSolution();
                ObjectNode item = array.addObject();
                item.put("uri", nodeString(soln.get("org")));
                item.put("label", nodeString(soln.get("label")));
                item.put("people", literalLong(soln.get("personCount")));
            }
        } catch (Exception e) {
            log.error("Dashboard top organizations query failed", e);
        }
    }

    private String nodeString(RDFNode node) {
        if (node == null) {
            return "";
        }
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        if (node.isURIResource()) {
            return node.asResource().getURI();
        }
        return node.toString();
    }

    private long literalLong(RDFNode node) {
        if (node != null && node.isLiteral()) {
            try {
                return node.asLiteral().getLong();
            } catch (Exception e) {
                log.debug("Non-numeric literal where a count was expected", e);
            }
        }
        return 0L;
    }

    private String extractYear(String dateTime) {
        if (dateTime != null && dateTime.length() >= 4) {
            return dateTime.substring(0, 4);
        }
        return "";
    }
}
