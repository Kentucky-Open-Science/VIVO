/* $This file is distributed under the terms of the license in LICENSE$ */

(function () {
    'use strict';

    var POLL_INTERVAL_MS = 2000;
    var MAX_BACKOFF_MS = 30000;
    var pollTimer = null;
    var consecutiveFailures = 0;
    var lastKnownRunning = false;

    function byId(id) {
        return document.getElementById(id);
    }

    function setText(id, text) {
        var el = byId(id);
        if (el) {
            el.textContent = text;
        }
    }

    function show(el, visible) {
        if (el) {
            el.style.display = visible ? '' : 'none';
        }
    }

    function setFormsDisabled(disabled) {
        var buttons = document.querySelectorAll('.data-ingest-start');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].disabled = disabled;
        }
    }

    function describeType(type) {
        if (type === 'pubmed') {
            return 'PubMed publications';
        }
        if (type === 'reporter') {
            return 'NIH RePORTER grants';
        }
        return type || '';
    }

    function render(data) {
        var panel = byId('data-ingest-status');
        if (!data || !data.job) {
            show(panel, false);
            setFormsDisabled(false);
            return;
        }

        var job = data.job;
        show(panel, true);
        setText('data-ingest-status-type', describeType(job.type));
        setText('data-ingest-status-state', job.status);

        var counts = 'Processed ' + job.processed + ' of ' + job.total
            + ' — written ' + job.written
            + ', skipped ' + job.skipped
            + ', errors ' + job.errors;
        setText('data-ingest-status-counts', counts);
        setText('data-ingest-status-message', job.message || '');

        var bar = byId('data-ingest-progress-bar');
        if (bar) {
            var pct = job.total > 0 ? Math.min(100, Math.round(100 * job.processed / job.total)) : 0;
            bar.style.width = pct + '%';
        }

        var logEl = byId('data-ingest-log');
        if (logEl && job.log) {
            logEl.textContent = job.log.join('\n');
            logEl.scrollTop = logEl.scrollHeight;
        }

        var cancelForm = byId('data-ingest-cancel-form');
        show(cancelForm, data.running === true);
        setFormsDisabled(data.running === true);

        lastKnownRunning = data.running === true;
        if (data.running) {
            scheduleNextPoll();
        }
    }

    function poll() {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', dataIngestConfig.statusUrl, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 200) {
                try {
                    consecutiveFailures = 0;
                    render(JSON.parse(xhr.responseText));
                    return;
                } catch (e) {
                    // Malformed response (e.g. login page HTML); fall through to retry.
                }
            }
            // Transient failure: keep polling with capped backoff while a job
            // was last known to be running, so the panel self-heals.
            if (lastKnownRunning) {
                consecutiveFailures++;
                scheduleNextPoll();
            }
        };
        xhr.send();
    }

    function scheduleNextPoll() {
        if (pollTimer) {
            clearTimeout(pollTimer);
        }
        var delay = Math.min(MAX_BACKOFF_MS, POLL_INTERVAL_MS * Math.pow(2, consecutiveFailures));
        pollTimer = setTimeout(poll, delay);
    }

    if (typeof dataIngestConfig !== 'undefined') {
        poll();
    }
})();
