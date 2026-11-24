const messages = document.getElementById("messages");
const status = document.getElementById("status");
let port, worker, playing = false, workerError = false;

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
    if (message.type === "receiverResult") return;
    for (const [name, color] of Object.entries(message.theme)) {
      document.documentElement.style.setProperty("--" + name, color);
    }
    if (playing !== message.playing) {
      playing = message.playing;
      workerError = false;
      if (playing) {

        startWorker();
      }
      else {
        worker?.terminate();
        worker = undefined;
      }
    }
    if (!playing) {
      status.textContent = message.error || (message.available ? "Ready" : "Start the receiver");
    }
  };
});

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
