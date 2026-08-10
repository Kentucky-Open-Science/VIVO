/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State of a single ingestion job (PubMed or NIH RePORTER harvest).
 *
 * All mutable state is safe for concurrent access: counters are atomic, simple
 * fields are volatile, and the log ring buffer is guarded by its own monitor.
 */
public class IngestJob {

    /** Maximum number of log lines retained in the ring buffer. */
    private static final int MAX_LOG_LINES = 200;

    public enum Status {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private final String id;
    private final String type;
    private final long startedAt;

    private volatile Status status = Status.RUNNING;
    private volatile long endedAt = 0L;
    private volatile String message = "";
    private volatile boolean cancelRequested = false;
    private volatile Thread worker = null;

    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger written = new AtomicInteger(0);
    private final AtomicInteger skipped = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);

    private final Deque<String> logLines = new ArrayDeque<String>();

    public IngestJob(String type) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.startedAt = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Status getStatus() {
        return status;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = (message == null) ? "" : message;
    }

    public int getProcessed() {
        return processed.get();
    }

    public void incrementProcessed() {
        processed.incrementAndGet();
    }

    public int getTotal() {
        return total.get();
    }

    public void setTotal(int value) {
        total.set(value);
    }

    public int getWritten() {
        return written.get();
    }

    public void addWritten(int count) {
        written.addAndGet(count);
    }

    public int getSkipped() {
        return skipped.get();
    }

    public void incrementSkipped() {
        skipped.incrementAndGet();
    }

    public int getErrors() {
        return errors.get();
    }

    public void incrementErrors() {
        errors.incrementAndGet();
    }

    public void requestCancel() {
        cancelRequested = true;
        log("Cancellation requested; the job will stop after the current batch.");
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    void setWorker(Thread worker) {
        this.worker = worker;
    }

    /**
     * A job is active while its status is RUNNING and its worker thread (if
     * assigned yet) has not died unexpectedly.
     */
    public boolean isActive() {
        if (status != Status.RUNNING) {
            return false;
        }
        Thread t = worker;
        return t == null || t.isAlive();
    }

    /** Marks the job COMPLETED if it is still RUNNING (or CANCELLED if a cancel was requested). */
    public synchronized void completeIfRunning() {
        if (status == Status.RUNNING) {
            status = cancelRequested ? Status.CANCELLED : Status.COMPLETED;
            endedAt = System.currentTimeMillis();
        }
    }

    /** Marks the job FAILED, recording the given message. */
    public synchronized void fail(String failureMessage) {
        if (status == Status.RUNNING) {
            status = Status.FAILED;
            endedAt = System.currentTimeMillis();
        }
        setMessage(failureMessage);
        log("FAILED: " + failureMessage);
    }

    /** Appends a timestamped line to the log ring buffer. */
    public void log(String line) {
        String stamped = new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + line;
        synchronized (logLines) {
            while (logLines.size() >= MAX_LOG_LINES) {
                logLines.removeFirst();
            }
            logLines.addLast(stamped);
        }
    }

    /** Returns up to the last {@code maxLines} log lines, oldest first. */
    public List<String> getRecentLog(int maxLines) {
        synchronized (logLines) {
            List<String> all = new ArrayList<String>(logLines);
            if (all.size() <= maxLines) {
                return all;
            }
            return new ArrayList<String>(all.subList(all.size() - maxLines, all.size()));
        }
    }
}
