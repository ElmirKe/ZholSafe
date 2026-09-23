const gum = (video) => navigator.mediaDevices.getUserMedia({ video, audio: false });

export function openDriverCamera() {
  return gum({ facingMode: 'user', width: { ideal: 640 }, height: { ideal: 480 } });
}

// Ищем заднюю камеру, отличную от уже открытой фронтальной.
// На ноутбуке с одной веб-камерой вернёт null — тогда дорогу берём из видеофайла.
export async function openRoadCamera(excludeId, roadOnly) {
  const size = { width: { ideal: 1280 }, height: { ideal: 720 } };
  try {
    if (roadOnly) return await gum({ facingMode: 'environment', ...size });
    const devices = (await navigator.mediaDevices.enumerateDevices()).filter(
      (d) => d.kind === 'videoinput' && d.deviceId && d.deviceId !== excludeId,
    );
    const back = devices.find((d) => /back|rear|environment|задн/i.test(d.label)) ?? devices[0];
    if (!back) return null;
    return await gum({ deviceId: { exact: back.deviceId }, ...size });
  } catch (err) {
    console.warn('Камера дороги недоступна', err);
    return null;
  }
}

export function stopStream(stream) {
  stream?.getTracks().forEach((t) => t.stop());
}
