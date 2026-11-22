// media capture — microphone (MediaRecorder) and camera
// (capture="environment") ride the SAME upload lane file attachments
// use: upload, then submit immediately, voice/photo Telegram-style.
// Explicit capture/upload state machine prevents double-starts and
// surfaces upload/auth failures visibly instead of rejecting silently.
import { put, post } from './api.js';
import { createMachine, registerMachine, projectAttr } from './fsm.js';

const el = (id) => document.getElementById(id);

const mediaMachine = registerMachine(createMachine({
  name: 'media',
  initial: 'idle',
  transitions: {
    idle: ['recording', 'uploading'],
    recording: ['uploading', 'idle', 'error'],
    uploading: ['idle', 'error'],
    error: ['idle', 'recording', 'uploading'],
  },
  onTransition: ({ to }) => projectAttr('#cockpit-mic', 'data-state', to),
}));

function reportError(msg) {
  const err = el('cockpit-error');
  if (err) {
    err.textContent = msg;
    err.hidden = false;
  }
}

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
  if (recorder || mediaMachine.state() === 'uploading') return null;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const chunks = [];
    const rec = new MediaRecorder(stream);
    recorder = rec;
    // 10-minute hard cap: a forgotten tab must not record forever.
    recTimer = setTimeout(() => { if (rec.state === 'recording') rec.stop(); }, 600000);
    rec.onstop = async () => {
      clearTimeout(recTimer);
      stream.getTracks().forEach((t) => t.stop());
      stopMicUi(false);
      const mime = rec.mimeType || 'audio/webm';
      const blob = new Blob(chunks, { type: mime });
      if (recorder === rec) recorder = null;
      if (blob.size === 0) {
        mediaMachine.force('idle');
        return;
      }
      mediaMachine.force('uploading', { kind: 'voice' });
      try {
        const ext = (mime.split('/')[1] || 'webm').split(';')[0];
        const id = await uploadBlob('voice-note.' + ext, mime, blob);
        const job = await sendNow(sessionId, id, '');
        mediaMachine.force('idle');
        onJob(job);
      } catch (e) {
        mediaMachine.force('error', { error: e && e.message });
        reportError('voice upload failed — ' + (e && e.message || 'try again'));
      }
    };
    rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    rec.start();
    mediaMachine.force('recording');
    stopMicUi(true);
    return () => { if (rec.state === 'recording') rec.stop(); };
  } catch (e) {
    recorder = null;
    stopMicUi(false);
    mediaMachine.force('error', { error: e && e.message });
    reportError('microphone unavailable — ' + (e && e.message || 'denied'));
    return null;
  }
}

// ---------- camera ----------

export function captureAndSend(sessionId, onJob) {
  const input = el('cockpit-camera-input');
  if (!input || mediaMachine.state() === 'uploading') return;
  input.onchange = async () => {
    const f = input.files && input.files[0];
    input.value = '';
    if (!f) return;
    mediaMachine.force('uploading', { kind: 'photo' });
    try {
      const id = await uploadBlob(f.name || 'photo.jpg', f.type || 'image/jpeg',
                                  await f.arrayBuffer());
      const job = await sendNow(sessionId, id, '');
      mediaMachine.force('idle');
      onJob(job);
    } catch (e) {
      mediaMachine.force('error', { error: e && e.message });
      reportError('camera upload failed — ' + (e && e.message || 'try again'));
    }
  };
  input.click();
}

export function micToggle(sessionId, onJob) {
  if (mediaMachine.state() === 'uploading') return Promise.resolve(null);
  if (recorder) {
    if (recorder.state === 'recording') recorder.stop();
    return Promise.resolve(null);
  }
  return startRecording(sessionId, onJob);
}
