(function() {
    'use strict';

    var BASE = '';
    var refreshInterval = null;

    // --- Tab switching ---

    function initTabs() {
        var tabs = document.querySelectorAll('.tab');
        tabs.forEach(function(tab) {
            tab.addEventListener('click', function() {
                tabs.forEach(function(t) { t.classList.remove('active'); });
                tab.classList.add('active');

                document.querySelectorAll('.tab-content').forEach(function(c) {
                    c.classList.remove('active');
                });
                var target = document.getElementById('tab-' + tab.dataset.tab);
                if (target) target.classList.add('active');

                if (tab.dataset.tab === 'requests') {
                    fetchRequestLog();
                }
            });
        });
    }

    // --- Health / Status ---

    function fetchHealth() {
        fetch(BASE + '/_localcloud/health')
            .then(function(r) { return r.json(); })
            .then(function(data) {
                renderHealth(data);
            })
            .catch(function(err) {
                document.getElementById('server-status').textContent = 'Offline';
                document.getElementById('server-status').className = 'status-indicator offline';
                console.error('Health check failed:', err);
            });
    }

    function renderHealth(data) {
        var statusEl = document.getElementById('server-status');
        statusEl.textContent = data.status === 'healthy' ? 'Healthy' : data.status;
        statusEl.className = 'status-indicator ' + (data.status === 'healthy' ? 'online' : 'offline');

        var uptimeEl = document.getElementById('uptime');
        if (data.uptime_seconds !== undefined) {
            var mins = Math.floor(data.uptime_seconds / 60);
            var secs = data.uptime_seconds % 60;
            uptimeEl.textContent = 'Uptime: ' + mins + 'm ' + secs + 's';
        }

        var projectEl = document.getElementById('project-id');
        projectEl.textContent = data.project_id || '';

        renderServiceCards(data.services || {});
    }

    function renderServiceCards(services) {
        var container = document.getElementById('service-cards');
        if (!container) return;

        var html = '';
        var names = Object.keys(services);
        if (names.length === 0) {
            html = '<div class="empty-state">No services registered.</div>';
        } else {
            names.forEach(function(name) {
                var svc = services[name];
                var running = svc.status === 'running';
                html += '<div class="card">';
                html += '<div class="card-header">';
                html += '<span class="dot ' + (running ? 'green' : 'red') + '"></span>';
                html += '<strong>' + escapeHtml(name) + '</strong>';
                html += '</div>';
                html += '<div class="card-body">';
                html += '<div>Status: ' + escapeHtml(svc.status) + '</div>';
                html += '<div>Port: ' + svc.port + '</div>';
                html += '<div>Protocol: ' + escapeHtml(svc.protocol) + '</div>';
                html += '<div>Requests: ' + svc.request_count + '</div>';
                html += '</div>';
                html += '</div>';
            });
        }
        container.innerHTML = html;
    }

    // --- Request Log ---

    function fetchRequestLog() {
        var limitEl = document.getElementById('log-limit');
        var limit = limitEl ? limitEl.value : 50;

        fetch(BASE + '/_localcloud/requests?limit=' + limit)
            .then(function(r) { return r.json(); })
            .then(function(data) {
                renderRequestLog(data.requests || []);
            })
            .catch(function(err) {
                console.error('Failed to fetch request log:', err);
            });
    }

    function renderRequestLog(requests) {
        var tbody = document.getElementById('log-body');
        var emptyEl = document.getElementById('log-empty');
        if (!tbody) return;

        if (requests.length === 0) {
            tbody.innerHTML = '';
            if (emptyEl) emptyEl.style.display = 'block';
            return;
        }

        if (emptyEl) emptyEl.style.display = 'none';

        var html = '';
        requests.forEach(function(req) {
            var statusClass = '';
            if (req.status_code >= 200 && req.status_code < 300) statusClass = 'status-ok';
            else if (req.status_code >= 400 && req.status_code < 500) statusClass = 'status-warn';
            else if (req.status_code >= 500) statusClass = 'status-error';

            var time = req.timestamp ? new Date(req.timestamp).toLocaleTimeString() : '';

            html += '<tr>';
            html += '<td>' + escapeHtml(time) + '</td>';
            html += '<td>' + escapeHtml(req.service || '') + '</td>';
            html += '<td><span class="method">' + escapeHtml(req.method || '') + '</span></td>';
            html += '<td class="path-cell">' + escapeHtml(req.path || '') + '</td>';
            html += '<td><span class="status-badge ' + statusClass + '">' + req.status_code + '</span></td>';
            html += '<td>' + (req.duration_ms || 0) + 'ms</td>';
            html += '</tr>';
        });
        tbody.innerHTML = html;
    }

    // --- Data Browser ---

    function fetchBrowseData(service) {
        var resultEl = document.getElementById('browse-result');
        if (!resultEl) return;

        resultEl.innerHTML = '<div class="loading">Loading...</div>';

        fetch(BASE + '/_localcloud/browse/' + service)
            .then(function(r) { return r.json(); })
            .then(function(data) {
                renderBrowseData(data);
            })
            .catch(function(err) {
                resultEl.innerHTML = '<div class="error-msg">Failed to load data: ' + escapeHtml(err.message) + '</div>';
            });
    }

    function renderBrowseData(data) {
        var resultEl = document.getElementById('browse-result');
        if (!resultEl) return;

        if (data.error) {
            resultEl.innerHTML = '<div class="error-msg">' + escapeHtml(data.message || 'Error') + '</div>';
            return;
        }

        var html = '<pre class="json-display">' + escapeHtml(JSON.stringify(data, null, 2)) + '</pre>';
        resultEl.innerHTML = html;
    }

    // --- Utilities ---

    function escapeHtml(str) {
        if (str === null || str === undefined) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // --- Initialization ---

    function init() {
        initTabs();
        fetchHealth();

        // Auto-refresh every 5 seconds
        refreshInterval = setInterval(function() {
            var activeTab = document.querySelector('.tab.active');
            if (activeTab) {
                if (activeTab.dataset.tab === 'status') {
                    fetchHealth();
                } else if (activeTab.dataset.tab === 'requests') {
                    fetchRequestLog();
                }
            }
        }, 5000);

        // Browse button
        var browseBtn = document.getElementById('browse-btn');
        if (browseBtn) {
            browseBtn.addEventListener('click', function() {
                var select = document.getElementById('service-select');
                if (select) fetchBrowseData(select.value);
            });
        }

        // Refresh log button
        var refreshLogBtn = document.getElementById('refresh-log-btn');
        if (refreshLogBtn) {
            refreshLogBtn.addEventListener('click', fetchRequestLog);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
