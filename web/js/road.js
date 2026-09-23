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
const IGNORED_COLOR = 'rgba(234, 242, 255, .35)';

// Полоса движения в кадре: трапеция по центру нижней половины (доли ширины/высоты).
// Человек считается «на полосе», если его ноги (низ рамки) внутри неё.
const PATH = { top: 0.5, topHalf: 0.12, bottomHalf: 0.3 };

export function inPath(box, vw, vh) {
  const footY = (box.originY + box.height) / vh;
  if (footY < PATH.top) return false;
  const t = Math.min(1, (footY - PATH.top) / (1 - PATH.top));
  const half = PATH.topHalf + (PATH.bottomHalf - PATH.topHalf) * t;
  const footX = (box.originX + box.width / 2) / vw;
  return Math.abs(footX - 0.5) <= half;
}

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
          inPath: inPath(box, vw, vh),
          distance: Math.max(5, Math.round(meters / 10) * 10),
        };
      })
      .filter((h) => h && h.score >= minScore)
      .sort((a, b) => a.distance - b.distance);
  }
}

function drawPath(ctx, s, ox, oy, vw, vh) {
  const pt = (fx, fy) => [ox + fx * vw * s, oy + fy * vh * s];
  ctx.save();
  ctx.setLineDash([8, 8]);
  ctx.strokeStyle = 'rgba(167, 139, 250, .6)';
  ctx.fillStyle = 'rgba(167, 139, 250, .07)';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(...pt(0.5 - PATH.topHalf, PATH.top));
  ctx.lineTo(...pt(0.5 + PATH.topHalf, PATH.top));
  ctx.lineTo(...pt(0.5 + PATH.bottomHalf, 1));
  ctx.lineTo(...pt(0.5 - PATH.bottomHalf, 1));
  ctx.closePath();
  ctx.fill();
  ctx.stroke();
  ctx.restore();
}

// В городе рисуем полосу движения, а людей вне её — бледными (их не учитываем).
export function drawHazards(canvas, video, hazards, warnDistance, city = false) {
  const { ctx, s, ox, oy, vw, vh } = prepCanvas(canvas, video);
  if (city) drawPath(ctx, s, ox, oy, vw, vh);
  ctx.font = '600 13px Inter, sans-serif';
  ctx.textBaseline = 'top';
  for (const h of hazards) {
    const color = h.ignored ? IGNORED_COLOR : h.distance <= warnDistance ? NEAR_COLOR : KINDS[h.kind].color;
    const x = ox + h.box.originX * s;
    const y = oy + h.box.originY * s;
    const w = h.box.width * s;
    const bh = h.box.height * s;
    ctx.strokeStyle = color;
    ctx.lineWidth = h.ignored ? 1.5 : 3;
    ctx.strokeRect(x, y, w, bh);
    if (h.ignored) continue;

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
