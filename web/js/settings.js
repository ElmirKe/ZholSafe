const KEY = 'zholsafe.settings';
const DEFAULTS = {
  closedSec: 1.5,
  warnDistance: 200,
  minScore: 0.45,
  sound: true,
  voice: true,
  vibrate: true,
  autoReport: true,
  cameraMode: 'auto',
};

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY)) || {};
  } catch {
    return {};
  }
}

export const settings = { ...DEFAULTS, ...read() };

function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify(settings));
  } catch {
    /* приватный режим — настройки живут до перезагрузки */
  }
}

function format(key, value) {
  if (key === 'closedSec') return `${value.toFixed(1).replace('.', ',')} сек`;
  if (key === 'warnDistance') return `${value} м`;
  if (key === 'minScore') return `${Math.round(value * 100)}%`;
  return String(value);
}

export function bindSettings(root) {
  const render = (key) =>
    root.querySelectorAll(`[data-out="${key}"]`).forEach((el) => (el.textContent = format(key, settings[key])));

  root.querySelectorAll('[data-setting]').forEach((el) => {
    const key = el.dataset.setting;
    if (el.type === 'checkbox') el.checked = settings[key];
    else el.value = settings[key];
    render(key);
    el.addEventListener('input', () => {
      settings[key] = el.type === 'checkbox' ? el.checked : el.type === 'range' ? Number(el.value) : el.value;
      render(key);
      save();
    });
  });
}
