// Builds the Chart.js radar for the student dashboard. Loaded as a
// static asset so the page complies with the Iter 1 CSP that forbids
// inline scripts. Chart data is supplied via JSON-encoded data-*
// attributes on the <canvas> element so the template never embeds
// JavaScript expressions.
(function () {
    'use strict';

    function readJsonAttr(el, name, fallback) {
        var raw = el.getAttribute(name);
        if (raw === null || raw === '') {
            return fallback;
        }
        try {
            return JSON.parse(raw);
        } catch (e) {
            console.warn('dashboard-charts: bad JSON in ' + name, e);
            return fallback;
        }
    }

    function initRadar() {
        var canvas = document.getElementById('masteryRadar');
        if (!canvas || typeof Chart === 'undefined') {
            return;
        }
        var labels = readJsonAttr(canvas, 'data-labels', []);
        var values = readJsonAttr(canvas, 'data-values', []);
        new Chart(canvas, {
            type: 'radar',
            data: {
                labels: labels,
                datasets: [
                    {
                        label: 'Mastery',
                        data: values,
                        backgroundColor: 'rgba(54, 162, 235, 0.2)',
                        borderColor: 'rgba(54, 162, 235, 1)',
                        pointBackgroundColor: 'rgba(54, 162, 235, 1)'
                    }
                ]
            },
            options: {
                responsive: true,
                scales: {
                    r: {
                        suggestedMin: 0,
                        suggestedMax: 1
                    }
                }
            }
        });
    }

    function initSparkline() {
        var canvas = document.getElementById('weeklyActivityChart');
        if (!canvas || typeof Chart === 'undefined') {
            return;
        }
        var endpoint = canvas.getAttribute('data-endpoint');
        if (!endpoint) {
            return;
        }
        fetch(endpoint, { credentials: 'same-origin' })
            .then(function (resp) {
                if (!resp.ok) {
                    throw new Error('weekly-activity HTTP ' + resp.status);
                }
                return resp.json();
            })
            .then(function (buckets) {
                var labels = buckets.map(function (b) { return b.isoYearWeek; });
                var values = buckets.map(function (b) { return b.count; });
                new Chart(canvas, {
                    type: 'line',
                    data: {
                        labels: labels,
                        datasets: [
                            {
                                label: 'Interactions',
                                data: values,
                                borderColor: 'rgba(75, 192, 192, 1)',
                                backgroundColor: 'rgba(75, 192, 192, 0.2)',
                                fill: true,
                                tension: 0.3
                            }
                        ]
                    },
                    options: {
                        responsive: true,
                        scales: {
                            y: {
                                beginAtZero: true,
                                ticks: { precision: 0 }
                            }
                        }
                    }
                });
            })
            .catch(function (err) {
                console.warn('dashboard-charts: weekly fetch failed', err);
            });
    }

    function init() {
        initRadar();
        initSparkline();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
