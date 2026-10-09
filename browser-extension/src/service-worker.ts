import { HOST_NAME, validateStatus } from "./status.js";
import { listActiveTabMatches } from "./matches.js";

chrome.runtime.onMessage.addListener((message: unknown, sender, respond) => {
  // Somente o popup próprio pode consultar o cofre; mensagens de páginas/sites são recusadas.
  if (sender.id !== chrome.runtime.id || sender.url !== chrome.runtime.getURL("popup.html") ||
      typeof message !== "object" || message === null ||
      !["GET_STATUS", "LIST_MATCHES"].includes(String((message as Record<string, unknown>).command))) return false;
  if ((message as Record<string, unknown>).command === "LIST_MATCHES") {
    void listActiveTabMatches().then(result => respond({ok: true, ...result}))
      .catch(error => respond({ok: false, error: error instanceof Error ? error.message : "UNAVAILABLE"}));
    return true;
  }
  const requestId = crypto.randomUUID();
  const timeout = setTimeout(() => finish({ok: false, error: "TIMEOUT"}), 5000);
  let complete = false;
  function finish(result: unknown) {
    if (complete) return;
    complete = true;
    clearTimeout(timeout);
    respond(result);
  }
  chrome.runtime.sendNativeMessage(HOST_NAME, {version: 1, command: "GET_STATUS", requestId}, (response: unknown) => {
    if (chrome.runtime.lastError) { finish({ok: false, error: "HOST_UNAVAILABLE"}); return; }
    try { finish({ok: true, status: validateStatus(response, requestId)}); }
    catch { finish({ok: false, error: "INVALID_RESPONSE"}); }
  });
  return true;
});
