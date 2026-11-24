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
  byId("status").textContent = next.error || (!next.available ? "Audio source unavailable" : "");
  for (const button of layouts) {
    button.setAttribute("aria-pressed", button.dataset.layout === next.layout);
  }
}
const gain = byId("gain");
gain.oninput = () => {
  byId("level").textContent = gain.value + "%";
  radiola.send("gain", gain.valueAsNumber / 100);
};
radiola.onState(next => {
  updateCard(next);
  if (!gain.matches(":active")) {
    gain.value = Math.round(next.gain * 100);
    byId("level").textContent = gain.value + "%";
  }
});
