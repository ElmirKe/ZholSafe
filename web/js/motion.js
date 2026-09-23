// Виляние машины по гироскопу телефона. Сонный водитель долго не корректирует
// руль, а потом резко его дёргает — так работают системы VW и Bosch без камеры.

const CALM_DEG_S = 1.5; // руль почти не трогают
const CALM_MS = 3000;
const JERK_DEG_S = 8; // резкая коррекция: из покоя до 8°/с
const JERK_WINDOW_MS = 500;
const COOLDOWN_MS = 2000;
const MIN_SPEED = 8; // м/с (~30 км/ч): в городе и на парковке не считаем

export class MotionMonitor {
  constructor(onCorrection, getSpeed) {
    this.onCorrection = onCorrection;
    this.getSpeed = getSpeed;
    this.available = false;
    this.gravity = null;
    this.calmSince = null;
    this.calmBreak = null;
    this.lastJerk = -Infinity;
    this.handler = (e) => this.handle(e);
  }

  // iPhone спрашивает разрешение на датчики — только прямо в обработчике нажатия.
  static requestPermission() {
    const DME = window.DeviceMotionEvent;
    if (typeof DME?.requestPermission === 'function') {
      return DME.requestPermission()
        .then((r) => r === 'granted')
        .catch(() => false);
    }
    return Promise.resolve(Boolean(DME));
  }

  start() {
    window.addEventListener('devicemotion', this.handler);
  }

  stop() {
    window.removeEventListener('devicemotion', this.handler);
  }

  handle(e) {
    const g = e.accelerationIncludingGravity;
    const r = e.rotationRate;
    if (g?.x == null || r?.alpha == null) return;
    this.available = true;

    // Сглаженная гравитация даёт вертикаль при любом креплении телефона.
    const k = 0.1;
    this.gravity = this.gravity
      ? {
          x: this.gravity.x + (g.x - this.gravity.x) * k,
          y: this.gravity.y + (g.y - this.gravity.y) * k,
          z: this.gravity.z + (g.z - this.gravity.z) * k,
        }
      : { x: g.x, y: g.y, z: g.z };
    const { x, y, z } = this.gravity;
    const n = Math.hypot(x, y, z) || 1;
    // Скорость поворота машины (град/с) — вращение вокруг вертикали.
    const yaw = (r.beta * x + r.gamma * y + r.alpha * z) / n;

    const t = e.timeStamp;

    const speed = this.getSpeed();
    if (speed != null && speed < MIN_SPEED) {
      this.calmSince = null;
      return;
    }

    if (Math.abs(yaw) < CALM_DEG_S) {
      this.calmSince ??= t;
      return;
    }
    // Спокойный участок закончился — если за полсекунды поворот разогнался до рывка, это коррекция.
    if (this.calmSince != null) {
      this.calmBreak = { at: t, calmFor: t - this.calmSince };
      this.calmSince = null;
    }
    const afterCalm = this.calmBreak && this.calmBreak.calmFor >= CALM_MS && t - this.calmBreak.at <= JERK_WINDOW_MS;
    if (afterCalm && Math.abs(yaw) >= JERK_DEG_S && t - this.lastJerk > COOLDOWN_MS) {
      this.lastJerk = t;
      this.calmBreak = null;
      this.onCorrection();
    }
  }
}
