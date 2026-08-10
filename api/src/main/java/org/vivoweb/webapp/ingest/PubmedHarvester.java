/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.cornell.mannlib.vitro.webapp.modelaccess.ModelAccess;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Harvests publication metadata from PubMed via NCBI eUtils (esearch +
 * esummary) and writes VIVO-ISF RDF into the triple store in batches.
 *
 * Runs on a background daemon thread; progress and log output are reported
 * through the associated {@link IngestJob}. Respects the NCBI rate limit of
 * three requests per second for unauthenticated clients.
 */
public class PubmedHarvester implements Runnable {

    private static final Log log = LogFactory.getLog(PubmedHarvester.class);

    private static final String EUTILS_BASE = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";
    private static final String TOOL_NAME = "vivo-data-ingest";

    /** IDs requested per esearch page. */
    private static final int SEARCH_PAGE_SIZE = 200;
    /** IDs per esummary call (NCBI recommends at most a few hundred). */
    private static final int SUMMARY_BATCH_SIZE = 100;
    /** Articles per ChangeSet write. */
    private static final int WRITE_BATCH_SIZE = 25;
    /** Pause between eUtils calls: under three requests per second. */
    private static final long THROTTLE_MS = 350;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final IngestJob job;
    private final ServletContext ctx;
    private final String defaultNamespace;
    private final String editorUri;
    private final String query;
    private final int yearFrom;
    private final int yearTo;
    private final int maxRecords;
    private final boolean reprocess;
    private final String email;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PubmedHarvester(IngestJob job, ServletContext ctx, String defaultNamespace, String editorUri,
            String query, int yearFrom, int yearTo, int maxRecords, boolean reprocess, String email) {
        this.job = job;
        this.ctx = ctx;
        this.defaultNamespace = defaultNamespace;
        this.editorUri = editorUri;
        this.query = query;
        this.yearFrom = yearFrom;
        this.yearTo = yearTo;
        this.maxRecords = maxRecords;
        this.reprocess = reprocess;
        this.email = email;
    }

    @Override
    public void run() {
        try {
            RDFService rdfService = ModelAccess.on(ctx).getRDFService();
            RdfWriter writer = new RdfWriter(rdfService, editorUri);

            job.log("Starting PubMed harvest for query: " + query);

            List<String> pmids = collectIds();
            job.setTotal(pmids.size());
            job.log("Found " + pmids.size() + " PubMed IDs to process.");

            Model batch = ModelFactory.createDefaultModel();
            int inBatch = 0;

            for (int offset = 0; offset < pmids.size(); offset += SUMMARY_BATCH_SIZE) {
                if (job.isCancelRequested()) {
                    break;
                }

                int end = Math.min(offset + SUMMARY_BATCH_SIZE, pmids.size());
                List<String> chunk = pmids.subList(offset, end);

                throttle();
                JsonNode result;
                try {
                    result = fetchSummaries(chunk);
                } catch (IOException e) {
                    job.incrementErrors();
                    job.log("esummary request failed for IDs " + offset + "-" + end + ": " + e.getMessage());
                    log.error("esummary request failed", e);
                    continue;
                }

                for (String pmid : chunk) {
                    if (job.isCancelRequested()) {
                        break;
                    }
                    JsonNode summary = (result == null) ? null : result.get(pmid);
                    if (summary == null || summary.get("error") != null) {
                        job.incrementProcessed();
                        job.incrementErrors();
                        job.log("No summary returned for PMID " + pmid);
                        continue;
                    }

                    try {
                        if (!reprocess && articleExists(writer, pmid)) {
                            job.incrementProcessed();
                            job.incrementSkipped();
                            continue;
                        }

                        PubmedRdfMapper.mapArticle(batch, defaultNamespace, pmid, summary);
                        inBatch++;
                        job.incrementProcessed();
                    } catch (Exception e) {
                        job.incrementProcessed();
                        job.incrementErrors();
                        job.log("Failed to process PMID " + pmid + ": " + e.getMessage());
                        log.error("Failed to process PMID " + pmid, e);
                    }

                    if (inBatch >= WRITE_BATCH_SIZE) {
                        flush(writer, batch, inBatch);
                        batch = ModelFactory.createDefaultModel();
                        inBatch = 0;
                    }
                }
            }

            if (inBatch > 0) {
                flush(writer, batch, inBatch);
            }

            if (job.isCancelRequested()) {
                job.log("PubMed harvest cancelled after processing " + job.getProcessed() + " records.");
            } else {
                job.log("PubMed harvest finished. Processed " + job.getProcessed() + ", wrote "
                        + job.getWritten() + ", skipped " + job.getSkipped() + ", errors " + job.getErrors() + ".");
            }
            job.setMessage("Wrote " + job.getWritten() + " articles (" + job.getSkipped() + " already present).");
        } catch (Exception e) {
            log.error("PubMed harvest failed", e);
            job.fail(e.getMessage());
        }
    }

