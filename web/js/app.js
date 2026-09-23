import { loadFace, loadDetector } from './models.js';
import { FatigueMonitor, drawEyes } from './fatigue.js';
import { RoadMonitor, drawHazards } from './road.js';
import { openDriverCamera, openRoadCamera, stopStream } from './cameras.js';
import { settings, bindSettings } from './settings.js';
import { FatigueIndex, FACTOR_LABELS } from './fatigue-index.js';
import { MotionMonitor } from './motion.js';
import { fmtDuration, fmtDistance } from './util.js';
import * as alarm from './alarm.js';
import * as geo from './map.js';

const $ = (sel) => document.querySelector(sel);
const els = {
  idle: $('#drive-idle'),
  drive: $('#drive-active'),
  start: $('#btn-start'),
  stop: $('#btn-stop'),
  status: $('#start-status'),
  stage: $('#stage'),
  driverVideo: $('#driver-video'),
  driverCanvas: $('#driver-canvas'),
  driverEmpty: $('#driver-empty'),
  roadVideo: $('#road-video'),
  roadCanvas: $('#road-canvas'),
  roadEmpty: $('#road-empty'),
  pillFatigue: $('#pill-fatigue'),
  pillEyes: $('#pill-eyes'),
  pillRoad: $('#pill-road'),
  pillZone: $('#pill-zone'),
  banner: $('#road-banner'),
  bannerText: $('#road-banner-text'),
  alarmSleep: $('#alarm-sleep'),
  stTime: $('#st-time'),
  stFatigue: $('#st-fatigue'),
  sideFatigue: $('#side-fatigue'),
  sideFatigueTag: $('#side-fatigue-tag'),
  fatigueFill: $('#fatigue-fill'),
  factors: $('#factors'),
  sideMotion: $('#side-motion'),
  demoRow: $('#demo-row'),
  stRoad: $('#st-road'),
  sideEyes: $('#side-eyes'),
  sideEyesTag: $('#side-eyes-tag'),
  sideEar: $('#side-ear'),
  sideThr: $('#side-thr'),
  sideMode: $('#side-mode'),
  sidePitch: $('#side-pitch'),
  sideYawns: $('#side-yawns'),
  earFill: $('#ear-fill'),
  earMark: $('#ear-mark'),
  sideRoad: $('#side-road'),
  sideRoadTag: $('#side-road-tag'),
  sideDist: $('#side-dist'),
  sideDriveMode: $('#side-drive-mode'),
  sideScore: $('#side-score'),
  feed: $('#feed'),
  sheet: $('#report-sheet'),
  toast: $('#toast'),
  clock: $('#clock'),
};

const trip = { active: false };
let fatigue;
let road;

/* ---------- Общие мелочи ---------- */

let toastTimer;
function toast(message, ms = 3500) {
  els.toast.textContent = message;
  els.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (els.toast.hidden = true), ms);
}

function setPill(el, level, text) {
  el.dataset.level = level;
  el.querySelector('.txt').textContent = text;
}

function setTag(el, level, text) {
  el.dataset.level = level;
  el.textContent = text;
}

function logEvent(level, text) {
  els.feed.querySelector('.empty')?.remove();
  const li = document.createElement('li');
  li.dataset.level = level;
  const time = document.createElement('time');
  time.textContent = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const span = document.createElement('span');
  span.textContent = text;
  li.append(time, span);
  els.feed.prepend(li);
  while (els.feed.children.length > 30) els.feed.lastElementChild.remove();
}

const updateClock = () =>
  (els.clock.textContent = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }));
updateClock();
setInterval(updateClock, 10000);

/* ---------- Вкладки и настройки ---------- */

function openTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.id === `tab-${name}`));
  document.querySelectorAll('.tabs [data-tab]').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  if (name === 'map') geo.initMap($('#map'));
}
document.querySelectorAll('.tabs [data-tab]').forEach((b) => b.addEventListener('click', () => openTab(b.dataset.tab)));
bindSettings(document);

/* ---------- Модели ---------- */

let models;
function ensureModels() {
  models ??= Promise.all([loadFace(), loadDetector()]).catch((err) => {
    models = null;
    throw err;
  });
  return models;
}
ensureModels()
  .then(() => (els.status.textContent = 'Готово. Модели работают прямо на устройстве.'))
  .catch(() => (els.status.textContent = 'Модели загрузятся при старте'));

