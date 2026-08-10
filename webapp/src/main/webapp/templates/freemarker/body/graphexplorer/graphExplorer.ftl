<#-- $This file is distributed under the terms of the license in LICENSE$ -->

<h2>Graph Explorer</h2>

<div id="graphExplorerWrap" class="ge-wrap">
    <div id="gePanel" class="ge-panel">
        <div class="ge-search-box">
            <input type="text" id="geSearchInput" placeholder="Search people, orgs, publications..."
                   autocomplete="off" />
            <ul id="geSearchResults" class="ge-search-results"></ul>
        </div>
        <div class="ge-controls">
            <button type="button" id="geClearButton">Clear</button>
            <button type="button" id="geOverviewButton">Overview</button>
        </div>
        <div class="ge-legend">
            <h3>Legend</h3>
            <ul>
                <li><span class="ge-swatch ge-swatch-person"></span> Person</li>
                <li><span class="ge-swatch ge-swatch-organization"></span> Organization</li>
                <li><span class="ge-swatch ge-swatch-publication"></span> Publication</li>
                <li><span class="ge-swatch ge-swatch-grant"></span> Grant</li>
                <li><span class="ge-swatch ge-swatch-concept"></span> Concept</li>
                <li><span class="ge-swatch ge-swatch-other"></span> Other</li>
            </ul>
        </div>
        <div class="ge-help">
            <p>Click a node to expand its connections. Double-click a node to open its profile page.</p>
        </div>
        <div id="geNotice" class="ge-notice"></div>
    </div>
    <div id="geCanvas" class="ge-canvas"></div>
</div>

<script type="text/javascript">
    var graphExplorerBase = '${urls.base}';
</script>

${stylesheets.add('<link rel="stylesheet" href="${urls.base}/css/graphexplorer/graphExplorer.css" />')}
${scripts.add('<script type="text/javascript" src="${urls.base}/js/d3.min.js"></script>',
              '<script type="text/javascript" src="${urls.base}/js/graphexplorer/graphExplorer.js"></script>')}
