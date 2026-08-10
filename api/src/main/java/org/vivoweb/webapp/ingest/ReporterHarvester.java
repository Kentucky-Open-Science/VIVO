/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.cornell.mannlib.vitro.webapp.modelaccess.ModelAccess;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Harvests grant records from the public NIH RePORTER v2 API and writes
 * VIVO-ISF RDF into the triple store in batches.
 *
 * Per-fiscal-year project records are grouped by core project number: the
 * newest record supplies the metadata and award amounts are summed across
 * years, mirroring the UK Knowledge Graph RePORTER pipeline.
 */
public class ReporterHarvester implements Runnable {

    private static final Log log = LogFactory.getLog(ReporterHarvester.class);

    private static final String SEARCH_URL = "https://api.reporter.nih.gov/v2/projects/search";

    /** Records requested per API page (RePORTER maximum is 500). */
    private static final int PAGE_SIZE = 500;
    /** RePORTER rejects requests where offset + limit exceeds 14999. */
    private static final int MAX_OFFSET = 14999;
    /** Grants per ChangeSet write. */
    private static final int WRITE_BATCH_SIZE = 25;
    /** Pause between API calls (RePORTER asks for at most one request per second). */
    private static final long THROTTLE_MS = 1000;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 120000;

    private final IngestJob job;
    private final ServletContext ctx;
    private final String defaultNamespace;
    private final String editorUri;
    private final String orgName;
    private final int sinceFiscalYear;
    private final int maxRecords;
    private final boolean reprocess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReporterHarvester(IngestJob job, ServletContext ctx, String defaultNamespace, String editorUri,
            String orgName, int sinceFiscalYear, int maxRecords, boolean reprocess) {
        this.job = job;
        this.ctx = ctx;
        this.defaultNamespace = defaultNamespace;
        this.editorUri = editorUri;
        this.orgName = orgName;
        this.sinceFiscalYear = sinceFiscalYear;
        this.maxRecords = maxRecords;
        this.reprocess = reprocess;
    }

    /** Aggregate of all fiscal-year records for one core project number. */
    private static class GrantAggregate {
        private JsonNode latestRecord;
        private int latestFiscalYear = -1;
        private long totalAmount = 0;
    }

    @Override
    public void run() {
        try {
            RDFService rdfService = ModelAccess.on(ctx).getRDFService();
            RdfWriter writer = new RdfWriter(rdfService, editorUri);

            job.log("Starting NIH RePORTER harvest for organization: " + orgName
                    + " (fiscal years since " + sinceFiscalYear + ")");

            Map<String, GrantAggregate> grants = collectGrants();
            if (job.isCancelRequested()) {
                job.log("RePORTER harvest cancelled during record collection.");
                return;
            }

            job.setTotal(grants.size());
            job.log("Collected " + grants.size() + " distinct grants to process.");

            Model batch = ModelFactory.createDefaultModel();
            int inBatch = 0;

            for (Map.Entry<String, GrantAggregate> entry : grants.entrySet()) {
                if (job.isCancelRequested()) {
                    break;
                }

                String coreNumber = entry.getKey();
                GrantAggregate agg = entry.getValue();

                try {
                    if (!reprocess && grantExists(writer, coreNumber)) {
                        job.incrementProcessed();
                        job.incrementSkipped();
                        continue;
                    }

                    ReporterRdfMapper.mapGrant(batch, defaultNamespace, coreNumber, agg.latestRecord, agg.totalAmount);
                    inBatch++;
                    job.incrementProcessed();
                } catch (Exception e) {
                    job.incrementProcessed();
                    job.incrementErrors();
                    job.log("Failed to process grant " + coreNumber + ": " + e.getMessage());
                    log.error("Failed to process grant " + coreNumber, e);
                }

                if (inBatch >= WRITE_BATCH_SIZE) {
                    flush(writer, batch, inBatch);
                    batch = ModelFactory.createDefaultModel();
                    inBatch = 0;
                }
            }

            if (inBatch > 0) {
                flush(writer, batch, inBatch);
            }

            if (job.isCancelRequested()) {
                job.log("RePORTER harvest cancelled after processing " + job.getProcessed() + " grants.");
            } else {
                job.log("RePORTER harvest finished. Processed " + job.getProcessed() + ", wrote "
                        + job.getWritten() + ", skipped " + job.getSkipped() + ", errors " + job.getErrors() + ".");
            }
            job.setMessage("Wrote " + job.getWritten() + " grants (" + job.getSkipped() + " already present).");
        } catch (Exception e) {
            log.error("RePORTER harvest failed", e);
            job.fail(e.getMessage());
        }
    }