/* ---------- Камеры и видео ---------- */

const hasRoad = () => Boolean(trip.roadStream || trip.roadFileUrl);

function attach(video, stream) {
  video.srcObject = stream ?? null;
  if (stream) video.play().catch(() => {});
}

function updatePlaceholders() {
  els.roadEmpty.hidden = hasRoad();
  els.driverEmpty.hidden = Boolean(trip.driverStream);
}

async function openCameras() {
  if (!navigator.mediaDevices?.getUserMedia) {
    toast('Камера недоступна. Откройте сайт по https.');
    return;
  }
  const mode = settings.cameraMode;
  if (mode !== 'road') {
    try {
      trip.driverStream = await openDriverCamera();
    } catch (err) {
      console.warn('Фронтальная камера недоступна', err);
    }
  }
  if (mode !== 'driver') {
    const excludeId = trip.driverStream?.getVideoTracks()[0]?.getSettings().deviceId;
    trip.roadStream = await openRoadCamera(excludeId, mode === 'road' || !trip.driverStream);
  }
  // iPhone и часть Android выключают первую камеру, когда открывается вторая.
  if (trip.driverStream && trip.roadStream) {
    await new Promise((r) => setTimeout(r, 400));
    const track = trip.driverStream.getVideoTracks()[0];
    if (track.readyState === 'ended' || track.muted) {
      stopStream(trip.driverStream);
      trip.driverStream = null;
      toast('Телефон не даёт включить две камеры сразу — работает камера дороги. Режим можно сменить в настройках.', 6000);
    }
  }
  attach(els.driverVideo, trip.driverStream);
  attach(els.roadVideo, trip.roadStream);
}

document.querySelectorAll('.road-file').forEach((input) =>
  input.addEventListener('change', () => {
    const file = input.files[0];
    input.value = '';
    if (!file) return;
    stopStream(trip.roadStream);
    trip.roadStream = null;
    if (trip.roadFileUrl) URL.revokeObjectURL(trip.roadFileUrl);
    trip.roadFileUrl = URL.createObjectURL(file);
    const v = els.roadVideo;
    v.srcObject = null;
    v.src = trip.roadFileUrl;
    v.loop = true;
    v.play().catch(() => {});
    els.stage.dataset.main = 'road';
    updatePlaceholders();
    logEvent('info', 'Демо: видео трассы');
  }),
);

// Нажатие на маленькое окно меняет камеры местами.
els.stage.addEventListener('click', (e) => {
  const view = e.target.closest('.view');
  if (view && view.dataset.view !== els.stage.dataset.main && !e.target.closest('label')) {
    els.stage.dataset.main = view.dataset.view;
  }
});

/* ---------- Поездка ---------- */

async function keepAwake() {
  try {
    trip.wakeLock = await navigator.wakeLock?.request('screen');
  } catch {
    /* не поддерживается — экран может погаснуть */
  }
}
document.addEventListener('visibilitychange', () => {
  if (trip.active && document.visibilityState === 'visible') keepAwake();
});

function watchPosition() {
  if (!navigator.geolocation) return;
  trip.geoWatch = navigator.geolocation.watchPosition(
    (p) => {
      trip.pos = { lat: p.coords.latitude, lng: p.coords.longitude };
      trip.speed = p.coords.speed;
    },
    () => {},
    { enableHighAccuracy: true, maximumAge: 10000 },
  );
}

