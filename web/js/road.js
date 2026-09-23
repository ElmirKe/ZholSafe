import { prepCanvas } from './util.js';

export const LABELS = {
  horse: 'Лошадь',
  cow: 'Корова',
  sheep: 'Овца',
  dog: 'Собака',
  bear: 'Животное',
  elephant: 'Животное',
  person: 'Человек',
};
export const LIVESTOCK = new Set(['horse', 'cow', 'sheep']);

// Средняя высота объекта в метрах — для грубой оценки дистанции по размеру рамки.
const HEIGHT_M = { horse: 1.6, cow: 1.4, sheep: 0.8, dog: 0.6, bear: 1.2, elephant: 2.5, person: 1.7 };

export class RoadMonitor {
  constructor(detector) {
    this.detector = detector;
  }

  process(video, now, minScore) {
    const vw = video.videoWidth;
    const vh = video.videoHeight;
    // Вертикальный угол обзора типичной камеры телефона: ~48° в альбомной ориентации, ~65° в портретной.
    const fov = ((vh > vw ? 65 : 48) * Math.PI) / 180;
    const focalPx = vh / (2 * Math.tan(fov / 2));

    return this.detector
      .detectForVideo(video, now)
      .detections.map((d) => {
        const { categoryName: key, score } = d.categories[0];
        const box = d.boundingBox;
        const meters = (HEIGHT_M[key] * focalPx) / Math.max(box.height, 1);
        return { key, label: LABELS[key], score, box, distance: Math.max(5, Math.round(meters / 10) * 10) };
      })
      .filter((h) => h.label && h.score >= minScore)
      .sort((a, b) => a.distance - b.distance);
  }
}

export function drawHazards(canvas, video, hazards, warnDistance) {
  const { ctx, s, ox, oy } = prepCanvas(canvas, video);
  ctx.font = '600 13px Inter, sans-serif';
  ctx.textBaseline = 'top';
  for (const h of hazards) {
    const color = h.distance <= warnDistance ? '#FF3B5C' : '#FFB020';
    const x = ox + h.box.originX * s;
    const y = oy + h.box.originY * s;
    const w = h.box.width * s;
    const bh = h.box.height * s;
    ctx.strokeStyle = color;
    ctx.lineWidth = 3;
    ctx.strokeRect(x, y, w, bh);

    const text = `${h.label} · ≈${h.distance} м · ${Math.round(h.score * 100)}%`;
    const tw = ctx.measureText(text).width + 12;
    const ty = y > 24 ? y - 24 : y + bh + 2;
    ctx.fillStyle = color;
    ctx.fillRect(x, ty, tw, 22);
    ctx.fillStyle = '#fff';
    ctx.fillText(text, x + 6, ty + 4);
  }
}
