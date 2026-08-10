/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import edu.cornell.mannlib.vitro.webapp.config.ConfigurationProperties;
import edu.cornell.mannlib.vitro.webapp.modelaccess.ModelAccess;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Optionally starts a PubMed harvest after webapp startup, mirroring the UK
 * Knowledge Graph INGEST_ON_STARTUP automation. Controlled by
 * runtime.properties:
 *
 *   ingest.pubmed.onStartup = true          (default false)
 *   ingest.pubmed.query     = ...           (default University of Kentucky[Affiliation])
 *   ingest.pubmed.max       = 500           (default 500, capped at 2000)
 *   ingest.pubmed.reprocess = false         (default false: skip records already present)
 *
 * The runner waits for the application to finish initializing (retrying until
 * the RDFService is available) before launching, and already-present articles
 * are skipped, so leaving the flag on across restarts is cheap.
 */
@WebListener
public class StartupIngestRunner implements ServletContextListener {

    private static final Log log = LogFactory.getLog(StartupIngestRunner.class);

    private static final long INITIAL_DELAY_MS = 45000L;
    private static final long RETRY_DELAY_MS = 30000L;
    private static final int MAX_RETRIES = 10;
    private static final int DEFAULT_MAX_RECORDS = 500;
    private static final int HARD_MAX_RECORDS = 2000;
    private static final String DEFAULT_QUERY = "University of Kentucky[Affiliation]";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        final ServletContext ctx = sce.getServletContext();

        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(INITIAL_DELAY_MS);
                    ConfigurationProperties props = ConfigurationProperties.getBean(ctx);
                    if (!"true".equalsIgnoreCase(props.getProperty("ingest.pubmed.onStartup", "false"))) {
                        return;
                    }
                    startWhenReady(ctx, props);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("Startup ingest runner failed", e);
                }
            }
        }, "startup-ingest-runner");
        starter.setDaemon(true);
        starter.start();
    }

    private void startWhenReady(ServletContext ctx, ConfigurationProperties props)
            throws InterruptedException {
        String namespace = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // Both of these throw or return null until Vitro finishes starting up.
                if (ModelAccess.on(ctx).getRDFService() != null) {
                    namespace = ModelAccess.on(ctx).getWebappDaoFactory().getDefaultNamespace();
                }
            } catch (Exception e) {
                log.debug("Application not ready for startup ingest yet: " + e.getMessage());
            }
            if (namespace != null && !namespace.isEmpty()) {
                break;
            }
            Thread.sleep(RETRY_DELAY_MS);
        }
        if (namespace == null || namespace.isEmpty()) {
            log.error("Startup ingest gave up: application never became ready.");
            return;
        }

        if (IngestJobRegistry.isJobRunning()) {
            log.info("Startup ingest skipped: another ingest job is already running.");
            return;
        }

        String query = props.getProperty("ingest.pubmed.query", DEFAULT_QUERY);
        int maxRecords = parseInt(props.getProperty("ingest.pubmed.max"), DEFAULT_MAX_RECORDS);
        maxRecords = Math.max(1, Math.min(HARD_MAX_RECORDS, maxRecords));
        boolean reprocess = "true".equalsIgnoreCase(props.getProperty("ingest.pubmed.reprocess", "false"));
        String email = props.getProperty("ingest.email");

        IngestJob job = new IngestJob("pubmed");
        job.log("Automatic startup harvest: query=\"" + query + "\", max=" + maxRecords);

        PubmedHarvester harvester = new PubmedHarvester(job, ctx, namespace, null,
                query, 0, 0, maxRecords, reprocess, email);

        if (IngestJobRegistry.start(job, harvester)) {
            log.info("Startup PubMed ingest launched (query=\"" + query + "\", max=" + maxRecords + ")");
        } else {
            log.info("Startup ingest not launched: another job is running.");
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim(), 10);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Harvest threads are daemons; nothing to clean up.
    }
}
