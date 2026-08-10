<#-- $This file is distributed under the terms of the license in LICENSE$ -->

${stylesheets.add('<link rel="stylesheet" href="${urls.base}/css/dataingest/dataIngest.css" />')}

<h2>Scholarly Data Ingest</h2>

<p class="data-ingest-intro">
    Harvest publications from PubMed (NCBI eUtils) or grants from NIH RePORTER directly into this
    VIVO. Records are written with deterministic URIs, so re-running a harvest updates rather than
    duplicates. One job runs at a time.
</p>

<#if errorMessage??>
    <div class="data-ingest-error">${errorMessage?html}</div>
</#if>

<div id="data-ingest-status" class="data-ingest-status" style="display: none;">
    <h3>Job status</h3>
    <div class="data-ingest-status-line">
        <span id="data-ingest-status-type"></span>
        <span id="data-ingest-status-state"></span>
    </div>
    <div class="data-ingest-progress">
        <div id="data-ingest-progress-bar" class="data-ingest-progress-bar"></div>
    </div>
    <div id="data-ingest-status-counts" class="data-ingest-status-counts"></div>
    <div id="data-ingest-status-message" class="data-ingest-status-message"></div>
    <pre id="data-ingest-log" class="data-ingest-log"></pre>
    <form method="post" action="${urls.base}/dataIngest" id="data-ingest-cancel-form">
        <input type="hidden" name="action" value="cancel" />
        <button type="submit" id="data-ingest-cancel" class="data-ingest-button data-ingest-button-secondary">
            Cancel running job
        </button>
    </form>
</div>

<div class="data-ingest-forms">
    <section class="data-ingest-panel">
        <h3>PubMed publications</h3>
        <form method="post" action="${urls.base}/dataIngest" class="data-ingest-form">
            <input type="hidden" name="action" value="startPubmed" />
            <label>Search query
                <input type="text" name="pubmedQuery" value="${defaultPubmedQuery}" />
            </label>
            <div class="data-ingest-form-row">
                <label>Year from
                    <input type="number" name="yearFrom" placeholder="e.g. 2020" min="1800" max="2100" />
                </label>
                <label>Year to
                    <input type="number" name="yearTo" placeholder="e.g. 2026" min="1800" max="2100" />
                </label>
                <label>Max records
                    <input type="number" name="maxRecords" value="${defaultPubmedMax}" min="1" max="2000" />
                </label>
            </div>
            <label class="data-ingest-checkbox">
                <input type="checkbox" name="reprocess" /> Re-process records already in VIVO
            </label>
            <button type="submit" class="data-ingest-button data-ingest-start" <#if jobRunning>disabled</#if>>
                Start PubMed harvest
            </button>
        </form>
        <#if !ncbiEmailConfigured>
            <p class="data-ingest-note">
                Tip: set <code>ingest.email</code> in <code>runtime.properties</code> so NCBI can
                contact you about your eUtils usage (and to raise your rate limit).
            </p>
        </#if>
    </section>

    <section class="data-ingest-panel">
        <h3>NIH RePORTER grants</h3>
        <form method="post" action="${urls.base}/dataIngest" class="data-ingest-form">
            <input type="hidden" name="action" value="startReporter" />
            <label>Organization name
                <input type="text" name="orgName" value="${defaultReporterOrg}" />
            </label>
            <div class="data-ingest-form-row">
                <label>Fiscal years since
                    <input type="number" name="sinceYear" value="${defaultReporterSince}" min="1985" max="2100" />
                </label>
                <label>Max records
                    <input type="number" name="maxRecords" value="${defaultReporterMax}" min="1" max="5000" />
                </label>
            </div>
            <label class="data-ingest-checkbox">
                <input type="checkbox" name="reprocess" /> Re-process grants already in VIVO
            </label>
            <button type="submit" class="data-ingest-button data-ingest-start" <#if jobRunning>disabled</#if>>
                Start RePORTER harvest
            </button>
        </form>
        <p class="data-ingest-note">
            RePORTER is a public API; no key is needed. Per-fiscal-year records are merged by core
            project number, with award amounts summed across years.
        </p>
    </section>
</div>

<script type="text/javascript">
    var dataIngestConfig = {
        baseUrl: '${urls.base}',
        statusUrl: '${urls.base}/dataIngest?action=status'
    };
</script>

${scripts.add('<script type="text/javascript" src="${urls.base}/js/dataingest/dataIngest.js"></script>')}
