/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cornell.mannlib.vitro.webapp.auth.permissions.SimplePermission;
import edu.cornell.mannlib.vitro.webapp.auth.requestedAction.AuthorizationRequest;
import edu.cornell.mannlib.vitro.webapp.config.ConfigurationProperties;
import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.FreemarkerHttpServlet;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.ResponseValues;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.TemplateResponseValues;
import edu.cornell.mannlib.vitro.webapp.edit.n3editing.VTwo.N3EditUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Admin page for the built-in scholarly data ingest tools (PubMed via NCBI
 * eUtils, grants via NIH RePORTER). GET renders the form page (or job status
 * as JSON with action=status); POST starts or cancels a background job.
 */
@WebServlet(name = "DataIngestController", urlPatterns = {"/dataIngest"})
public class IngestController extends FreemarkerHttpServlet {

    private static final Log log = LogFactory.getLog(IngestController.class);
    private static final String TEMPLATE_NAME = "dataIngest.ftl";

    private static final int PUBMED_DEFAULT_MAX = 200;
    private static final int PUBMED_HARD_MAX = 2000;
    private static final int REPORTER_DEFAULT_MAX = 500;
    private static final int REPORTER_HARD_MAX = 5000;

    private static final String DEFAULT_PUBMED_QUERY = "University of Kentucky[Affiliation]";
    private static final String DEFAULT_REPORTER_ORG = "University of Kentucky";
    private static final int DEFAULT_REPORTER_SINCE = 2020;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected AuthorizationRequest requiredActions(VitroRequest vreq) {
        return SimplePermission.USE_ADVANCED_DATA_TOOLS_PAGES.ACTION;
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if ("status".equals(request.getParameter("action"))) {
            VitroRequest vreq = new VitroRequest(request);
            if (!isAuthorizedToDisplayPage(request, response, requiredActions(vreq))) {
                return;
            }
            writeStatusJson(response);
            return;
        }
        super.doGet(request, response);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        VitroRequest vreq = new VitroRequest(request);
        if (!isAuthorizedToDisplayPage(request, response, requiredActions(vreq))) {
            return;
        }

        String action = request.getParameter("action");
        String error = null;

        if (!sameOriginOk(request)) {
            error = "Cross-origin request rejected.";
        } else if ("startPubmed".equals(action)) {
            error = startPubmed(vreq);
        } else if ("startReporter".equals(action)) {
            error = startReporter(vreq);
        } else if ("cancel".equals(action)) {
            if (!IngestJobRegistry.cancelRunningJob()) {
                error = "No job is currently running.";
            }
        } else {
            error = "Unknown action.";
        }

        if ("json".equals(request.getParameter("format"))) {
            response.setContentType("application/json;charset=UTF-8");
            ObjectNode result = objectMapper.createObjectNode();
            result.put("ok", error == null);
            if (error != null) {
                result.put("error", error);
            }
            response.getWriter().write(result.toString());
            return;
        }

        // Post/Redirect/Get back to the page; surface any error as a query parameter.
        String location = request.getContextPath() + "/dataIngest";
        if (error != null) {
            location += "?error=" + java.net.URLEncoder.encode(error, "UTF-8");
        }
        response.sendRedirect(location);
    }

    @Override
    protected ResponseValues processRequest(VitroRequest vreq) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("title", "Scholarly data ingest");
        body.put("defaultPubmedQuery", DEFAULT_PUBMED_QUERY);
        body.put("defaultReporterOrg", DEFAULT_REPORTER_ORG);
        body.put("defaultReporterSince", String.valueOf(DEFAULT_REPORTER_SINCE));
        body.put("defaultPubmedMax", String.valueOf(PUBMED_DEFAULT_MAX));
        body.put("defaultReporterMax", String.valueOf(REPORTER_DEFAULT_MAX));
        body.put("jobRunning", IngestJobRegistry.isJobRunning());

        String error = vreq.getParameter("error");
        if (error != null && !error.trim().isEmpty()) {
            body.put("errorMessage", error);
        }

        String email = ConfigurationProperties.getBean(vreq).getProperty("ingest.email");
        body.put("ncbiEmailConfigured", email != null && !email.trim().isEmpty());

