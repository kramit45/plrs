// View-beacon for student page-views on a content detail page.
// Reads contentId from data-content-id on the body root and posts to
// /web-api/interactions. Externalised from the template so the page
// complies with the CSP that forbids inline scripts.
(function () {
    'use strict';

    var root = document.querySelector('[data-content-id]');
    if (!root) {
        return;
    }
    var contentId = parseInt(root.getAttribute('data-content-id'), 10);
    if (!contentId) {
        return;
    }

    function csrfToken() {
        var name = 'XSRF-TOKEN=';
        var parts = document.cookie.split(';');
        for (var i = 0; i < parts.length; i++) {
            var c = parts[i].trim();
            if (c.indexOf(name) === 0) {
                return decodeURIComponent(c.substring(name.length));
            }
        }
        return '';
    }

    fetch('/web-api/interactions', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': csrfToken()
        },
        body: JSON.stringify({
            contentId: contentId,
            eventType: 'VIEW',
            clientInfo: (navigator.userAgent || '').substring(0, 200)
        })
    }).catch(function () { /* view-logging is best-effort */ });
})();
