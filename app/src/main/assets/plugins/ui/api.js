(() => {
  let port;
  let receive;
  window.addEventListener("message", event => {
    if (port || event.data !== "radiola-plugin" || event.ports.length !== 1) return;
    port = event.ports[0];
    port.onmessage = message => receive?.(JSON.parse(message.data));
  });
  window.radiola = {
    onState(callback) { receive = callback; },
    send(type, value) { port?.postMessage(JSON.stringify({type, value})); }
  };
})();
