import { haversine } from './util.js';

export const TYPES = { horse: 'Лошади', cow: 'Коровы', camel: 'Верблюды', sheep: 'Овцы' };

// Демо-данные: участки республиканских трасс, где часто выходит скот.
const HOTSPOTS = [
  { lat: 43.87, lng: 77.07, type: 'horse', reports: 14, road: 'Алматы — Астана, р-н Конаева' },
  { lat: 44.2, lng: 76.25, type: 'horse', reports: 18, road: 'Алматы — Астана, Куртинский участок' },
  { lat: 45.02, lng: 75.72, type: 'cow', reports: 9, road: 'Алматы — Астана' },
  { lat: 45.95, lng: 75.25, type: 'horse', reports: 12, road: 'Алматы — Астана' },
  { lat: 46.75, lng: 75.0, type: 'camel', reports: 6, road: 'Алматы — Астана, р-н Балхаша' },
  { lat: 47.75, lng: 74.25, type: 'horse', reports: 11, road: 'Балхаш — Караганда' },
  { lat: 48.85, lng: 73.55, type: 'cow', reports: 8, road: 'Балхаш — Караганда' },
  { lat: 50.35, lng: 72.25, type: 'cow', reports: 7, road: 'Караганда — Астана' },
  { lat: 50.85, lng: 71.75, type: 'horse', reports: 5, road: 'Караганда — Астана' },
  { lat: 43.3, lng: 68.3, type: 'sheep', reports: 8, road: 'Шымкент — Самара, р-н Туркестана' },
  { lat: 44.2, lng: 66.8, type: 'camel', reports: 13, road: 'Шымкент — Самара, р-н Шиели' },
  { lat: 44.95, lng: 65.3, type: 'camel', reports: 16, road: 'Шымкент — Самара, р-н Кызылорды' },
  { lat: 45.76, lng: 62.1, type: 'camel', reports: 19, road: 'Шымкент — Самара, р-н Казалы' },
  { lat: 46.85, lng: 61.7, type: 'camel', reports: 15, road: 'Шымкент — Самара, р-н Аральска' },
  { lat: 49.9, lng: 60.15, type: 'horse', reports: 9, road: 'Шымкент — Самара, р-н Карабутака' },
  { lat: 50.25, lng: 57.6, type: 'cow', reports: 6, road: 'Шымкент — Самара, р-н Актобе' },
];

const KEY = 'zholsafe.reports';
const COLORS = { danger: '#FF3B5C', warn: '#FFB020', accent: '#22D3EE' };

let map;
let userLayer;

export function loadReports() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || [];
  } catch {
    return [];
  }
}

function saveReports(reports) {
  try {
    localStorage.setItem(KEY, JSON.stringify(reports));
  } catch {
    /* хранилище недоступно — отметка останется только до перезагрузки */
  }
}

function addHotspotMarker(h) {
  const color = h.reports >= 10 ? COLORS.danger : COLORS.warn;
  L.circleMarker([h.lat, h.lng], {
    radius: 7 + Math.min(h.reports, 20) / 2,
    color,
    fillColor: color,
    fillOpacity: 0.3,
    weight: 2,
  })
    .bindPopup(`<b>${TYPES[h.type]}</b><br>${h.road}<br>${h.reports} сообщений · чаще ночью`)
    .addTo(map);
}

function addUserMarker(r) {
  const when = new Date(r.time).toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' });
  L.circleMarker([r.lat, r.lng], { radius: 8, color: COLORS.accent, fillColor: COLORS.accent, fillOpacity: 0.6, weight: 2 })
    .bindPopup(`<b>${TYPES[r.type] ?? 'Скот'}</b><br>${r.source === 'auto' ? 'Отмечено камерой' : 'Ваша отметка'}<br>${when}`)
    .addTo(userLayer);
}

export function initMap(el) {
  if (map) {
    map.invalidateSize();
    return;
  }
  if (!window.L) {
    el.innerHTML = '<p class="map-error">Карта не загрузилась — проверьте интернет.</p>';
    return;
  }
  map = L.map(el, { zoomControl: false }).setView([47.2, 68.5], 5);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap',
    className: 'dark-tiles',
  }).addTo(map);
  HOTSPOTS.forEach(addHotspotMarker);
  userLayer = L.layerGroup().addTo(map);
  loadReports().forEach(addUserMarker);
}

export function addReport(report) {
  const record = { ...report, time: Date.now() };
  saveReports([...loadReports(), record]);
  if (map) addUserMarker(record);
}

export function center() {
  const c = map?.getCenter();
  return c ? { lat: c.lat, lng: c.lng } : { lat: 43.24, lng: 76.9 };
}

export function focus(lat, lng) {
  map?.setView([lat, lng], 11);
}

export function nearestHotspot(lat, lng) {
  let best = null;
  for (const h of [...HOTSPOTS, ...loadReports()]) {
    const d = haversine(lat, lng, h.lat, h.lng);
    if (!best || d < best.d) best = { ...h, d };
  }
  return best;
}
