// Renders the admin /admin/dashboard line charts from data-* attrs on
// the canvas elements (CSP forbids inline scripts so the bootstrap
// lives in this static asset).
(function () {
    'use strict';

    function parseList(raw) {
        if (!raw) {
            return [];
        }
        return raw.split(',').filter(function (s) { return s.length > 0; });
    }

    function lineChart(canvasId, label) {
        var canvas = document.getElementById(canvasId);
        if (!canvas) {
            return;
        }
        var labels = parseList(canvas.dataset.labels);
        var values = parseList(canvas.dataset.values).map(parseFloat);
        if (labels.length === 0) {
            // Empty state — render a placeholder caption rather than an
            // empty axis grid.
            canvas.replaceWith(
                Object.assign(document.createElement('p'), {
                    className: 'text-muted small mb-0',
                    textContent: 'No data yet.'
                })
            );
            return;
        }
        new Chart(canvas.getContext('2d'), {
            type: 'line',
            data: {
                labels: labels,
                datasets: [{
                    label: label,
                    data: values,
                    fill: false,
                    borderWidth: 2,
                    tension: 0.2
                }]
            },
            options: {
                responsive: true,
                plugins: { legend: { display: false } },
                scales: { y: { beginAtZero: true } }
            }
        });
    }

    function init() {
        lineChart('completionLineChart', 'Completion rate');
        lineChart('ratingLineChart', 'Avg rating');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
