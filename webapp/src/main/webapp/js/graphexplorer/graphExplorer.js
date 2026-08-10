/* $This file is distributed under the terms of the license in LICENSE$ */
/*
 * Interactive graph explorer for VIVO. Requires the bundled D3 v4
 * (js/d3.min.js) and the global graphExplorerBase set by graphExplorer.ftl.
 */
(function() {
    'use strict';

    if (typeof d3 === 'undefined') {
        return;
    }

    var BASE = (typeof graphExplorerBase !== 'undefined') ? graphExplorerBase : '';
    var AJAX_URL = BASE + '/graphExplorerAjax';
    var MAX_NODES = 400;
    var LABELS_VISIBLE_THRESHOLD = 60;

    var TYPE_COLORS = {
        person: '#0072B2',
        organization: '#E69F00',
        publication: '#009E73',
        grant: '#CC79A7',
        concept: '#D55E00',
        other: '#999999'
    };

    var nodes = [];
    var links = [];
    var nodesById = {};

    var svg, container, linkGroup, nodeGroup, labelGroup, simulation;
    var width = 800;
    var height = 600;
    var clickTimer = null;

    function idOf(endpoint) {
        return (typeof endpoint === 'object' && endpoint !== null) ? endpoint.id : endpoint;
    }

    function getJSON(url, callback) {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, true);
        xhr.onreadystatechange = function() {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 200) {
                var data = null;
                try {
                    data = JSON.parse(xhr.responseText);
                } catch (e) {
                    notice('Could not parse server response.');
                    return;
                }
                callback(data);
            } else {
                notice('Request failed (' + xhr.status + ').');
            }
        };
        xhr.send();
    }

    function escapeHtml(s) {
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function notice(message) {
        var el = document.getElementById('geNotice');
        if (!el) {
            return;
        }
        el.textContent = message;
        el.style.opacity = '1';
        if (el._geTimer) {
            clearTimeout(el._geTimer);
        }
        el._geTimer = setTimeout(function() {
            el.style.opacity = '0';
        }, 4000);
    }

    function degreeMap() {
        var deg = {};
        var i;
        for (i = 0; i < links.length; i++) {
            var s = idOf(links[i].source);
            var t = idOf(links[i].target);
            deg[s] = (deg[s] || 0) + 1;
            deg[t] = (deg[t] || 0) + 1;
        }
        return deg;
    }

    function nodeRadius(d, deg) {
        var degree = deg[d.id] || 0;
        var r = 6 + 2 * Math.sqrt(degree);
        if (d.root) {
            r = Math.max(r, 10);
        }
        return Math.min(r, 24);
    }

    function mergeGraph(data, rootId) {
        if (!data || data.error) {
            notice(data && data.error ? data.error : 'No data returned.');
            return;
        }
        var i, n, existing;
        var anchor = rootId ? nodesById[rootId] : null;
        var ax = anchor ? anchor.x : width / 2;
        var ay = anchor ? anchor.y : height / 2;

        for (i = 0; i < (data.nodes || []).length; i++) {
            n = data.nodes[i];
            existing = nodesById[n.id];
            if (existing) {
                if (n.root) {
                    existing.root = true;
                }
                if (existing.type === 'other' && n.type && n.type !== 'other') {
                    existing.type = n.type;
                }
                if (n.label) {
                    existing.label = n.label;
                }
            } else {
                var angle = Math.random() * 2 * Math.PI;
                var dist = 40 + Math.random() * 80;
                var newNode = {
                    id: n.id,
                    label: n.label || n.id,
                    type: n.type || 'other',
                    root: !!n.root,
                    x: ax + dist * Math.cos(angle),
                    y: ay + dist * Math.sin(angle)
                };
                nodes.push(newNode);
                nodesById[n.id] = newNode;
            }
        }

        var linkKeys = {};
        for (i = 0; i < links.length; i++) {
            linkKeys[idOf(links[i].source) + '|' + links[i].label + '|' + idOf(links[i].target)] = true;
        }
        for (i = 0; i < (data.links || []).length; i++) {
            var l = data.links[i];
            if (!nodesById[l.source] || !nodesById[l.target]) {
                continue;
            }
            var key = l.source + '|' + l.label + '|' + l.target;
            var reverseKey = l.target + '|' + l.label + '|' + l.source;
            if (linkKeys[key] || linkKeys[reverseKey]) {
                continue;
            }
            linkKeys[key] = true;
            links.push({source: l.source, target: l.target, label: l.label || ''});
        }

        enforceNodeCap();
        render();
    }

    function enforceNodeCap() {
        if (nodes.length <= MAX_NODES) {
            return;
        }
        var deg = degreeMap();
        var removable = nodes.filter(function(d) { return !d.root; });
        removable.sort(function(a, b) { return (deg[a.id] || 0) - (deg[b.id] || 0); });
        var toRemove = nodes.length - MAX_NODES;
        var removed = {};
        var i;
        for (i = 0; i < toRemove && i < removable.length; i++) {
            removed[removable[i].id] = true;
        }
        var removedCount = Object.keys(removed).length;
        if (removedCount === 0) {
            return;
        }
        nodes = nodes.filter(function(d) { return !removed[d.id]; });
        links = links.filter(function(l) {
            return !removed[idOf(l.source)] && !removed[idOf(l.target)];
        });
        nodesById = {};
        for (i = 0; i < nodes.length; i++) {
            nodesById[nodes[i].id] = nodes[i];
        }
        notice('Dropped ' + removedCount + ' low-degree nodes to stay under the ' + MAX_NODES + ' node limit.');
    }

    function clearGraph() {
        nodes = [];
        links = [];
        nodesById = {};
        render();
    }

    function render() {
        var deg = degreeMap();

        var linkSel = linkGroup.selectAll('line.ge-link')
            .data(links, function(l) {
                return idOf(l.source) + '|' + l.label + '|' + idOf(l.target);
            });
        linkSel.exit().remove();
        var linkEnter = linkSel.enter().append('line')
            .attr('class', 'ge-link');
        linkEnter.append('title').text(function(l) { return l.label; });
        linkSel = linkEnter.merge(linkSel);

        var nodeSel = nodeGroup.selectAll('circle.ge-node')
            .data(nodes, function(d) { return d.id; });
        nodeSel.exit().remove();
        var nodeEnter = nodeSel.enter().append('circle')
            .attr('class', 'ge-node')
            .call(d3.drag()
                .on('start', dragStarted)
                .on('drag', dragged)
                .on('end', dragEnded))
            .on('click', onNodeClick)
            .on('dblclick', onNodeDblClick);
        nodeEnter.append('title');
        nodeSel = nodeEnter.merge(nodeSel);
        nodeSel
            .attr('r', function(d) { return nodeRadius(d, deg); })
            .style('fill', function(d) { return TYPE_COLORS[d.type] || TYPE_COLORS.other; })
            .classed('ge-node-root', function(d) { return d.root; });
        nodeSel.select('title').text(function(d) { return d.label; });

        var showLabels = nodes.length > 0 && nodes.length < LABELS_VISIBLE_THRESHOLD;
        var labelSel = labelGroup.selectAll('text.ge-label')
            .data(showLabels ? nodes : [], function(d) { return d.id; });
        labelSel.exit().remove();
        var labelEnter = labelSel.enter().append('text')
            .attr('class', 'ge-label');
        labelSel = labelEnter.merge(labelSel);
        labelSel.text(function(d) {
            return d.label.length > 28 ? d.label.substring(0, 27) + '…' : d.label;
        });

        simulation.nodes(nodes);
        simulation.force('link').links(links);
        simulation.alpha(0.8).restart();

        simulation.on('tick', function() {
            linkSel
                .attr('x1', function(l) { return l.source.x; })
                .attr('y1', function(l) { return l.source.y; })
                .attr('x2', function(l) { return l.target.x; })
                .attr('y2', function(l) { return l.target.y; });
            nodeSel
                .attr('cx', function(d) { return d.x; })
                .attr('cy', function(d) { return d.y; });
            labelSel
                .attr('x', function(d) { return d.x + nodeRadius(d, deg) + 3; })
                .attr('y', function(d) { return d.y + 4; });
        });
    }

    function onNodeClick(d) {
        if (d3.event.defaultPrevented) {
            return;
        }
        var uri = d.id;
        if (clickTimer) {
            clearTimeout(clickTimer);
        }
        clickTimer = setTimeout(function() {
            clickTimer = null;
            loadNeighborhood(uri);
        }, 280);
    }

    function onNodeDblClick(d) {
        if (clickTimer) {
            clearTimeout(clickTimer);
            clickTimer = null;
        }
        window.open(BASE + '/individual?uri=' + encodeURIComponent(d.id), '_blank');
    }

    function dragStarted(d) {
        if (!d3.event.active) {
            simulation.alphaTarget(0.3).restart();
        }
        d.fx = d.x;
        d.fy = d.y;
    }

    function dragged(d) {
        d.fx = d3.event.x;
        d.fy = d3.event.y;
    }

    function dragEnded(d) {
        if (!d3.event.active) {
            simulation.alphaTarget(0);
        }
        d.fx = null;
        d.fy = null;
    }

    function loadNeighborhood(uri) {
        getJSON(AJAX_URL + '?action=neighborhood&uri=' + encodeURIComponent(uri), function(data) {
            mergeGraph(data, uri);
        });
    }

    function loadOverview() {
        getJSON(AJAX_URL + '?action=overview', function(data) {
            mergeGraph(data, null);
        });
    }

    function renderSearchResults(results) {
        var list = document.getElementById('geSearchResults');
        if (!list) {
            return;
        }
        list.innerHTML = '';
        if (!results || !results.length) {
            return;
        }
        var i;
        for (i = 0; i < results.length; i++) {
            (function(item) {
                var li = document.createElement('li');
                li.innerHTML = '<span class="ge-swatch ge-swatch-' + escapeHtml(item.type || 'other')
                    + '"></span> ' + escapeHtml(item.label);
                li.title = item.uri;
                li.addEventListener('click', function() {
                    list.innerHTML = '';
                    document.getElementById('geSearchInput').value = item.label;
                    loadNeighborhood(item.uri);
                });
                list.appendChild(li);
            })(results[i]);
        }
    }

    function initSearch() {
        var input = document.getElementById('geSearchInput');
        if (!input) {
            return;
        }
        var searchTimer = null;
        input.addEventListener('input', function() {
            var q = input.value.trim();
            if (searchTimer) {
                clearTimeout(searchTimer);
            }
            if (q.length < 2) {
                renderSearchResults([]);
                return;
            }
            searchTimer = setTimeout(function() {
                getJSON(AJAX_URL + '?action=search&q=' + encodeURIComponent(q), function(data) {
                    if (data && data.error) {
                        notice(data.error);
                        return;
                    }
                    renderSearchResults(data);
                });
            }, 300);
        });
    }

    function initCanvas() {
        var canvas = document.getElementById('geCanvas');
        if (!canvas) {
            return false;
        }
        width = canvas.clientWidth || 800;
        height = canvas.clientHeight || 600;

        svg = d3.select(canvas).append('svg')
            .attr('width', '100%')
            .attr('height', '100%');

        container = svg.append('g').attr('class', 'ge-container');
        linkGroup = container.append('g').attr('class', 'ge-links');
        nodeGroup = container.append('g').attr('class', 'ge-nodes');
        labelGroup = container.append('g').attr('class', 'ge-labels');

        var zoom = d3.zoom()
            .scaleExtent([0.1, 8])
            .on('zoom', function() {
                container.attr('transform', d3.event.transform);
            });
        svg.call(zoom).on('dblclick.zoom', null);

        simulation = d3.forceSimulation()
            .force('link', d3.forceLink().id(function(d) { return d.id; }).distance(70))
            .force('charge', d3.forceManyBody().strength(-150))
            .force('center', d3.forceCenter(width / 2, height / 2))
            .force('collide', d3.forceCollide().radius(14));

        window.addEventListener('resize', function() {
            width = canvas.clientWidth || width;
            height = canvas.clientHeight || height;
            simulation.force('center', d3.forceCenter(width / 2, height / 2));
        });
        return true;
    }

    function initButtons() {
        var clearButton = document.getElementById('geClearButton');
        if (clearButton) {
            clearButton.addEventListener('click', function() {
                clearGraph();
            });
        }
        var overviewButton = document.getElementById('geOverviewButton');
        if (overviewButton) {
            overviewButton.addEventListener('click', function() {
                clearGraph();
                loadOverview();
            });
        }
    }

    function init() {
        if (!initCanvas()) {
            return;
        }
        initSearch();
        initButtons();
        loadOverview();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
