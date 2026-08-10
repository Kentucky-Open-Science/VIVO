/* $This file is distributed under the terms of the license in LICENSE$ */

(function() {
    "use strict";

    function getConfig() {
        return window.statsDashboardConfig || { baseUrl: "", ajaxUrl: "/dashboardAjax" };
    }

    function individualUrl(uri) {
        return getConfig().baseUrl + "/individual?uri=" + encodeURIComponent(uri || "");
    }

    function formatNumber(n) {
        var value = typeof n === "number" ? n : 0;
        return value.toLocaleString();
    }

    function el(tag, className, text) {
        var node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.appendChild(document.createTextNode(String(text)));
        }
        return node;
    }

    function renderStats(counts) {
        var container = document.getElementById("stats-dashboard-stats");
        if (!container) {
            return;
        }
        container.innerHTML = "";
        var cards = [
            { key: "people", label: "People" },
            { key: "publications", label: "Publications" },
            { key: "grants", label: "Grants" },
            { key: "organizations", label: "Organizations" },
            { key: "concepts", label: "Concepts" }
        ];
        var i;
        for (i = 0; i < cards.length; i++) {
            var card = el("div", "stats-dashboard-card");
            var value = counts && typeof counts[cards[i].key] === "number" ? counts[cards[i].key] : 0;
            card.appendChild(el("div", "stats-dashboard-card-value", formatNumber(value)));
            card.appendChild(el("div", "stats-dashboard-card-label", cards[i].label));
            container.appendChild(card);
        }
    }

    function renderList(listId, items, metaFormatter) {
        var list = document.getElementById(listId);
        if (!list) {
            return;
        }
        list.innerHTML = "";
        if (!items || items.length === 0) {
            var empty = el("li", "stats-dashboard-empty", "No data available.");
            list.appendChild(empty);
            return;
        }
        var i;
        for (i = 0; i < items.length; i++) {
            var item = items[i];
            var li = el("li", "stats-dashboard-item");
            var link = el("a", "stats-dashboard-link", item.label || item.uri || "(no label)");
            link.href = individualUrl(item.uri);
            li.appendChild(link);
            var meta = metaFormatter(item);
            if (meta) {
                li.appendChild(el("span", "stats-dashboard-meta", meta));
            }
            list.appendChild(li);
        }
    }

    function render(data) {
        renderStats(data.counts || {});
        renderList("stats-dashboard-recent-publications", data.recentPublications, function(item) {
            return item.year ? item.year : "";
        });
        renderList("stats-dashboard-top-researchers", data.topResearchers, function(item) {
            var n = typeof item.publications === "number" ? item.publications : 0;
            return formatNumber(n) + (n === 1 ? " publication" : " publications");
        });
        renderList("stats-dashboard-top-organizations", data.topOrganizations, function(item) {
            var n = typeof item.people === "number" ? item.people : 0;
            return formatNumber(n) + (n === 1 ? " person" : " people");
        });
    }

    function show(id, visible) {
        var node = document.getElementById(id);
        if (node) {
            node.style.display = visible ? "" : "none";
        }
    }

    function showContent() {
        show("stats-dashboard-loading", false);
        show("stats-dashboard-error", false);
        show("stats-dashboard-content", true);
    }

    function showError() {
        show("stats-dashboard-loading", false);
        show("stats-dashboard-content", false);
        show("stats-dashboard-error", true);
    }

    function init() {
        var request = new XMLHttpRequest();
        request.open("GET", getConfig().ajaxUrl, true);
        request.onreadystatechange = function() {
            if (request.readyState !== 4) {
                return;
            }
            if (request.status === 200) {
                try {
                    var data = JSON.parse(request.responseText);
                    render(data);
                    showContent();
                } catch (e) {
                    showError();
                }
            } else {
                showError();
            }
        };
        request.send();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();
