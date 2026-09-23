import { prepCanvas } from './util.js';

// Точки MediaPipe Face Mesh для формулы EAR: [p1, p2, p3, p4, p5, p6].
const LEFT_EAR = [33, 160, 158, 133, 153, 144];
const RIGHT_EAR = [362, 385, 387, 263, 373, 380];
// Контуры глаз для отрисовки и замера яркости.
const LEFT_RING = [33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246];
const RIGHT_RING = [263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466];
// Лоб и щёки ниже оправы — эталон яркости кожи.
const SKIN = [151, 205, 425];

const CALIBRATION_FRAMES = 45; // ~3 сек при 15 кадрах/сек
const DEFAULT_THRESHOLD = 0.21;
const BLINK_MS = 300; // короче — это обычное моргание
const HEAD_DROP_DEG = 20; // голова «клюнула» относительно обычного положения
const YAWN_JAW = 0.6;
const YAWN_MS = 1200;
const YAWN_WINDOW_MS = 10 * 60 * 1000;
const YAWNS_FOR_WARNING = 3;

// Тёмные очки: у открытого глаза самые светлые пиксели (белок, веко) почти как кожа,
// тёмная линза гасит всё. Гистерезис, чтобы режим не прыгал.
const DARK_RATIO = 0.75;
const LIGHT_RATIO = 0.8;
const GLASSES_ON = 4;
const GLASSES_OFF = 1;
const GLASSES_MAX = 6;

function eyeAspectRatio(lm, [p1, p2, p3, p4, p5, p6], w, h) {
  const d = (a, b) => Math.hypot((lm[a].x - lm[b].x) * w, (lm[a].y - lm[b].y) * h);
  return (d(p2, p6) + d(p3, p5)) / (2 * d(p1, p4));
}

// Наклон головы вперёд-назад (в градусах) из матрицы позы лица (column-major 4×4).
function pitchDegrees(m) {
  return (Math.atan2(m[6], m[10]) * 180) / Math.PI;
}

const median = (values) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];

/* ---------- Замер яркости глаз относительно кожи ---------- */

const PROBE_MAX = 240; // ширина вырезки лица в пикселях — хватает и быстро
let probeCtx;

const luminance = (px, i) => 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2];

// Отношение самых светлых 10% пикселей глаз к медианной яркости кожи.
function eyeBrightness(video, lm) {
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  const ids = [...LEFT_RING, ...RIGHT_RING, ...SKIN];
  const xs = ids.map((i) => lm[i].x * vw);
  const ys = ids.map((i) => lm[i].y * vh);
  const sx = Math.max(0, Math.min(...xs) - 4);
  const sy = Math.max(0, Math.min(...ys) - 4);
  const sw = Math.min(vw, Math.max(...xs) + 4) - sx;
  const sh = Math.min(vh, Math.max(...ys) + 4) - sy;
  if (sw < 8 || sh < 8) return null;

  const scale = Math.min(1, PROBE_MAX / sw);
  const w = Math.max(1, Math.round(sw * scale));
  const h = Math.max(1, Math.round(sh * scale));
  probeCtx ??= document.createElement('canvas').getContext('2d', { willReadFrequently: true });
  probeCtx.canvas.width = w;
  probeCtx.canvas.height = h;
  probeCtx.drawImage(video, sx, sy, sw, sh, 0, 0, w, h);
  const px = probeCtx.getImageData(0, 0, w, h).data;
  const toProbe = (i) => [(lm[i].x * vw - sx) * scale, (lm[i].y * vh - sy) * scale];

  const eyes = [];
  for (const ring of [LEFT_RING, RIGHT_RING]) {
    const pts = ring.map(toProbe);
    const x0 = Math.max(0, Math.floor(Math.min(...pts.map((p) => p[0]))));
    const x1 = Math.min(w - 1, Math.ceil(Math.max(...pts.map((p) => p[0]))));
    const y0 = Math.max(0, Math.floor(Math.min(...pts.map((p) => p[1]))));
    const y1 = Math.min(h - 1, Math.ceil(Math.max(...pts.map((p) => p[1]))));
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) eyes.push(luminance(px, (y * w + x) * 4));
  }
  // Кожа — медиана пятна 5×5 вокруг каждой точки, чтобы не зависеть от одного пикселя.
  const skin = SKIN.flatMap((i) => {
    const [cx, cy] = toProbe(i).map(Math.round);
    const patch = [];
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++) {
        const x = Math.min(w - 1, Math.max(0, cx + dx));
        const y = Math.min(h - 1, Math.max(0, cy + dy));
        patch.push(luminance(px, (y * w + x) * 4));
      }
    }
    return [median(patch)];
  });
  const skinLevel = median(skin);
  if (eyes.length < 10 || skinLevel < 12) return null; // слишком темно или лицо слишком далеко
  eyes.sort((a, b) => a - b);
  return eyes[Math.floor(eyes.length * 0.9)] / skinLevel;
}