async function startTrip() {
  alarm.unlock();
  const motionAllowed = MotionMonitor.requestPermission();
  els.start.disabled = true;
  els.status.textContent = 'Загружаем ИИ-модели…';
  let face;
  let detector;
  try {
    [face, detector] = await ensureModels();
  } catch (err) {
    console.error(err);
    els.status.textContent = 'Не удалось загрузить модели — проверьте интернет';
    els.start.disabled = false;
    return;
  }
  fatigue = new FatigueMonitor(face);
  road = new RoadMonitor(detector);

  els.status.textContent = 'Включаем камеры…';
  await openCameras();

  Object.assign(trip, {
    active: true,
    startedAt: Date.now(),
    microsleeps: 0,
    hazards: 0,
    sleeping: false,
    roadAlert: false,
    lastNear: -Infinity,
    near: null,
    lastFaceT: 0,
    lastRoadT: 0,
    lastTick: 0,
    lastSleepBeep: 0,
    lastRoadBeep: 0,
    lastReportAt: -Infinity,
    zoneKey: null,
    yawns: 0,
    glasses: false,
    pos: null,
    eyeLevel: 'ok',
    roadLevel: 'ok',
    prevEyeState: null,
    speed: null,
    index: new FatigueIndex(performance.now()),
    fatigueLevel: 'ok',
    lastFatigueSay: -Infinity,
    // Без GPS (ноутбук на демо) остаёмся в режиме «Трасса».
    mode: settings.driveMode === 'city' ? 'city' : 'highway',
    autoMode: 'highway',
    slowSince: null,
    fastSince: null,
  });

  // В городе повороты и перестроения — это не виляние, не считаем.
  trip.motion = new MotionMonitor(
    () => trip.mode === 'highway' && fatigueEvent('weave', 'Виляние: резкая коррекция руля'),
    () => trip.speed,
  );
  // Подписываемся всегда: без разрешения данных просто не будет (статус виден в панели).
  trip.motion.start();
  motionAllowed.then((ok) => ok || logEvent('info', 'Нет доступа к датчикам движения'));
  els.demoRow.hidden = !settings.showDemo;
  renderFatigue({ score: 0, level: 'ok', factors: [] });

  els.feed.innerHTML = '<li class="empty">Событий пока нет</li>';
  els.sideYawns.textContent = '0';
  els.stage.dataset.main = hasRoad() || !trip.driverStream ? 'road' : 'driver';
  updatePlaceholders();
  setPill(els.pillEyes, 'idle', trip.driverStream ? 'Глаза: поиск лица…' : 'Камера водителя выкл.');
  setPill(els.pillRoad, 'idle', hasRoad() ? 'Дорога: анализ…' : 'Нет видео дороги');
  els.pillZone.hidden = true;
  document.body.classList.add('driving');
  els.idle.hidden = true;
  els.drive.hidden = false;
  els.start.disabled = false;
  els.status.textContent = '';
  logEvent('ok', 'Поездка началась');
  if (!trip.driverStream && !hasRoad()) toast('Нет доступа к камерам. Разрешите камеру или загрузите видео трассы.', 6000);

  keepAwake();
  watchPosition();
  requestAnimationFrame(loop);
}

function stopTrip() {
  trip.active = false;
  stopStream(trip.driverStream);
  stopStream(trip.roadStream);
  trip.driverStream = trip.roadStream = null;
  for (const v of [els.driverVideo, els.roadVideo]) {
    v.pause();
    v.srcObject = null;
    v.removeAttribute('src');
    v.load();
  }
  if (trip.roadFileUrl) URL.revokeObjectURL(trip.roadFileUrl);
  trip.roadFileUrl = null;
  trip.wakeLock?.release().catch(() => {});
  trip.wakeLock = null;
  if (trip.geoWatch != null) navigator.geolocation.clearWatch(trip.geoWatch);
  trip.geoWatch = null;
  trip.motion?.stop();
  alarm.silence();
  els.alarmSleep.hidden = true;
  els.banner.hidden = true;
  document.body.classList.remove('driving');
  els.drive.hidden = true;
  els.idle.hidden = false;
  toast(
    `Поездка ${fmtDuration(Date.now() - trip.startedAt)} · макс. усталость: ${trip.index.max} · микросон: ${trip.microsleeps} · опасности: ${trip.hazards}`,
    6000,
  );
}

els.start.addEventListener('click', startTrip);
els.stop.addEventListener('click', stopTrip);
els.alarmSleep.addEventListener('click', () => {
  fatigue?.dismiss();
  els.alarmSleep.hidden = true;
});

/* ---------- Главный цикл ---------- */

function loop() {
  if (!trip.active) return;
  const now = performance.now();

  if (trip.driverStream && els.driverVideo.readyState >= 2 && now - trip.lastFaceT >= 66) {
    trip.lastFaceT = now;
    renderDriver(fatigue.process(els.driverVideo, now, settings.closedSec * 1000, settings.glassesMode), now);
  }
  if (hasRoad() && els.roadVideo.readyState >= 2 && now - trip.lastRoadT >= 100) {
    trip.lastRoadT = now;
    renderRoad(road.process(els.roadVideo, now, settings.minScore), now);
  }
  if (now - trip.lastTick >= 1000) {
    trip.lastTick = now;
    tick();
  }

  const levels = [trip.eyeLevel, trip.roadLevel, trip.fatigueLevel, els.pillZone.hidden ? 'ok' : 'warn'];
  els.drive.dataset.level = levels.includes('danger') ? 'danger' : levels.includes('warn') ? 'warn' : 'ok';
  requestAnimationFrame(loop);
}

