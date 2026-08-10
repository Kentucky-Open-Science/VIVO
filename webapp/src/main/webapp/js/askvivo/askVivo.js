/* $This file is distributed under the terms of the license in LICENSE$ */

/* Chat UI for the Ask VIVO page. Expects a global askVivoBaseUrl set by askVivo.ftl. */

(function() {
    'use strict';

    var MAX_HISTORY_SENT = 3;

    var ERROR_MESSAGES = {
        not_configured: 'Ask VIVO is not configured on this server. An administrator must set ' +
            'llm.baseUrl, llm.apiKey and llm.model in runtime.properties and restart VIVO.',
        rate_limited: 'Ask VIVO is receiving too many requests right now. Please wait a minute ' +
            'and try again.',
        llm_error: 'The language model could not be reached or returned an error.',
        invalid_sparql: 'The language model produced a query that could not be run. ' +
            'Try rephrasing your question.',
        query_error: 'The generated query failed to run against the knowledge graph. ' +
            'Try rephrasing your question.',
        empty_question: 'Please type a question first.',
        method_not_allowed: 'The request was rejected. Please reload the page and try again.',
        internal_error: 'Something went wrong on the server. Please try again.'
    };

    var history = [];
    var pending = false;

    var messagesEl;
    var formEl;
    var questionEl;
    var sendEl;

    function init() {
        messagesEl = document.getElementById('askvivo-messages');
        formEl = document.getElementById('askvivo-form');
        questionEl = document.getElementById('askvivo-question');
        sendEl = document.getElementById('askvivo-send');
        if (!messagesEl || !formEl || !questionEl) {
            return;
        }
        formEl.addEventListener('submit', function(event) {
            event.preventDefault();
            submitQuestion();
        });
        questionEl.addEventListener('keydown', function(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                submitQuestion();
            }
        });
        questionEl.focus();
    }

    function submitQuestion() {
        if (pending) {
            return;
        }
        var question = questionEl.value.replace(/\s+/g, ' ').trim();
        if (question === '') {
            return;
        }
        questionEl.value = '';
        addBubble('user').appendChild(textParagraph(question));
        send(question);
    }

    function send(question) {
        setPending(true);
        var loading = addLoadingBubble();

        var params = new URLSearchParams();
        params.set('question', question);
        params.set('history', JSON.stringify(history.slice(-MAX_HISTORY_SENT)));

        fetch(askVivoBaseUrl + '/askVivoAjax', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: params.toString()
        }).then(function(response) {
            return response.json();
        }).then(function(data) {
            removeBubble(loading);
            if (data && data.error) {
                addErrorBubble(data.error, data.detail);
            } else if (data && typeof data.answer === 'string') {
                addAnswerBubble(question, data);
            } else {
                addErrorBubble('internal_error', null);
            }
        }).catch(function() {
            removeBubble(loading);
            addErrorBubble('internal_error', 'The server could not be reached.');
        }).then(function() {
            setPending(false);
            questionEl.focus();
        });
    }

    function setPending(value) {
        pending = value;
        questionEl.disabled = value;
        if (sendEl) {
            sendEl.disabled = value;
        }
    }

    /* ---------------- bubble rendering ---------------- */

    function addBubble(kind) {
        var bubble = document.createElement('div');
        bubble.className = 'askvivo-bubble askvivo-' + kind;
        messagesEl.appendChild(bubble);
        bubble.scrollIntoView({ block: 'nearest' });
        return bubble;
    }

    function removeBubble(bubble) {
        if (bubble && bubble.parentNode) {
            bubble.parentNode.removeChild(bubble);
        }
    }

    function addLoadingBubble() {
        var bubble = addBubble('assistant askvivo-loading');
        var text = document.createElement('span');
        text.textContent = 'Thinking';
        bubble.appendChild(text);
        for (var i = 0; i < 3; i++) {
            var dot = document.createElement('span');
            dot.className = 'askvivo-dot';
            dot.textContent = '.';
            bubble.appendChild(dot);
        }
        return bubble;
    }

    function addErrorBubble(code, detail) {
        var bubble = addBubble('assistant askvivo-error');
        var message = ERROR_MESSAGES[code] || ERROR_MESSAGES.internal_error;
        bubble.appendChild(textParagraph(message));
        if (detail) {
            var detailEl = document.createElement('p');
            detailEl.className = 'askvivo-error-detail';
            detailEl.textContent = detail;
            bubble.appendChild(detailEl);
        }
        bubble.scrollIntoView({ block: 'nearest' });
    }

    function addAnswerBubble(question, data) {
        var bubble = addBubble('assistant');

        String(data.answer).split(/\n\s*\n/).forEach(function(paragraph) {
            var trimmed = paragraph.trim();
            if (trimmed !== '') {
                bubble.appendChild(textParagraph(trimmed));
            }
        });

        if (Array.isArray(data.entities) && data.entities.length > 0) {
            bubble.appendChild(entityChips(data.entities));
        }
        if (typeof data.sparql === 'string' && data.sparql !== '') {
            bubble.appendChild(collapsible('Show SPARQL', sparqlBlock(data.sparql)));
        }
        if (Array.isArray(data.rows) && data.rows.length > 0) {
            bubble.appendChild(collapsible(
                'Show results (' + data.rows.length + ' row' + (data.rows.length === 1 ? '' : 's') + ')',
                resultsTable(data.rows)));
        }

        history.push({ question: question, answer: String(data.answer) });
        if (history.length > 10) {
            history = history.slice(-10);
        }
        bubble.scrollIntoView({ block: 'nearest' });
    }

    /* ---------------- element builders ---------------- */

    function textParagraph(text) {
        var p = document.createElement('p');
        p.textContent = text;
        return p;
    }

    function collapsible(summaryText, contentEl) {
        var details = document.createElement('details');
        var summary = document.createElement('summary');
        summary.textContent = summaryText;
        details.appendChild(summary);
        details.appendChild(contentEl);
        return details;
    }

    function sparqlBlock(sparql) {
        var pre = document.createElement('pre');
        pre.className = 'askvivo-sparql';
        pre.textContent = sparql;
        return pre;
    }

    function entityChips(entities) {
        var container = document.createElement('div');
        container.className = 'askvivo-entities';
        entities.forEach(function(entity) {
            if (!entity || !entity.uri || !entity.label) {
                return;
            }
            var chip = document.createElement('a');
            chip.className = 'askvivo-chip';
            chip.href = askVivoBaseUrl + '/individual?uri=' + encodeURIComponent(entity.uri);
            chip.textContent = entity.label;
            container.appendChild(chip);
        });
        return container;
    }

    function resultsTable(rows) {
        var columns = [];
        rows.forEach(function(row) {
            Object.keys(row).forEach(function(key) {
                if (columns.indexOf(key) === -1) {
                    columns.push(key);
                }
            });
        });

        var wrapper = document.createElement('div');
        wrapper.className = 'askvivo-table-wrapper';
        var table = document.createElement('table');
        table.className = 'askvivo-table';

        var thead = document.createElement('thead');
        var headRow = document.createElement('tr');
        columns.forEach(function(column) {
            var th = document.createElement('th');
            th.textContent = column;
            headRow.appendChild(th);
        });
        thead.appendChild(headRow);
        table.appendChild(thead);

        var tbody = document.createElement('tbody');
        rows.forEach(function(row) {
            var tr = document.createElement('tr');
            columns.forEach(function(column) {
                var td = document.createElement('td');
                var value = row[column];
                if (typeof value === 'string' && /^https?:\/\//.test(value)) {
                    var link = document.createElement('a');
                    link.href = askVivoBaseUrl + '/individual?uri=' + encodeURIComponent(value);
                    link.textContent = value;
                    td.appendChild(link);
                } else {
                    td.textContent = (value === undefined || value === null) ? '' : String(value);
                }
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);
        wrapper.appendChild(table);
        return wrapper;
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
