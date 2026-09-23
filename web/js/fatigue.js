import { prepCanvas } from './util.js';

// Точки MediaPipe Face Mesh для формулы EAR: [p1, p2, p3, p4, p5, p6].
const LEFT_EAR = [33, 160, 158, 133, 153, 144];
const RIGHT_EAR = [362, 385, 387, 263, 373, 380];
// Контуры глаз для отрисовки.
const LEFT_RING = [33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246];
const RIGHT_RING = [263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466];

const CALIBRATION_FRAMES = 45; // ~3 сек при 15 кадрах/сек
const DEFAULT_THRESHOLD = 0.21;
const BLINK_MS = 300; // короче — это обычное моргание

function eyeAspectRatio(lm, [p1, p2, p3, p4, p5, p6], w, h) {
  const d = (a, b) => Math.hypot((lm[a].x - lm[b].x) * w, (lm[a].y - lm[b].y) * h);
  return (d(p2, p6) + d(p3, p5)) / (2 * d(p1, p4));
}

export class FatigueMonitor {
  constructor(landmarker) {
    this.landmarker = landmarker;
    this.samples = [];
    this.threshold = DEFAULT_THRESHOLD;
    this.closedSince = null;
  }

  get calibrated() {
    return this.samples.length >= CALIBRATION_FRAMES;
  }

  dismiss() {
    this.closedSince = null;
  }

  // Порог подстраивается под конкретного водителя: 70% от его обычного EAR.
  calibrate(ear) {
    if (this.calibrated) return;
    this.samples.push(ear);
    if (!this.calibrated) return;
    const sorted = [...this.samples].sort((a, b) => a - b);
    const open = sorted[Math.floor(sorted.length * 0.7)];
    this.threshold = Math.min(0.27, Math.max(0.14, open * 0.7));
  }

  process(video, now, closedMs) {
    const lm = this.landmarker.detectForVideo(video, now).faceLandmarks?.[0];
    if (lm) {
      const w = video.videoWidth;
      const h = video.videoHeight;
      this.ear = (eyeAspectRatio(lm, LEFT_EAR, w, h) + eyeAspectRatio(lm, RIGHT_EAR, w, h)) / 2;
      this.calibrate(this.ear);
      if (this.ear < this.threshold) this.closedSince ??= now;
      else this.closedSince = null;
    } else {
      // Лицо пропало при закрытых глазах — голова упала, продолжаем отсчёт.
      this.ear = null;
    }

    const closedFor = this.closedSince == null ? 0 : now - this.closedSince;
    let state;
    if (closedFor >= closedMs) state = 'sleep';
    else if (closedFor >= BLINK_MS) state = 'closed';
    else if (!lm) state = 'noface';
    else state = this.calibrated ? 'open' : 'calibrating';

    return { state, closedFor, ear: this.ear, threshold: this.threshold, landmarks: lm ?? null };
  }
}

const STATE_COLOR = {
  open: '#2EE59D',
  calibrating: '#22D3EE',
  closed: '#FFB020',
  sleep: '#FF3B5C',
};

export function drawEyes(canvas, video, landmarks, state) {
  const { ctx, s, ox, oy, vw, vh } = prepCanvas(canvas, video);
  if (!landmarks) return;
  ctx.strokeStyle = STATE_COLOR[state] ?? '#7F8FAA';
  ctx.lineWidth = 2;
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
