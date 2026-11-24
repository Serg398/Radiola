const byId = id => document.getElementById(id);
const play = byId("play");
const layouts = [...document.querySelectorAll("[data-layout]")];
let state;
play.onclick = () => radiola.send(state?.playing ? "stop" : "start");
for (const button of layouts) button.onclick = () => radiola.send("layout", button.dataset.layout);

function updateCard(next) {
  state = next;
  for (const [name, color] of Object.entries(next.theme)) {
    document.documentElement.style.setProperty("--" + name, color);
  }
  play.textContent = next.playing ? "STOP" : "START";
  play.disabled = !next.playing && !next.available;
  byId("status").textContent = next.error || "";
  for (const button of layouts) {
    button.setAttribute("aria-pressed", button.dataset.layout === next.layout);
  }
}
const plot = byId("plot");
const ctx = plot.getContext("2d");
let pendingFrame = 0;

function scheduleDraw() {
  if (!pendingFrame) pendingFrame = requestAnimationFrame(draw);
}

function draw() {
  pendingFrame = 0;
  if (!state) return;
  const {width, height} = plot.getBoundingClientRect();
  if (width < 1 || height < 1) return;
  const scale = devicePixelRatio;
  const w = Math.round(width * scale);
  const h = Math.round(height * scale);
  if (plot.width !== w || plot.height !== h) {
    plot.width = w;
    plot.height = h;
  }
  ctx.setTransform(scale, 0, 0, scale, 0, 0);
  ctx.clearRect(0, 0, width, height);
  const {theme, spectrum} = state;
  if (!spectrum || spectrum.bins.length < 2) return;
  const labelHeight = 11;
  const bottom = Math.max(0, height - labelHeight);
  ctx.font = "8px system-ui";
  ctx.strokeStyle = theme.grid;
  ctx.fillStyle = theme.muted;
  for (let tick = 0; tick <= 4; tick++) {
    const x = tick * width / 4;
    const label = (tick * spectrum.sampleRate / 8000).toFixed(1) + " kHz";
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, bottom);
    ctx.stroke();
    ctx.fillText(label, Math.min(x, width - ctx.measureText(label).width), height - 2);
  }
  ctx.strokeStyle = theme.accent;
  ctx.beginPath();
  for (const [i, db] of spectrum.bins.entries()) {
    const x = i * width / (spectrum.bins.length - 1);
    const y = bottom * (1 - Math.max(0, Math.min(1, (db + 100) / 100)));
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.stroke();
}

new ResizeObserver(scheduleDraw).observe(plot);
radiola.onState(next => {
  updateCard(next);
  scheduleDraw();
});
