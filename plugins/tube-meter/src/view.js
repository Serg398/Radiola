const byId = id => document.getElementById(id);
const dial = byId("dial"), ctx = dial.getContext("2d");
const level = byId("level"), status = byId("status"), band = byId("band"), expand = byId("expand");
let port, worker, playing = false, expanded = false, busy = false, frameCount = 0;
let target = -80, needle = -80, velocity = 0, last = 0, epoch, raf = 0;

window.addEventListener("message", event => {
  if (port || event.data !== "radiola-plugin" || event.ports.length !== 1) return;
  port = event.ports[0];
  port.onmessage = ({data}) => {
    const message = JSON.parse(data);
    if (message.type === "channelIq") {
      if (!worker || (++frameCount % 4 !== 1 && message.epoch === epoch) || busy) return;
      busy = true;
      const bytes = Uint8Array.from(atob(message.samples), c => c.charCodeAt(0));
      const {rate, epoch, bandwidthHz, passbandOffsetHz} = message;
      worker.postMessage({bytes, rate, epoch, bandwidthHz, passbandOffsetHz}, [bytes.buffer]);
      return;
    }
    for (const [name, color] of Object.entries(message.theme)) {
      document.documentElement.style.setProperty("--" + name, color);
    }
    expanded = message.layout === "card";
    expand.textContent = expanded ? "COLLAPSE" : "EXPAND";
    expand.setAttribute("aria-pressed", expanded);
    if (playing !== message.playing) {
      playing = message.playing;
      if (playing) openWorker();
      else {
        worker?.terminate();
        worker = undefined;
        target = -80;
        band.textContent = "";
        level.firstChild.textContent = "--.- ";
        scheduleDraw();
      }
    }
    if (!playing) status.textContent = message.error || (message.available ? "Ready" : "Start USB receiver");
  };
});
expand.onclick = () => send("layout", expanded ? "tall" : "card");

function send(type, value) { port?.postMessage(JSON.stringify({type,value})); }

function openWorker() {
  busy = false;
  status.textContent = "Waiting for IQ";
  worker = new Worker("worker.js");
  worker.onerror = () => { status.textContent = "IQ processing failed"; };
  worker.onmessage = ({data}) => {
    busy = false;
    if (data.error) { status.textContent = data.error; return; }
    if (epoch !== data.epoch) velocity = 0;
    epoch = data.epoch;
    target = Math.max(-80, data.db);
    band.textContent = "BW " + (data.bandwidthHz / 1000) + " kHz";
    status.textContent = "RTL-SDR";
    scheduleDraw();
  };
}

function scheduleDraw() {
  if (!raf) raf = requestAnimationFrame(draw);
}
function draw(now) {
  raf = 0;
  const dt = Math.min(.034, (now - last) / 1000);
  last = now;
  velocity += (195 * (target - needle) - 24 * velocity) * dt;
  needle += velocity * dt;
  if (Math.abs(target - needle) > .03 || Math.abs(velocity) > .05) scheduleDraw();
  if (playing && epoch !== undefined) level.firstChild.textContent = needle.toFixed(1) + " ";
  const {width:w, height:h} = dial.getBoundingClientRect();
  if (!w || !h) return;
  const dpr = devicePixelRatio;
  const px = Math.round(w * dpr), py = Math.round(h * dpr);
  if (dial.width !== px || dial.height !== py) { dial.width = px; dial.height = py; }
  ctx.setTransform(dpr,0,0,dpr,0,0);
  ctx.clearRect(0,0,w,h);
  const cx = w * .5, cy = h * .91;
  const rx = Math.min(w * .40, h * 3.4), ry = h * .70;
  const pos = (value, scale) => {
    const angle = (value + 40) / 40 * 1.13;
    return [cx + Math.sin(angle) * rx * scale, cy - Math.cos(angle) * ry * scale];
  };
  const stroke = (a,b) => { ctx.beginPath(); ctx.moveTo(...a); ctx.lineTo(...b); ctx.stroke(); };
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.font = "600 " + Math.max(8,Math.min(15,h * .105)) + "px Georgia";
  for (let value = -80; value <= 0; value += 2) {
    const marked = value % 10 === 0;
    const color = value >= -10 ? "#761e12" : "#322013";
    ctx.strokeStyle = color;
    ctx.lineWidth = marked ? 1.25 : .7;
    stroke(pos(value,marked ? .78 : .86),pos(value,.96));
    if (marked) { ctx.fillStyle = color; ctx.fillText(value,...pos(value,1.10)); }
  }
  const tip = pos(Math.max(-80,Math.min(0,needle)),.93);
  ctx.strokeStyle = "#451d13"; ctx.lineWidth = 3;
  stroke([cx,cy+2],[tip[0]+1,tip[1]+1]);
  ctx.strokeStyle = "#bc2f20"; ctx.lineWidth = 1.6;
  stroke([cx,cy],tip);
  ctx.beginPath(); ctx.arc(cx,cy,Math.min(6,h*.10),0,Math.PI*2);
  ctx.fillStyle = "#43261a"; ctx.fill();
}
new ResizeObserver(scheduleDraw).observe(dial);