const EYE_STATES = {
  noface: ['idle', 'Лицо не видно', '—'],
  calibrating: ['info', 'Калибровка глаз…', 'КАЛИБРОВКА'],
  open: ['ok', 'Глаза открыты', 'НОРМА'],
  glasses: ['ok', 'Очки: слежу за головой', 'ОЧКИ'],
  closed: ['warn', 'Глаза закрыты', 'ВНИМАНИЕ'],
  headdown: ['warn', 'Голова опущена', 'ВНИМАНИЕ'],
  sleep: ['danger', 'Микросон!', 'ТРЕВОГА'],
};

function renderDriver(r, now) {
  drawEyes(els.driverCanvas, els.driverVideo, r.landmarks, r.state, r.glasses);

  const [level, text, tag] = EYE_STATES[r.state];
  const timed = r.state === 'closed' || r.state === 'headdown';
  const label = timed ? `${text} ${(r.closedFor / 1000).toFixed(1).replace('.', ',')} с` : text;
  setPill(els.pillEyes, level, label);
  els.sideEyes.textContent = label;
  setTag(els.sideEyesTag, level, tag);
  els.sideEar.textContent = r.ear == null ? '—' : r.ear.toFixed(3);
  els.sideThr.textContent = r.threshold.toFixed(3);
  const pct = (v) => `${Math.min(100, (v / 0.4) * 100)}%`;
  els.earFill.style.width = r.ear == null ? '0' : pct(r.ear);
  els.earFill.style.background = `var(--${level === 'idle' || level === 'info' ? 'accent' : level})`;
  els.earMark.style.left = pct(r.threshold);
  els.sideMode.textContent = r.glasses ? 'Очки · по голове' : 'По глазам';
  els.sidePitch.textContent = r.pitchDelta == null ? '—' : `${r.pitchDelta > 0 ? '+' : ''}${Math.round(r.pitchDelta)}°`;

  if (r.glasses !== trip.glasses) {
    trip.glasses = r.glasses;
    logEvent('info', r.glasses ? 'Тёмные очки: слежу за наклоном головы' : 'Глаза снова видны');
  }
  if (r.yawned) {
    trip.index.add('yawn', now);
    trip.yawns++;
    els.sideYawns.textContent = trip.yawns;
    logEvent('warn', 'Зевание');
    if (r.tooManyYawns) alarm.say('Вы часто зеваете. Сделайте перерыв.', settings);
  }
  trip.eyeLevel = level === 'danger' ? 'danger' : level === 'warn' ? 'warn' : 'ok';

  trip.index.recordHead(r.yaw, now);
  if (r.state !== trip.prevEyeState) {
    if (r.state === 'headdown') fatigueEvent('nod', 'Кивок головы');
    if (r.state === 'closed' && !r.glasses) fatigueEvent('longblink', 'Долгое моргание');
  }
  trip.prevEyeState = r.state;

  const sleeping = r.state === 'sleep';
  els.alarmSleep.hidden = !sleeping;
  if (sleeping && !trip.sleeping) {
    trip.microsleeps++;
    trip.index.add('microsleep', now);
    logEvent('danger', 'Микросон водителя');
    alarm.say('Проснитесь! Остановитесь и отдохните.', settings);
  }
  if (sleeping && now - trip.lastSleepBeep >= 800) {
    trip.lastSleepBeep = now;
    alarm.siren(settings);
  }
  trip.sleeping = sleeping;
}

const SAY = {
  livestock: 'Внимание! Скот на дороге. Сбавьте скорость.',
  person: 'Внимание! Человек на дороге. Сбавьте скорость.',
};
const describe = (h) => (h.kind === 'livestock' ? `${h.species}, ≈${h.distance} м` : `≈${h.distance} м`);

const CITY_WARN_M = 50;
const CITY_PERSON_M = 30;
const MODE_LABEL = { highway: 'Трасса', city: 'Город' };

