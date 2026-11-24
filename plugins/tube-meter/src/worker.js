const N = 4096;
const hann = Float64Array.from({length:N}, (_, i) => .5 - .5 * Math.cos(2 * Math.PI * i / N));
const windowEnergy = hann.reduce((sum, v) => sum + v * v, 0);
const reverse = Uint16Array.from({length:N}, (_, i) => {
  let result = 0;
  for (let bits = N; bits > 1; bits >>= 1, i >>= 1) result = (result << 1) | (i & 1);
  return result;
});
const real = new Float64Array(N), imag = new Float64Array(N);


self.onmessage = ({data}) => {
  const {bytes, rate, epoch, bandwidthHz, passbandOffsetHz} = data;
  if (!(bandwidthHz > 0) || !Number.isFinite(passbandOffsetHz)) {
    self.postMessage({error:"Update Radiola to read receiver bandwidth"});
    return;
  }
  if (bytes.byteLength !== N * 4 || rate <= 0) return;
  const samples = new DataView(bytes.buffer);
  for (let i = 0; i < N; i++) {
    real[reverse[i]] = samples.getInt16(i * 4, true) * hann[i] / 32768;
    imag[reverse[i]] = samples.getInt16(i * 4 + 2, true) * hann[i] / 32768;
  }
  for (let size = 2; size <= N; size *= 2) {
    const half = size / 2;
    for (let j = 0; j < half; j++) {
      const phase = -2 * Math.PI * j / size;
      const c = Math.cos(phase), s = Math.sin(phase);
      for (let a = j; a < N; a += size) {
        const b = a + half;
        const i = real[b] * c - imag[b] * s;
        const q = real[b] * s + imag[b] * c;
        real[b] = real[a] - i; imag[b] = imag[a] - q;
        real[a] += i; imag[a] += q;
      }
    }
  }
  const minBin = Math.max(-N / 2, Math.ceil((passbandOffsetHz - bandwidthHz / 2) * N / rate));
  const maxBin = Math.min(N / 2 - 1, Math.floor((passbandOffsetHz + bandwidthHz / 2) * N / rate));
  let power = 0;
  for (let i = minBin; i <= maxBin; i++) {

    const bin = (i + N) % N;
    power += real[bin] ** 2 + imag[bin] ** 2;
  }
  self.postMessage({db: Math.min(0, 10 * Math.log10(Math.max(1e-12, power / (N * windowEnergy)))), epoch, bandwidthHz});
};
