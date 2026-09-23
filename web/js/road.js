import { prepCanvas } from './util.js';

// Что ищем на дороге: вид → тип опасности.
const SPECIES = {
  horse: { kind: 'livestock', name: 'лошадь', height: 1.6 },
  cow: { kind: 'livestock', name: 'корова', height: 1.4 },
  sheep: { kind: 'livestock', name: 'овца', height: 0.8 },
  person: { kind: 'person', name: 'человек', height: 1.7 },
};
export const DETECT_CLASSES = Object.keys(SPECIES);

export const KINDS = {
  livestock: { label: 'Скот', color: '#FFB020' },
  person: { label: 'Человек', color: '#A78BFA' },
};
const NEAR_COLOR = '#FF3B5C';

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
        const species = SPECIES[key];
        if (!species) return null;
        const box = d.boundingBox;
        const meters = (species.height * focalPx) / Math.max(box.height, 1);
        return {
          key,
          kind: species.kind,
          label: KINDS[species.kind].label,
          species: species.name,
          score,
          box,
          distance: Math.max(5, Math.round(meters / 10) * 10),
        };
      })
      .filter((h) => h && h.score >= minScore)
      .sort((a, b) => a.distance - b.distance);
  }
}

export function drawHazards(canvas, video, hazards, warnDistance) {
  const { ctx, s, ox, oy } = prepCanvas(canvas, video);
  ctx.font = '600 13px Inter, sans-serif';
  ctx.textBaseline = 'top';
  for (const h of hazards) {
    const color = h.distance <= warnDistance ? NEAR_COLOR : KINDS[h.kind].color;
    const x = ox + h.box.originX * s;
    const y = oy + h.box.originY * s;
    const w = h.box.width * s;
    const bh = h.box.height * s;
    ctx.strokeStyle = color;
    ctx.lineWidth = 3;
    ctx.strokeRect(x, y, w, bh);

    const what = h.kind === 'livestock' ? `Скот: ${h.species}` : 'Человек';
    const text = `${what} · ≈${h.distance} м · ${Math.round(h.score * 100)}%`;
    const tw = ctx.measureText(text).width + 12;
    const ty = y > 24 ? y - 24 : y + bh + 2;
    ctx.fillStyle = color;
    ctx.fillRect(x, ty, tw, 22);
    ctx.fillStyle = '#fff';
    ctx.fillText(text, x + 6, ty + 4);
  }
}