    private void flush(RdfWriter writer, Model batch, int grantCount) {
        try {
            if (reprocess) {
                writer.replace(batch);
            } else {
                writer.write(batch);
            }
            job.addWritten(grantCount);
            job.log("Wrote batch of " + grantCount + " grants (" + batch.size() + " triples).");
        } catch (Exception e) {
            job.incrementErrors();
            job.log("Failed to write batch: " + e.getMessage());
            log.error("Failed to write batch", e);
        }
    }

    private boolean grantExists(RdfWriter writer, String coreNumber) throws Exception {
        String grantUri = ReporterRdfMapper.grantUri(defaultNamespace, coreNumber);
        String ask = "ASK { <" + grantUri + "> <" + ReporterRdfMapper.VIVO_SPONSOR_AWARD_ID + "> ?id }";
        return writer.ask(ask);
    }

    /** Pages through the search API, grouping per-fiscal-year records by core project number. */
    private Map<String, GrantAggregate> collectGrants() throws IOException, InterruptedException {
        Map<String, GrantAggregate> grants = new LinkedHashMap<String, GrantAggregate>();
        int offset = 0;
        int fetched = 0;

        while (fetched < maxRecords && offset + PAGE_SIZE <= MAX_OFFSET + 1) {
            if (job.isCancelRequested()) {
                break;
            }

            int limit = Math.min(PAGE_SIZE, maxRecords - fetched);

            throttle();
            JsonNode root;
            try {
                root = objectMapper.readTree(httpPost(buildRequestBody(offset, limit)));
            } catch (IOException e) {
                job.incrementErrors();
                job.log("RePORTER request at offset " + offset + " failed: " + e.getMessage());
                log.error("RePORTER request failed", e);
                break;
            }

            JsonNode results = root.get("results");
            int found = 0;
            if (results != null && results.isArray()) {
                for (JsonNode record : results) {
                    found++;
                    String coreNumber = textOf(record, "core_project_num");
                    if (coreNumber == null || coreNumber.trim().isEmpty()) {
                        continue;
                    }
                    coreNumber = coreNumber.trim();

                    GrantAggregate agg = grants.get(coreNumber);
                    if (agg == null) {
                        agg = new GrantAggregate();
                        grants.put(coreNumber, agg);
                    }

                    // Subproject records share the parent's core project number;
                    // counting them would double both metadata and amounts.
                    String subprojectId = textOf(record, "subproject_id");
                    boolean isSubproject = subprojectId != null && !subprojectId.trim().isEmpty();

                    int fiscalYear = intOf(record, "fiscal_year");
                    if (!isSubproject && fiscalYear >= agg.latestFiscalYear) {
                        agg.latestFiscalYear = fiscalYear;
                        agg.latestRecord = record;
                    } else if (agg.latestRecord == null) {
                        agg.latestRecord = record;
                    }

                    if (!isSubproject) {
                        long amount = longOf(record, "award_amount");
                        if (amount > 0) {
                            agg.totalAmount += amount;
                        }
                    }
                }
            }

            int totalAvailable = 0;
            JsonNode meta = root.get("meta");
            if (meta != null && meta.get("total") != null) {
                totalAvailable = meta.get("total").asInt(0);
            }

            fetched += found;
            offset += found;
            job.log("RePORTER page at offset " + (offset - found) + " returned " + found
                    + " records (total available: " + totalAvailable + ").");

            if (found == 0 || offset >= totalAvailable) {
                break;
            }
        }

        return grants;
    }

    private String buildRequestBody(int offset, int limit) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode criteria = body.putObject("criteria");

        ArrayNode orgNames = criteria.putArray("org_names");
        orgNames.add(orgName);

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        if (sinceFiscalYear > 0 && sinceFiscalYear <= currentYear) {
            ArrayNode years = criteria.putArray("fiscal_years");
            for (int year = sinceFiscalYear; year <= currentYear; year++) {
                years.add(year);
            }
        }

        body.put("offset", offset);
        body.put("limit", limit);
        return body.toString();
    }

    private String httpPost(String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(SEARCH_URL).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            OutputStream out = conn.getOutputStream();
            try {
                out.write(jsonBody.getBytes("UTF-8"));
            } finally {
                out.close();
            }

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("RePORTER returned HTTP " + status);
            }
            return readStream(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private String readStream(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    private void throttle() throws InterruptedException {
        Thread.sleep(THROTTLE_MS);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = (node == null) ? null : node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static int intOf(JsonNode node, String field) {
        JsonNode value = (node == null) ? null : node.get(field);
        return (value == null || value.isNull()) ? 0 : value.asInt(0);
    }

    private static long longOf(JsonNode node, String field) {
        JsonNode value = (node == null) ? null : node.get(field);
        return (value == null || value.isNull()) ? 0L : value.asLong(0L);
    }
}