function updateDriveMode(now) {
  let mode = settings.driveMode;
  if (mode === 'auto') {
    const v = trip.speed; // м/с из GPS
    if (v != null) {
      if (v < 13.9) {
        trip.slowSince ??= now;
        trip.fastSince = null;
        if (now - trip.slowSince > 20000) trip.autoMode = 'city';
      } else if (v > 16.7) {
        trip.fastSince ??= now;
        trip.slowSince = null;
        if (now - trip.fastSince > 30000) trip.autoMode = 'highway';
      } else {
        trip.slowSince = trip.fastSince = null;
      }
    }
    mode = trip.autoMode;
  }
  if (mode !== trip.mode) {
    trip.mode = mode;
    logEvent('info', `Режим «${MODE_LABEL[mode]}»`);
  }
  els.sideDriveMode.textContent = MODE_LABEL[trip.mode] + (settings.driveMode === 'auto' ? ' · авто' : '');
}

function renderRoad(allHazards, now) {
  const city = trip.mode === 'city';
  const warnDistance = city ? CITY_WARN_M : settings.warnDistance;
  // В городе люди на тротуарах — не опасность: учитываем только тех, кто на полосе и близко.
  for (const h of allHazards) h.ignored = city && h.kind === 'person' && (!h.inPath || h.distance > CITY_PERSON_M);
  const hazards = allHazards.filter((h) => !h.ignored);
  drawHazards(els.roadCanvas, els.roadVideo, allHazards, warnDistance, city);

  const prefix = `${MODE_LABEL[trip.mode]} · `;
  const nearest = hazards[0];
  if (nearest && nearest.distance <= warnDistance) {
    trip.lastNear = now;
    trip.near = nearest;
  }
  // Держим тревогу ещё секунду, чтобы она не мигала между кадрами.
  const alerting = now - trip.lastNear < 1200;
  const shown = alerting ? trip.near : nearest;

  if (alerting) {
    const text = `${shown.label} на дороге · ${describe(shown)}`;
    els.banner.hidden = false;
    els.banner.dataset.kind = shown.kind;
    els.bannerText.textContent = text;
    setPill(els.pillRoad, 'danger', `${prefix}${shown.label} · ≈${shown.distance} м`);
    // Новая тревога — или тип опасности сменился (был скот, появился человек).
    if (!trip.roadAlert || trip.alertKind !== shown.kind) {
      trip.hazards++;
      trip.alertKind = shown.kind;
      logEvent('danger', text);
      alarm.say(SAY[shown.kind], settings);
      autoReport(shown, now);
    }
    if (now - trip.lastRoadBeep >= 1500) {
      trip.lastRoadBeep = now;
      alarm.chime(settings);
    }
  } else {
    els.banner.hidden = true;
    setPill(els.pillRoad, nearest ? 'warn' : 'ok', prefix + (nearest ? `${nearest.label} · ≈${nearest.distance} м` : 'дорога чистая'));
    trip.alertKind = null;
  }
  trip.roadAlert = alerting;
  trip.roadLevel = alerting ? 'danger' : nearest ? 'warn' : 'ok';

  const level = trip.roadLevel;
  els.sideRoad.textContent = shown ? (shown.kind === 'livestock' ? `Скот: ${shown.species}` : 'Человек') : 'Дорога чистая';
  setTag(els.sideRoadTag, level, level === 'danger' ? 'ОПАСНО' : level === 'warn' ? 'ВИЖУ' : 'ЧИСТО');
  els.sideDist.textContent = shown ? `≈${shown.distance} м` : '—';
  els.sideScore.textContent = shown ? `${Math.round(shown.score * 100)}%` : '—';
}

function autoReport(hazard, now) {
  if (!settings.autoReport || hazard.kind !== 'livestock' || !trip.pos || now - trip.lastReportAt < 60000) return;
  trip.lastReportAt = now;
  geo.addReport({ ...trip.pos, type: hazard.key, source: 'auto' });
  logEvent('info', 'Скот отмечен на карте');
}

function tick() {
  els.stTime.textContent = fmtDuration(Date.now() - trip.startedAt);
  updateDriveMode(performance.now());
  updateFatigue(performance.now());
  els.sideMotion.textContent = trip.motion?.available ? 'работают' : 'нет данных';
  els.stRoad.textContent = trip.hazards;

  if (!trip.pos) return;
  const zone = geo.nearestHotspot(trip.pos.lat, trip.pos.lng);
  if (zone && zone.d <= 3000) {
    els.pillZone.hidden = false;
    setPill(els.pillZone, 'warn', `Зона выпаса · ${fmtDistance(zone.d)}`);
    const key = `${zone.lat},${zone.lng}`;
    if (trip.zoneKey !== key) {
      trip.zoneKey = key;
      logEvent('warn', `Зона выпаса скота через ${fmtDistance(zone.d)}`);
      alarm.say('Впереди участок, где часто выходит скот. Снизьте скорость.', settings);
    }
  } else {
    els.pillZone.hidden = true;
    trip.zoneKey = null;
  }
}

