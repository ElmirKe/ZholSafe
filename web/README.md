# web — monitoring / risk map (plain HTML/CSS/JS)

Static, framework-free front-end (no React/Vue by policy). Stage 0 ships a placeholder page that
calls the server health endpoint. Stage 6 adds the hazard map (Leaflet is the planned lightweight
map library; decision deferred) fed by `GET /api/v1/hazards/nearby` and the WebSocket feed.

Serve during development from any static server, e.g. `python3 -m http.server 8081` from this
directory, with the ZholNet server on port 8080 (CORS/proxy configured in Stage 6).