/* ---------- Монитор усталости ---------- */

export class FatigueMonitor {
  constructor(landmarker) {
    this.landmarker = landmarker;
    this.earSamples = [];
    this.pitchSamples = [];
    this.threshold = DEFAULT_THRESHOLD;
    this.basePitch = null;
    this.closedSince = null;
    this.headDownSince = null;
    this.darkScore = 0;
    this.glasses = false;
    this.brightness = null;
    this.frame = 0;
    this.jawSince = null;
    this.yawnCounted = false;
    this.yawns = [];
  }

  get calibrated() {
    return this.earSamples.length >= CALIBRATION_FRAMES;
  }

  dismiss() {
    this.closedSince = null;
    this.headDownSince = null;
  }

  // Порог EAR — 70% от обычной открытости глаз водителя; обычный наклон головы — медиана.
  calibrate(ear, pitch) {
    if (this.calibrated) return;
    this.earSamples.push(ear);
    if (pitch != null) this.pitchSamples.push(pitch);
    if (!this.calibrated) return;
    const sorted = [...this.earSamples].sort((a, b) => a - b);
    const open = sorted[Math.floor(sorted.length * 0.7)];
    this.threshold = Math.min(0.27, Math.max(0.14, open * 0.7));
    if (this.pitchSamples.length) this.basePitch = median(this.pitchSamples);
  }

  updateGlasses(video, lm, mode) {
    // Яркость меряем раз в 5 кадров (~3 раза в секунду) — это дешевле, чем на каждом кадре.
    if (this.frame++ % 5 === 0) {
      this.brightness = eyeBrightness(video, lm);
      if (this.brightness == null) {
        /* нет надёжного замера — режим не меняем */
      } else if (this.brightness < DARK_RATIO) this.darkScore = Math.min(GLASSES_MAX, this.darkScore + 1);
      else if (this.brightness > LIGHT_RATIO) this.darkScore = Math.max(0, this.darkScore - 1);
      if (!this.glasses && this.darkScore >= GLASSES_ON) this.glasses = true;
      else if (this.glasses && this.darkScore <= GLASSES_OFF) this.glasses = false;
    }
    if (mode === 'on') return true;
    if (mode === 'off') return false;
    return this.glasses;
  }

  trackYawn(blendshapes, now) {
    const jaw = blendshapes?.find((c) => c.categoryName === 'jawOpen')?.score ?? 0;
    if (jaw < YAWN_JAW) {
      this.jawSince = null;
      this.yawnCounted = false;
      return false;
    }
    this.jawSince ??= now;
    if (this.yawnCounted || now - this.jawSince < YAWN_MS) return false;
    this.yawnCounted = true;
    this.yawns = [...this.yawns.filter((t) => now - t < YAWN_WINDOW_MS), now];
    return true;
  }

