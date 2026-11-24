const SLOT_MS = 15000;
const DECODE_RATE = 12000;
let rate = 0, epoch = -1, next = 0, clock = 0, slot = 0, start = 0, filled = 0, window;
function begin(index) {
  slot = Math.ceil((clock + index * 1000 / rate) / SLOT_MS) * SLOT_MS;
  start = Math.round((slot - clock) * rate / 1000);
  filled = 0;
  window = new Float32Array(15 * rate);
}

self.onmessage = ({data}) => {
  if (!Number.isInteger(data.rate) || data.rate < 6000 || data.rate > 192000) return;
  const count = data.bytes.byteLength / 2;
  if (epoch !== data.epoch || next !== data.index || rate !== data.rate) {
    epoch = data.epoch;
    rate = data.rate;
    clock = data.startMs;
    begin(data.index);
  }
  next = data.index + count;
  const pcm = new DataView(data.bytes.buffer);
  let index = Math.max(0, start - data.index);
  while (index < count) {
    const amount = Math.min(count - index, window.length - filled);
    for (let i = 0; i < amount; i++) window[filled + i] = pcm.getInt16((index + i) * 2, true) / 32768;
    index += amount;
    filled += amount;
    if (filled === window.length) {
      decodeSlot();
      slot += SLOT_MS;
      start += window.length;
      filled = 0;
    }
  }
};

function decodeSlot() {
  try {
    const started = performance.now();
    let samples = window;
    if (rate !== DECODE_RATE) {
      samples = new Float32Array(15 * DECODE_RATE);
      // Average downsampled intervals to attenuate frequencies above the new passband.
      for (let i = 0; i < samples.length; i++) {
        const a = i * rate / DECODE_RATE;
        const b = (i + 1) * rate / DECODE_RATE;
        let sum = 0;
        for (let j = Math.floor(a); j < b && j < window.length; j++) {
          sum += window[j] * (Math.min(b, j + 1) - Math.max(a, j));
        }
        samples[i] = sum / (b - a);
      }
    }
    const rows = FtxBench.decodeFT8(samples, {sampleRate: DECODE_RATE, freqLow: 200, freqHigh: 3000, depth: 1});
    self.postMessage({slot, rows: rows.map(({freq, snr, msg}) => ({freq, snr, msg})), ms: performance.now() - started});
  } catch (error) {
    self.postMessage({error: String(error)});
  }
}