/* ---------- Индекс усталости ---------- */

function fatigueEvent(type, text) {
  if (!trip.active) return;
  const now = performance.now();
  trip.index.add(type, now);
  logEvent('warn', text);
  updateFatigue(now);
}

const FATIGUE_TAGS = { ok: 'НОРМА', warn: 'УСТАЛОСТЬ', danger: 'ОПАСНО' };

function renderFatigue({ score, level, factors }) {
  const top = factors[0] ? ` · ${FACTOR_LABELS[factors[0].key].toLowerCase()}` : '';
  setPill(els.pillFatigue, level, `Усталость ${score}${top}`);
  els.stFatigue.textContent = score;
  els.sideFatigue.textContent = score;
  setTag(els.sideFatigueTag, level, FATIGUE_TAGS[level]);
  els.fatigueFill.style.width = `${score}%`;
  els.fatigueFill.style.background = `var(--${level})`;
  const rows = factors.map((f) => {
    const li = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = FACTOR_LABELS[f.key] + (f.count > 1 ? ` ×${f.count}` : '');
    const pts = document.createElement('b');
    pts.textContent = `+${f.points}`;
    li.append(name, pts);
    return li;
  });
  if (!rows.length) rows.push(Object.assign(document.createElement('li'), { className: 'muted', textContent: 'Признаков усталости нет' }));
  els.factors.replaceChildren(...rows);
}

function updateFatigue(now) {
  const result = trip.index.compute(now);
  renderFatigue(result);
  const { level } = result;
  const worse = level === 'danger' ? trip.fatigueLevel !== 'danger' : level === 'warn' && trip.fatigueLevel === 'ok';
  // Голосом — при ухудшении и потом не чаще раза в 3 минуты, чтобы не надоедать.
  if (level !== 'ok' && (worse || now - trip.lastFatigueSay > 3 * 60 * 1000)) {
    trip.lastFatigueSay = now;
    if (level === 'danger') {
      alarm.siren(settings);
      alarm.say('Высокий риск уснуть. Остановитесь и отдохните.', settings);
      logEvent('danger', `Индекс усталости ${result.score}`);
    } else {
      alarm.chime(settings);
      alarm.say('Признаки усталости. Сделайте перерыв на ближайшей стоянке.', settings);
      logEvent('warn', `Индекс усталости ${result.score}`);
    }
  }
  trip.fatigueLevel = level;
}

const DEMO = {
  weave: () => fatigueEvent('weave', 'Демо: виляние машины'),
  nod: () => fatigueEvent('nod', 'Демо: кивок головы'),
  yawn: () => fatigueEvent('yawn', 'Демо: зевание'),
  time: () => {
    trip.index.timeOffset += 2 * 60 * 60 * 1000;
    logEvent('info', 'Демо: +2 часа в пути');
    updateFatigue(performance.now());
  },
};
els.demoRow.querySelectorAll('[data-demo]').forEach((b) =>
  b.addEventListener('click', (e) => {
    e.stopPropagation();
    DEMO[b.dataset.demo]();
  }),
);

/* ---------- Отметка скота на карте ---------- */

function currentPosition() {
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lng: p.coords.longitude }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 },
    );
  });
}

$('#btn-report').addEventListener('click', () => (els.sheet.hidden = false));
$('#report-cancel').addEventListener('click', () => (els.sheet.hidden = true));
els.sheet.addEventListener('click', (e) => {
  if (e.target === els.sheet) els.sheet.hidden = true;
});
els.sheet.querySelectorAll('[data-type]').forEach((btn) =>
  btn.addEventListener('click', async () => {
    els.sheet.hidden = true;
    const pos = await currentPosition();
    const where = pos ?? geo.center();
    geo.addReport({ ...where, type: btn.dataset.type, source: 'manual' });
    geo.focus(where.lat, where.lng);
    toast(pos ? 'Спасибо! Отметка добавлена' : 'Геолокация недоступна — отметка поставлена в центре карты');
  }),
);
