import {test} from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {JSDOM} from "jsdom";

test("unlock form appears only for running locked desktop and clears password immediately", async () => {
  const dom = new JSDOM(await readFile(new URL("../popup.html", import.meta.url), "utf8"));
  globalThis.document = dom.window.document;
  globalThis.window = dom.window;
  let state = "APP_CLOSED";
  let completeUnlock;
  let passwordReceived;
  globalThis.chrome = {runtime: {sendMessage: request => {
    if (request.command === "GET_STATUS") return Promise.resolve({ok: true, status: state});
    if (request.command === "UNLOCK_VAULT") {
      passwordReceived = request.password;
      return new Promise(resolve => {completeUnlock = resolve;});
    }
    return Promise.resolve({ok: true, origin: "https://exemplo.com", accounts: []});
  }}};
  const flush = () => new Promise(resolve => setImmediate(resolve));
  try {
    await import("../dist/popup.js");
    await flush();
    const form = document.querySelector("#unlock-form");
    const input = document.querySelector("#master-password");
    assert.equal(form.hidden, true);
    state = "LOCKED";
    document.querySelector("#refresh").click();
    await flush();
    assert.equal(form.hidden, false);
    input.value = "senha fictícia";
    form.dispatchEvent(new dom.window.Event("submit", {cancelable: true}));
    assert.equal(input.value, "");
    assert.equal(input.disabled, true);
    assert.equal(passwordReceived, "senha fictícia");
    completeUnlock({ok: false, error: "UNLOCK_DENIED"});
    await flush();
    assert.equal(input.disabled, false);
    assert.equal(form.hidden, false);
    input.value = "senha fictícia";
    form.dispatchEvent(new dom.window.Event("submit", {cancelable: true}));
    state = "UNLOCKED";
    completeUnlock({ok: true, status: "UNLOCKED"});
    await flush();
    assert.equal(form.hidden, true);
    assert.equal(input.value, "");
    assert.match(document.querySelector("#status").textContent, /Nenhuma conta/);
  } finally {dom.window.close();}
});
