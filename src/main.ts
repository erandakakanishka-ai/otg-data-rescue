import { Capacitor } from '@capacitor/core';
import { OtgRecovery, type RecoveredFile, type UsbDrive } from './otg-recovery';
import './style.css';

const app = document.querySelector<HTMLDivElement>('#app')!;
app.innerHTML = `
  <main class="shell">
    <header class="hero">
      <div class="brand-mark" aria-hidden="true">↻</div>
      <div>
        <p class="eyebrow">READ-ONLY RECOVERY LAB</p>
        <h1>OTG Data Rescue</h1>
        <p class="subtitle">Recover lost files from a USB flash drive — without rooting your phone.</p>
      </div>
    </header>

    <section class="notice">
      <strong>Stop using the drive now.</strong>
      <span>Do not copy new files to it or format it. This prototype only reads the source drive.</span>
    </section>

    <section class="card">
      <div class="step-title"><span>1</span><h2>Connect your USB drive</h2></div>
      <p class="muted">Use a compatible OTG adapter, then allow Android's USB permission prompt.</p>
      <div class="actions">
        <button id="refresh" class="secondary">Detect drives</button>
        <button id="connect" disabled>Connect</button>
      </div>
      <label class="field">
        <span>Detected drive</span>
        <select id="drives" disabled><option>No drive detected</option></select>
      </label>
      <p id="drive-status" class="status">Waiting for a USB mass-storage device…</p>
    </section>

    <section class="card recommended">
      <div class="step-title"><span>2</span><h2>Create a safety image</h2><b>RECOMMENDED</b></div>
      <p class="muted">Copy the entire drive to an <strong>.img</strong> file on separate storage. The app calculates and verifies SHA-256. You need free space equal to the drive's capacity.</p>
      <div class="actions">
        <button id="image" disabled>Create disk image</button>
        <button id="resume-image" class="secondary" disabled>Resume</button>
        <button id="scan-image" class="secondary">Recover from image</button>
      </div>
      <div class="progress-track"><div id="image-progress" class="progress-bar"></div></div>
      <div class="progress-meta"><span id="image-label">No image created in this session</span><span id="image-percent">0%</span></div>
      <p id="image-hash" class="output-path"></p>
    </section>

    <section class="card">
      <div class="step-title"><span>3</span><h2>Scan for deleted files</h2></div>
      <p class="muted">Choose an image above for the safest workflow, or directly scan the connected drive. Signatures currently targeted: JPG, PNG, GIF, PDF and ZIP-based files.</p>
      <div class="actions">
        <button id="scan" disabled>Direct scan</button>
        <button id="stop" class="danger" disabled>Stop operation</button>
      </div>
      <div class="progress-track"><div id="progress" class="progress-bar"></div></div>
      <div class="progress-meta"><span id="progress-label">Not scanned</span><span id="percent">0%</span></div>
    </section>

    <section class="card results-card">
      <div class="step-title"><span>4</span><h2>Recovered files</h2><b id="count">0</b></div>
      <div id="results" class="results"><p class="empty">Recovered files will appear here.</p></div>
      <p id="output-path" class="output-path"></p>
    </section>

    <footer>
      Experimental prototype • Recovery is never guaranteed • Fragmented or overwritten files may be incomplete
    </footer>
  </main>
`;

const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
const drivesSelect = $<HTMLSelectElement>('drives');
const connectButton = $<HTMLButtonElement>('connect');
const scanButton = $<HTMLButtonElement>('scan');
const imageButton = $<HTMLButtonElement>('image');
const resumeImageButton = $<HTMLButtonElement>('resume-image');
const scanImageButton = $<HTMLButtonElement>('scan-image');
const stopButton = $<HTMLButtonElement>('stop');
let drives: UsbDrive[] = [];
let connectedId: number | null = null;
let files: RecoveredFile[] = [];

function setStatus(message: string, error = false) {
  $('drive-status').textContent = message;
  $('drive-status').classList.toggle('error', error);
}

function addFile(file: RecoveredFile) {
  files.push(file);
  $('count').textContent = String(files.length);
  if (files.length === 1) $('results').innerHTML = '';
  const row = document.createElement('div');
  row.className = 'file-row';
  row.innerHTML = `<span class="file-kind">${file.kind.toUpperCase()}</span><div><strong></strong><small>${formatBytes(file.size)}${file.complete ? '' : ' • partial'}</small></div>`;
  row.querySelector('strong')!.textContent = file.name;
  $('results').prepend(row);
  $('output-path').textContent = file.path;
}

async function detectDrives() {
  setStatus('Looking for OTG drives…');
  try {
    const result = await OtgRecovery.listDrives();
    drives = result.drives;
    drivesSelect.innerHTML = '';
    if (!drives.length) {
      drivesSelect.innerHTML = '<option>No USB mass-storage drive detected</option>';
      drivesSelect.disabled = connectButton.disabled = true;
      setStatus(Capacitor.isNativePlatform()
        ? 'No drive found. Reconnect the OTG adapter and try again.'
        : 'USB recovery only works in the Android app, not in this browser preview.');
      return;
    }
    for (const drive of drives) {
      const option = document.createElement('option');
      option.value = String(drive.deviceId);
      option.textContent = `${drive.product || drive.manufacturer || 'USB drive'} · ${drive.name}`;
      drivesSelect.append(option);
    }
    drivesSelect.disabled = false;
    connectButton.disabled = false;
    setStatus(`${drives.length} USB drive${drives.length === 1 ? '' : 's'} detected.`);
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error), true);
  }
}

