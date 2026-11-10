// media capture — microphone (MediaRecorder) and camera
// (capture="environment") ride the SAME upload lane file attachments
// use: upload, then submit immediately, voice/photo Telegram-style.
// Feature-detected everywhere: no MediaRecorder, no getUserMedia →
// the buttons never render, the deck stays fully usable.
import { get, put, post } from './api.js';

const el = (id) => document.getElementById(id);

async function uploadBlob(name, mime, bytes) {
  const q = '?name=' + encodeURIComponent(name) +
            '&mime=' + encodeURIComponent(mime);
  const desc = await put('/api/attachment' + q, bytes);
  return desc['upload/id'];
}

async function sendNow(sessionId, attachmentId, text) {
  const job = await post('/api/turn', {
    session: sessionId,
    text: text || '',
    attachments: [attachmentId],
  });
  return job;
}

// ---------- microphone ----------

let recorder = null;
let recTimer = null;

function stopMicUi(active) {
  const btn = el('cockpit-mic');
  if (!btn) return;
  btn.classList.toggle('rec', Boolean(active));
  btn.setAttribute('aria-pressed', active ? 'true' : 'false');
}

export function micSupported() {
  return typeof navigator !== 'undefined' &&
         navigator.mediaDevices && navigator.mediaDevices.getUserMedia &&
         typeof window.MediaRecorder !== 'undefined';
}

export async function startRecording(sessionId, onJob) {
  if (recorder) return; // already recording — second tap stops below
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const chunks = [];
    recorder = new MediaRecorder(stream);
    // 10-minute hard cap: a forgotten tab must not record forever.
    recTimer = setTimeout(() => { if (recorder) recorder.stop(); }, 600000);
    recorder.onstop = async () => {
      clearTimeout(recTimer);
      stream.getTracks().forEach((t) => t.stop());
      stopMicUi(false);
      const mime = recorder.mimeType || 'audio/webm';
      const blob = new Blob(chunks, { type: mime });
      const r = recorder;
      recorder = null;
      if (blob.size === 0) return;
      const ext = (mime.split('/')[1] || 'webm').split(';')[0];
      const id = await uploadBlob('voice-note.' + ext, mime, blob);
      onJob(await sendNow(sessionId, id, ''));
    };
    recorder.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    recorder.start();
    stopMicUi(true);
    return () => { if (r && r.state === 'recording') r.stop(); };
  } catch (e) {
    recorder = null;
    stopMicUi(false);
    const err = el('cockpit-error');
    if (err) {
      err.textContent = 'microphone unavailable — ' + e.message;
      err.hidden = false;
    }
    return null;
  }
}

// ---------- camera ----------

export function captureAndSend(sessionId, onJob) {
  const input = el('cockpit-camera-input');
  if (!input) return;
  input.onchange = async () => {
    const f = input.files && input.files[0];
    input.value = '';
    if (!f) return;
    try {
      const id = await uploadBlob(f.name || 'photo.jpg', f.type || 'image/jpeg',
                                  await f.arrayBuffer());
      onJob(await sendNow(sessionId, id, ''));
    } catch (e) {
      const err = el('cockpit-error');
      if (err) {
        err.textContent = 'camera upload failed — ' + e.message;
        err.hidden = false;
      }
    }
  };
  input.click();
}

export function micToggle(sessionId, onJob) {
  if (recorder) {
    if (recorder.state === 'recording') recorder.stop();
    return Promise.resolve(null);
  }
  return startRecording(sessionId, onJob);
}
