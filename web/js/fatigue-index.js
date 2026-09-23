// Индекс усталости 0–100: складываем сигналы, которые не зависят от того,
// видны ли глаза (солнцезащитные очки, темнота).

const MIN = 60 * 1000;
const HOUR = 60 * MIN;
const GLANCE_DEG = 15; // поворот головы к зеркалу
const WARMUP_MS = 2 * MIN; // до этого не судим о неподвижности взгляда

// [баллы за событие, максимум, окно учёта]
const EVENT_RULES = {
  microsleep: [40, 40, 10 * MIN],
  nod: [15, 30, 10 * MIN],
  weave: [10, 30, 5 * MIN],
  longblink: [7, 20, 5 * MIN],
  yawn: [5, 15, 10 * MIN],
};

export const FACTOR_LABELS = {
  microsleep: 'Микросон',
  nod: 'Кивки головы',
  weave: 'Виляние машины',
  longblink: 'Долгие моргания',
  yawn: 'Зевание',
  stillness: 'Не смотрит в зеркала',
  time: 'Долго за рулём',
  night: 'Ночные часы',
};

export class FatigueIndex {
  constructor(now) {
    this.startedAt = now;
    this.timeOffset = 0;
    this.events = [];
    this.glances = [];
    this.baseYaw = null;
    this.inGlance = false;
    this.headSince = null;
    this.lastHeadAt = -Infinity;
    this.max = 0;
  }

  add(type, now) {
    this.events.push({ type, t: now });
  }

  // Бодрый водитель регулярно поворачивает голову к зеркалам; уставший «замирает».
  recordHead(yaw, now) {
    if (yaw == null) return;
    this.headSince ??= now;
    this.lastHeadAt = now;
    this.baseYaw ??= yaw;
    const dev = Math.abs(yaw - this.baseYaw);
    if (!this.inGlance && dev > GLANCE_DEG) {
      this.inGlance = true;
      this.glances.push(now);
    } else if (this.inGlance && dev < GLANCE_DEG / 2) {
      this.inGlance = false;
    }
    if (dev < 8) this.baseYaw += (yaw - this.baseYaw) * 0.02;
  }

  compute(now, clock = new Date()) {
    this.events = this.events.filter((e) => now - e.t < 10 * MIN);
    this.glances = this.glances.filter((t) => now - t < 2 * MIN);
    const factors = [];

    for (const [key, [per, cap, window]] of Object.entries(EVENT_RULES)) {
      const count = this.events.filter((e) => e.type === key && now - e.t < window).length;
      if (count) factors.push({ key, points: Math.min(cap, count * per), count });
    }

    const watching = this.headSince != null && now - this.headSince > WARMUP_MS && now - this.lastHeadAt < 5000;
    if (watching) {
      const points = [15, 10, 5][this.glances.length] ?? 0;
      if (points) factors.push({ key: 'stillness', points });
    }

    const hours = (now - this.startedAt + this.timeOffset) / HOUR;
    const timePoints = hours >= 3 ? 15 : hours >= 2 ? 10 : 0;
    if (timePoints) factors.push({ key: 'time', points: timePoints, hours });

    // Пик ДТП из-за сна — 2:00–5:00, рядом повышенный риск.
    const h = clock.getHours();
    const nightPoints = h >= 2 && h < 5 ? 15 : h < 2 || h === 5 ? 8 : 0;
    if (nightPoints) factors.push({ key: 'night', points: nightPoints });

    factors.sort((a, b) => b.points - a.points);
    const score = Math.min(100, factors.reduce((sum, f) => sum + f.points, 0));
    this.max = Math.max(this.max, score);
    return { score, level: score >= 70 ? 'danger' : score >= 40 ? 'warn' : 'ok', factors };
  }
}
