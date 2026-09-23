// Stage 0: health check only. Relative URL so a dev proxy / same-origin deploy works.
const API_BASE = window.ZHOLNET_API_BASE || "";
async function checkHealth() {
  const el = document.getElementById("status");
  try {
    const r = await fetch(`${API_BASE}/api/v1/health`);
    const j = await r.json();
    el.textContent = `server ${j.status} · contract v${j.contractVersion}`;
    el.className = "badge up";
  } catch (e) {
    el.textContent = "server unreachable";
    el.className = "badge down";
  }
}
checkHealth();
