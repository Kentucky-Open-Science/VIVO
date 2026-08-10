/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Thread-safe registry of ingestion jobs. Only one job may run at a time;
 * the most recent job (running or finished) is retained for status display.
 */
public final class IngestJobRegistry {

    private static final Log log = LogFactory.getLog(IngestJobRegistry.class);

    private static final Object LOCK = new Object();

    private static IngestJob currentJob = null;

    private IngestJobRegistry() {
        // Static utility; never instantiated.
    }

    /** Returns the most recent job, or null if none has been started. */
    public static IngestJob getCurrentJob() {
        synchronized (LOCK) {
            return currentJob;
        }
    }

    /** Returns true if a job is currently running. */
    public static boolean isJobRunning() {
        synchronized (LOCK) {
            return currentJob != null && currentJob.isActive();
        }
    }

    /**
     * Starts the given job on a new daemon thread, unless another job is
     * already running.
     *
     * @param job  the job whose state the work will update
     * @param work the harvester runnable
     * @return true if the job was started, false if another job is running
     */
    public static boolean start(final IngestJob job, final Runnable work) {
        synchronized (LOCK) {
            if (currentJob != null && currentJob.isActive()) {
                return false;
            }

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        work.run();
                    } catch (Throwable t) {
                        log.error("Ingest job " + job.getId() + " failed", t);
                        job.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
                    } finally {
                        job.completeIfRunning();
                    }
                }
            }, "data-ingest-" + job.getType() + "-" + job.getId());

            thread.setDaemon(true);
            job.setWorker(thread);
            currentJob = job;
            thread.start();

            log.info("Started ingest job " + job.getId() + " of type " + job.getType());
            return true;
        }
    }

    /**
     * Requests cooperative cancellation of the running job.
     *
     * @return true if a running job was told to cancel, false if no job is running
     */
    public static boolean cancelRunningJob() {
        synchronized (LOCK) {
            if (currentJob != null && currentJob.isActive()) {
                currentJob.requestCancel();
                return true;
            }
            return false;
        }
    }
}