  process(video, now, closedMs, glassesMode = 'auto') {
    const res = this.landmarker.detectForVideo(video, now);
    const lm = res.faceLandmarks?.[0];
    const matrix = res.facialTransformationMatrixes?.[0]?.data;
    let glasses = glassesMode === 'on';
    let pitchDelta = null;
    let yawned = false;

    if (lm) {
      const w = video.videoWidth;
      const h = video.videoHeight;
      const pitch = matrix ? pitchDegrees(matrix) : null;
      this.ear = (eyeAspectRatio(lm, LEFT_EAR, w, h) + eyeAspectRatio(lm, RIGHT_EAR, w, h)) / 2;
      glasses = this.updateGlasses(video, lm, glassesMode);
      // В тёмных очках EAR — догадка модели, для калибровки порога не годится.
      this.calibrate(glasses ? DEFAULT_THRESHOLD / 0.7 : this.ear, pitch);

      if (!glasses && this.ear < this.threshold) this.closedSince ??= now;
      else this.closedSince = null;

      if (pitch != null && this.basePitch != null) {
        pitchDelta = pitch - this.basePitch;
        // Голова опущена — работает и в очках, потому что поза видна всегда.
        if (Math.abs(pitchDelta) >= HEAD_DROP_DEG) {
          this.headDownSince ??= now;
        } else {
          this.headDownSince = null;
          // Медленно подстраиваемся под смену позы водителя.
          this.basePitch += (pitch - this.basePitch) * 0.01;
        }
      }
      yawned = this.trackYawn(res.faceBlendshapes?.[0]?.categories, now);
    } else {
      // Лицо пропало при закрытых глазах или опущенной голове — продолжаем отсчёт.
      this.ear = null;
      glasses = glassesMode === 'on' || (glassesMode === 'auto' && this.glasses);
    }

    const since = (t) => (t == null ? 0 : now - t);
    const eyesClosedFor = since(this.closedSince);
    const headDownFor = since(this.headDownSince);
    const closedFor = Math.max(eyesClosedFor, headDownFor);

    let state;
    if (closedFor >= closedMs) state = 'sleep';
    else if (headDownFor >= BLINK_MS) state = 'headdown';
    else if (eyesClosedFor >= BLINK_MS) state = 'closed';
    else if (!lm) state = 'noface';
    else if (!this.calibrated) state = 'calibrating';
    else state = glasses ? 'glasses' : 'open';

    const recentYawns = this.yawns.filter((t) => now - t < YAWN_WINDOW_MS).length;
    return {
      state,
      closedFor,
      ear: this.ear,
      threshold: this.threshold,
      glasses,
      brightness: this.brightness,
      pitchDelta,
      yawned,
      tooManyYawns: yawned && recentYawns >= YAWNS_FOR_WARNING,
      landmarks: lm ?? null,
    };
  }
}

const STATE_COLOR = {
  open: '#2EE59D',
  glasses: '#22D3EE',
  calibrating: '#22D3EE',
  closed: '#FFB020',
  headdown: '#FFB020',
  sleep: '#FF3B5C',
};

export function drawEyes(canvas, video, landmarks, state, glasses) {
  const { ctx, s, ox, oy, vw, vh } = prepCanvas(canvas, video);
  if (!landmarks) return;
  ctx.strokeStyle = STATE_COLOR[state] ?? '#7F8FAA';
  ctx.lineWidth = 2;
  // В очках рисуем пунктир: глаз не видно, контур — только оценка модели.
  ctx.setLineDash(glasses ? [4, 4] : []);
  for (const ring of [LEFT_RING, RIGHT_RING]) {
    ctx.beginPath();
    ring.forEach((i, k) => {
      const x = ox + landmarks[i].x * vw * s;
      const y = oy + landmarks[i].y * vh * s;
      if (k) ctx.lineTo(x, y);
      else ctx.moveTo(x, y);
    });
    ctx.closePath();
    ctx.stroke();
  }
}