$('refresh').addEventListener('click', detectDrives);
connectButton.addEventListener('click', async () => {
  const deviceId = Number(drivesSelect.value);
  try {
    setStatus('Waiting for USB permission…');
    await OtgRecovery.connect({ deviceId });
    connectedId = deviceId;
    scanButton.disabled = false;
    imageButton.disabled = false;
    resumeImageButton.disabled = false;
    setStatus('Drive connected. The source will be opened read-only.');
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error), true);
  }
});

imageButton.addEventListener('click', async () => {
  if (connectedId === null) return;
  try {
    imageButton.disabled = true;
    scanImageButton.disabled = true;
    scanButton.disabled = true;
    stopButton.disabled = false;
    $('image-label').textContent = 'Choose a destination on separate storage…';
    await OtgRecovery.createImage({ deviceId: connectedId });
  } catch (error) {
    $('image-label').textContent = error instanceof Error ? error.message : String(error);
    imageButton.disabled = false;
    scanImageButton.disabled = false;
    scanButton.disabled = false;
    stopButton.disabled = true;
  }
});

resumeImageButton.addEventListener('click', async () => {
  if (connectedId === null) return;
  try {
    imageButton.disabled = true;
    resumeImageButton.disabled = true;
    scanImageButton.disabled = true;
    scanButton.disabled = true;
    stopButton.disabled = false;
    $('image-label').textContent = 'Select the matching partial image. Its checksum will be verified…';
    await OtgRecovery.resumeImage({ deviceId: connectedId });
  } catch (error) {
    $('image-label').textContent = error instanceof Error ? error.message : String(error);
    imageButton.disabled = false;
    resumeImageButton.disabled = false;
    scanImageButton.disabled = false;
    scanButton.disabled = false;
    stopButton.disabled = true;
  }
});

scanImageButton.addEventListener('click', async () => {
  try {
    files = [];
    $('results').innerHTML = '<p class="empty">Select a raw .img file to scan…</p>';
    $('count').textContent = '0';
    imageButton.disabled = true;
    scanImageButton.disabled = true;
    scanButton.disabled = true;
    stopButton.disabled = false;
    await OtgRecovery.scanImage();
  } catch (error) {
    $('progress-label').textContent = error instanceof Error ? error.message : String(error);
    imageButton.disabled = connectedId === null;
    scanImageButton.disabled = false;
    scanButton.disabled = connectedId === null;
    stopButton.disabled = true;
  }
});

scanButton.addEventListener('click', async () => {
  if (connectedId === null) return;
  files = [];
  $('results').innerHTML = '<p class="empty">Scanning… found files will appear here.</p>';
  $('count').textContent = '0';
  scanButton.disabled = true;
  connectButton.disabled = true;
  stopButton.disabled = false;
  try {
    await OtgRecovery.startScan({ deviceId: connectedId });
  } catch (error) {
    setStatus(error instanceof Error ? error.message : String(error), true);
    scanButton.disabled = false;
    connectButton.disabled = false;
    stopButton.disabled = true;
  }
});

stopButton.addEventListener('click', async () => {
  stopButton.disabled = true;
  await OtgRecovery.stopScan();
});

OtgRecovery.addListener('scanProgress', (progress) => {
  $('progress').style.width = `${progress.percent}%`;
  $('percent').textContent = `${progress.percent.toFixed(1)}%`;
  $('progress-label').textContent = `${formatBytes(progress.bytesScanned)} of ${formatBytes(progress.totalBytes)} · ${progress.filesFound} found`;
});

OtgRecovery.addListener('imageProgress', (progress) => {
  $('image-progress').style.width = `${progress.percent}%`;
  $('image-percent').textContent = `${progress.percent.toFixed(1)}%`;
  $('image-label').textContent = `${formatBytes(progress.bytesWritten)} of ${formatBytes(progress.totalBytes)} acquired`;
});

OtgRecovery.addListener('imageComplete', (result) => {
  imageButton.disabled = connectedId === null;
  resumeImageButton.disabled = connectedId === null || result.complete;
  scanImageButton.disabled = false;
  scanButton.disabled = connectedId === null;
  stopButton.disabled = true;
  $('image-label').textContent = result.message;
  if (result.complete) $('image-progress').style.width = '100%';
  $('image-hash').textContent = result.sha256 ? `SHA-256: ${result.sha256}` : 'The image is incomplete; do not treat it as a verified acquisition.';
});

OtgRecovery.addListener('fileRecovered', addFile);
OtgRecovery.addListener('scanComplete', (result) => {
  scanButton.disabled = connectedId === null;
  imageButton.disabled = connectedId === null;
  scanImageButton.disabled = false;
  connectButton.disabled = false;
  stopButton.disabled = true;
  $('progress-label').textContent = result.message;
  if (!result.cancelled) $('progress').style.width = '100%';
  if (!files.length) $('results').innerHTML = '<p class="empty">No supported files were found. The data may be overwritten or use an unsupported format.</p>';
});

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

detectDrives();