    private void flush(RdfWriter writer, Model batch, int articleCount) {
        try {
            if (reprocess) {
                writer.replace(batch);
            } else {
                writer.write(batch);
            }
            job.addWritten(articleCount);
            job.log("Wrote batch of " + articleCount + " articles (" + batch.size() + " triples).");
        } catch (Exception e) {
            job.incrementErrors();
            job.log("Failed to write batch: " + e.getMessage());
            log.error("Failed to write batch", e);
        }
    }

    private boolean articleExists(RdfWriter writer, String pmid) throws Exception {
        String articleUri = PubmedRdfMapper.articleUri(defaultNamespace, pmid);
        String ask = "ASK { <" + articleUri + "> <" + PubmedRdfMapper.BIBO_PMID + "> ?pmid }";
        return writer.ask(ask);
    }

    /** Pages through esearch to collect up to maxRecords PMIDs. */
    private List<String> collectIds() throws IOException, InterruptedException {
        List<String> ids = new ArrayList<String>();
        int retStart = 0;

        while (ids.size() < maxRecords) {
            if (job.isCancelRequested()) {
                break;
            }

            int retMax = Math.min(SEARCH_PAGE_SIZE, maxRecords - ids.size());
            StringBuilder url = new StringBuilder(EUTILS_BASE);
            url.append("esearch.fcgi?db=pubmed&retmode=json");
            url.append("&term=").append(URLEncoder.encode(query, "UTF-8"));
            url.append("&retmax=").append(retMax);
            url.append("&retstart=").append(retStart);
            if (yearFrom > 0 || yearTo > 0) {
                int minYear = (yearFrom > 0) ? yearFrom : 1800;
                int maxYear = (yearTo > 0) ? yearTo : 2100;
                url.append("&datetype=pdat&mindate=").append(minYear).append("&maxdate=").append(maxYear);
            }
            appendIdentification(url);

            throttle();
            JsonNode root = objectMapper.readTree(httpGet(url.toString()));
            JsonNode searchResult = root.get("esearchresult");
            if (searchResult == null) {
                job.log("Unexpected esearch response; stopping ID collection.");
                break;
            }

            JsonNode idList = searchResult.get("idlist");
            int found = 0;
            if (idList != null && idList.isArray()) {
                for (JsonNode idNode : idList) {
                    String id = idNode.asText();
                    found++;
                    // PMIDs are numeric; anything else would also be unsafe to
                    // interpolate into URIs and SPARQL, so drop it.
                    if (id != null && id.matches("[0-9]+")) {
                        ids.add(id);
                    } else {
                        job.incrementErrors();
                        job.log("Ignoring non-numeric PMID from esearch: " + id);
                    }
                }
            }

            int totalAvailable = 0;
            JsonNode countNode = searchResult.get("count");
            if (countNode != null) {
                totalAvailable = countNode.asInt(0);
            }

            job.log("esearch page at offset " + retStart + " returned " + found
                    + " IDs (total available: " + totalAvailable + ").");

            retStart += found;
            if (found == 0 || retStart >= totalAvailable) {
                break;
            }
        }

        return ids;
    }

    private JsonNode fetchSummaries(List<String> pmids) throws IOException {
        StringBuilder url = new StringBuilder(EUTILS_BASE);
        url.append("esummary.fcgi?db=pubmed&retmode=json&id=");
        for (int i = 0; i < pmids.size(); i++) {
            if (i > 0) {
                url.append(',');
            }
            url.append(pmids.get(i));
        }
        appendIdentification(url);

        JsonNode root = objectMapper.readTree(httpGet(url.toString()));
        return root.get("result");
    }

    private void appendIdentification(StringBuilder url) throws IOException {
        url.append("&tool=").append(TOOL_NAME);
        if (email != null && !email.trim().isEmpty()) {
            url.append("&email=").append(URLEncoder.encode(email.trim(), "UTF-8"));
        }
    }

    private String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("eUtils returned HTTP " + status + " for " + url);
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
}
