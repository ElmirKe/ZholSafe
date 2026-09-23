let ctx;

// Браузеры разрешают звук только после нажатия — вызываем на кнопке «Поехали».
export function unlock() {
  try {
    ctx ??= new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') ctx.resume();
  } catch {
    ctx = null;
  }
}

function tone(freq, start, duration, type, volume) {
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.type = type;
  osc.frequency.value = freq;
  gain.gain.setValueAtTime(0.0001, start);
  gain.gain.exponentialRampToValueAtTime(volume, start + 0.01);
  gain.gain.exponentialRampToValueAtTime(0.0001, start + duration);
  osc.connect(gain).connect(ctx.destination);
  osc.start(start);
  osc.stop(start + duration + 0.02);
}

// Сирена микросна: ~0,7 сек, повторяется, пока глаза закрыты.
export function siren(opts) {
  if (opts.sound && ctx) {
    const t = ctx.currentTime;
    for (let i = 0; i < 4; i++) tone(i % 2 ? 1400 : 900, t + i * 0.18, 0.17, 'sawtooth', 0.35);
  }
  if (opts.vibrate) navigator.vibrate?.([300, 100, 300]);
}

// Тройной сигнал — препятствие на дороге.
export function chime(opts) {
  if (opts.sound && ctx) {
    const t = ctx.currentTime;
    for (let i = 0; i < 3; i++) tone(1046, t + i * 0.16, 0.1, 'square', 0.25);
  }
  if (opts.vibrate) navigator.vibrate?.([120, 80, 120, 80, 120]);
}

export function say(text, opts) {
  if (!opts.voice || !('speechSynthesis' in window)) return;
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'ru-RU';
  u.rate = 1.05;
  speechSynthesis.speak(u);
}

export function silence() {
  if ('speechSynthesis' in window) speechSynthesis.cancel();
  navigator.vibrate?.(0);
}
