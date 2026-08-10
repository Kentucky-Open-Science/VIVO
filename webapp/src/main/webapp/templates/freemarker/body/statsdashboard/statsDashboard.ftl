<#-- $This file is distributed under the terms of the license in LICENSE$ -->

${stylesheets.add('<link rel="stylesheet" href="${urls.base}/css/statsdashboard/statsDashboard.css" />')}

<h2>Statistics Dashboard</h2>

<div id="stats-dashboard">
    <div id="stats-dashboard-loading" class="stats-dashboard-loading">Loading dashboard data&#8230;</div>
    <div id="stats-dashboard-error" class="stats-dashboard-error" style="display: none;">
        Dashboard data is currently unavailable. Please try again later.
    </div>
    <div id="stats-dashboard-content" style="display: none;">
        <div id="stats-dashboard-stats" class="stats-dashboard-stats"></div>
        <div class="stats-dashboard-columns">
            <div class="stats-dashboard-panel">
                <h3>Recent Publications</h3>
                <ul id="stats-dashboard-recent-publications" class="stats-dashboard-list"></ul>
            </div>
            <div class="stats-dashboard-panel">
                <h3>Top Researchers</h3>
                <ul id="stats-dashboard-top-researchers" class="stats-dashboard-list"></ul>
            </div>
        </div>
        <div class="stats-dashboard-panel stats-dashboard-panel-full">
            <h3>Top Organizations</h3>
            <ul id="stats-dashboard-top-organizations" class="stats-dashboard-list"></ul>
        </div>
    </div>
</div>

<script type="text/javascript">
    var statsDashboardConfig = {
        baseUrl: '${urls.base}',
        ajaxUrl: '${urls.base}/dashboardAjax'
    };
</script>

${scripts.add('<script type="text/javascript" src="${urls.base}/js/statsdashboard/statsDashboard.js"></script>')}