        return new TemplateResponseValues(TEMPLATE_NAME, body);
    }

    private String startPubmed(VitroRequest vreq) {
        if (IngestJobRegistry.isJobRunning()) {
            return "Another ingest job is already running.";
        }

        String query = trimmedParam(vreq, "pubmedQuery", DEFAULT_PUBMED_QUERY);
        int yearFrom = intParam(vreq, "yearFrom", 0);
        int yearTo = intParam(vreq, "yearTo", 0);
        int maxRecords = clamp(intParam(vreq, "maxRecords", PUBMED_DEFAULT_MAX), 1, PUBMED_HARD_MAX);
        boolean reprocess = "on".equals(vreq.getParameter("reprocess"));

        String ns = vreq.getWebappDaoFactory().getDefaultNamespace();
        String editorUri = N3EditUtils.getEditorUri(vreq);
        String email = ConfigurationProperties.getBean(vreq).getProperty("ingest.email");

        IngestJob job = new IngestJob("pubmed");
        job.log("PubMed harvest requested: query=\"" + query + "\", max=" + maxRecords
                + (reprocess ? ", reprocessing existing records" : ""));

        PubmedHarvester harvester = new PubmedHarvester(job, getServletContext(), ns, editorUri,
                query, yearFrom, yearTo, maxRecords, reprocess, email);

        if (!IngestJobRegistry.start(job, harvester)) {
            return "Another ingest job is already running.";
        }
        log.info("Started PubMed ingest job " + job.getId());
        return null;
    }

    private String startReporter(VitroRequest vreq) {
        if (IngestJobRegistry.isJobRunning()) {
            return "Another ingest job is already running.";
        }

        String orgName = trimmedParam(vreq, "orgName", DEFAULT_REPORTER_ORG);
        int sinceYear = intParam(vreq, "sinceYear", DEFAULT_REPORTER_SINCE);
        int maxRecords = clamp(intParam(vreq, "maxRecords", REPORTER_DEFAULT_MAX), 1, REPORTER_HARD_MAX);
        boolean reprocess = "on".equals(vreq.getParameter("reprocess"));

        String ns = vreq.getWebappDaoFactory().getDefaultNamespace();
        String editorUri = N3EditUtils.getEditorUri(vreq);

        IngestJob job = new IngestJob("reporter");
        job.log("RePORTER harvest requested: org=\"" + orgName + "\", since FY" + sinceYear
                + ", max=" + maxRecords + (reprocess ? ", reprocessing existing records" : ""));

        ReporterHarvester harvester = new ReporterHarvester(job, getServletContext(), ns, editorUri,
                orgName, sinceYear, maxRecords, reprocess);

        if (!IngestJobRegistry.start(job, harvester)) {
            return "Another ingest job is already running.";
        }
        log.info("Started RePORTER ingest job " + job.getId());
        return null;
    }

    private void writeStatusJson(HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");

        ObjectNode root = objectMapper.createObjectNode();
        IngestJob job = IngestJobRegistry.getCurrentJob();
        root.put("running", IngestJobRegistry.isJobRunning());

        if (job != null) {
            ObjectNode jobNode = root.putObject("job");
            jobNode.put("id", job.getId());
            jobNode.put("type", job.getType());
            jobNode.put("status", job.getStatus().name());
            jobNode.put("processed", job.getProcessed());
            jobNode.put("total", job.getTotal());
            jobNode.put("written", job.getWritten());
            jobNode.put("skipped", job.getSkipped());
            jobNode.put("errors", job.getErrors());
            jobNode.put("message", job.getMessage());
            jobNode.put("startedAt", job.getStartedAt());
            jobNode.put("endedAt", job.getEndedAt());

            ArrayNode logNode = jobNode.putArray("log");
            for (String line : job.getRecentLog(50)) {
                logNode.add(line);
            }
        }

        response.getWriter().write(root.toString());
    }

    /**
     * Lightweight CSRF mitigation: when the browser supplies an Origin or
     * Referer header, its host must match the request host. Requests without
     * either header (same-origin form posts from privacy-strict browsers,
     * curl) are allowed.
     */
    private boolean sameOriginOk(HttpServletRequest request) {
        String source = request.getHeader("Origin");
        if (source == null || source.trim().isEmpty()) {
            source = request.getHeader("Referer");
        }
        if (source == null || source.trim().isEmpty()) {
            return true;
        }
        try {
            return new java.net.URL(source.trim()).getHost().equalsIgnoreCase(request.getServerName());
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    private String trimmedParam(VitroRequest vreq, String name, String fallback) {
        String value = vreq.getParameter(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private int intParam(VitroRequest vreq, String name, int fallback) {
        String value = vreq.getParameter(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim(), 10);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
