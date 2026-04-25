// Fetches the student's top-10 recommendations from the
// session-authenticated /web-api/recommendations endpoint and renders
// them into #recommendations on the dashboard. Loaded as a static
// asset so the page complies with the Iter 1 CSP that forbids inline
// scripts.
(function () {
    'use strict';

    function escapeHtml(s) {
        return String(s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function renderRecommendations(items) {
        var ul = document.getElementById('recommendations');
        var loading = document.getElementById('recommendations-loading');
        if (!ul) {
            return;
        }
        if (loading) {
            loading.style.display = 'none';
        }
        ul.innerHTML = '';
        if (!items || items.length === 0) {
            var empty = document.createElement('li');
            empty.className = 'list-group-item text-muted';
            empty.textContent = 'No recommendations yet — take a quiz to seed your profile.';
            ul.appendChild(empty);
            return;
        }
        items.forEach(function (it) {
            var li = document.createElement('li');
            li.className = 'list-group-item d-flex justify-content-between align-items-center';
            li.innerHTML =
                '<a href="/catalog/' + escapeHtml(it.contentId) + '">' +
                escapeHtml(it.title) + '</a>' +
                '<span>' +
                '  <span class="badge bg-secondary me-1">' + escapeHtml(it.topic) + '</span>' +
                '  <span class="badge bg-light text-dark border me-1">' +
                escapeHtml(it.estMinutes) + ' min</span>' +
                '  <span class="badge bg-info text-dark" title="' +
                escapeHtml(it.reason) + '">Why?</span>' +
                '</span>';
            ul.appendChild(li);
        });
    }

    function failGracefully() {
        var ul = document.getElementById('recommendations');
        var loading = document.getElementById('recommendations-loading');
        if (loading) {
            loading.style.display = 'none';
        }
        if (ul) {
            ul.innerHTML =
                '<li class="list-group-item text-muted">Recommendations unavailable right now.</li>';
        }
    }

    function init() {
        if (!document.getElementById('recommendations')) {
            return;
        }
        fetch('/web-api/recommendations?k=10', { credentials: 'same-origin' })
            .then(function (resp) {
                if (!resp.ok) {
                    throw new Error('recommendations HTTP ' + resp.status);
                }
                return resp.json();
            })
            .then(renderRecommendations)
            .catch(function (err) {
                console.warn('recommendations fetch failed', err);
                failGracefully();
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
