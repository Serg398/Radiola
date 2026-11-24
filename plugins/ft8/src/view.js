const messages = document.getElementById("messages");
const status = document.getElementById("status");
const play = document.getElementById("play");
let port, worker, playing = false, workerError = false;
let available = false, startup, nextRequest = 0, startupError = "";

window.addEventListener("message", event => {
  if (port || event.data !== "radiola-plugin" || event.ports.length !== 1) return;
  port = event.ports[0];
  port.onmessage = ({data}) => {
    const message = JSON.parse(data);
    if (message.type === "pcm") {
      if (!worker) return;
      const raw = atob(message.samples);
      const bytes = Uint8Array.from(raw, character => character.charCodeAt(0));
      worker.postMessage({rate: message.rate, startMs: message.startMs, index: message.index,
        epoch: message.epoch, bytes}, [bytes.buffer]);
      return;
    }
    if (message.type === "receiverResult") {
      if (startup?.id !== message.requestId) return;
      if (!message.ok) finishStartup(message.error || "Cannot select USB");
      else {
        startup.modeAccepted = true;
        tryStart();
      }
      return;
    }
    available = message.available;
    for (const [name, color] of Object.entries(message.theme)) {
      document.documentElement.style.setProperty("--" + name, color);
    }
    if (playing !== message.playing) {
      playing = message.playing;
      workerError = false;
      if (playing) {
        finishStartup();
        startWorker();
      }
      else {
        worker?.terminate();
        worker = undefined;
      }
    }
    if (!playing && !startup) {
      status.textContent = message.error || startupError || (available ? "Ready" : "Start the receiver");
    }
    play.disabled = Boolean(startup);
    play.textContent = playing ? "STOP" : "START";
    tryStart();
  };
});
play.onclick = () => {
  if (!port || startup) return;
  if (playing) {
    port.postMessage(JSON.stringify({type: "stop"}));
    return;
  }
  startupError = "";
  const id = "ft8-usb-" + ++nextRequest;
  startup = {id, modeAccepted: false, startSent: false,
    timer: setTimeout(() => finishStartup("USB audio did not become available"), 5000)};
  play.disabled = true;
  status.textContent = "Selecting USB";
  port.postMessage(JSON.stringify({type: "setMode", value: "USB", requestId: id}));
};

function tryStart() {
  if (!startup?.modeAccepted || startup.startSent || !available) return;
  startup.startSent = true;
  status.textContent = "Starting decoder";
  port.postMessage(JSON.stringify({type: "start"}));
}

function finishStartup(error = "") {
  clearTimeout(startup?.timer);
  startup = undefined;
  startupError = error;
  play.disabled = false;
  if (error) status.textContent = error;
}

function startWorker() {
  status.textContent = "Collecting audio";
  try {
    worker = new Worker("worker.js");
    worker.onerror = event => {
      workerError = true;
      status.textContent = event.message || "Cannot load FT8 worker";
    };
    worker.onmessage = ({data}) => {
      if (workerError) return;
      if (data.error) {
        status.textContent = data.error;
        return;
      }
      const utc = new Date(data.slot).toISOString().slice(11, 19);
      const group = document.createDocumentFragment();
      for (const row of data.rows) {
        const item = document.createElement("li");
        for (const field of [utc, Math.round(row.freq) + " Hz", Math.round(row.snr) + " dB", row.msg]) {
          const cell = document.createElement("span");
          cell.textContent = field;
          item.append(cell);
        }
        group.append(item);
      }
      const pinned = messages.scrollTop < 8;
      const previousHeight = messages.scrollHeight;
      messages.prepend(group);
      if (!pinned) messages.scrollTop += messages.scrollHeight - previousHeight;
      while (messages.children.length > 120) messages.lastElementChild.remove();
      status.textContent = data.rows.length + " decodes / " + Math.round(data.ms) + " ms";
    };
  } catch (error) {
    status.textContent = String(error);
  }
}
