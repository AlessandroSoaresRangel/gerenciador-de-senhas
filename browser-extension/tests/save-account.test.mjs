import {test} from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {JSDOM} from "jsdom";

const html = await readFile(new URL("../save-account.html", import.meta.url), "utf8");
const flush = () => new Promise(resolve => setImmediate(resolve));

for (const confirm of [false, true]) {
  test(confirm ? "private confirmation saves captured credentials only after user submits"
    : "cancel private confirmation without saving", async () => {
    const dom = new JSDOM(html, {url: "chrome-extension://extension-test/save-account.html?token=abc"});
    const originalClose = dom.window.close.bind(dom.window);
    let closed = false;
    dom.window.close = () => {closed = true;};
    globalThis.document = dom.window.document;
    globalThis.window = dom.window;
    globalThis.location = dom.window.location;
    const calls = [];
    globalThis.chrome = {runtime: {sendMessage: async request => {
      calls.push({...request});
      if (request.command === "GET_CAPTURED_LOGIN") return {ok: true, draft: {
        origin: "https://conta.exemplo.com", title: "Conta", username: "teste",
        password: "senha ficticia", expiresAt: Date.now() + 120000
      }};
      return {ok: true, saved: true};
    }}};
    try {
      await import(`../dist/save-account.js?confirm=${confirm}`);
      await flush();
      assert.equal(calls.length, 1);
      assert.equal(document.body.textContent.includes("senha ficticia"), false);
      if (confirm) {
        document.querySelector("#capture-title").value = "Nome escolhido";
        document.querySelector("#save-account-form").dispatchEvent(new dom.window.Event("submit", {cancelable: true}));
        await flush();
        assert.equal(calls[1].command, "SAVE_NEW_ACCOUNT");
        assert.equal(calls[1].password, "senha ficticia");
        assert.equal(calls[1].title, "Nome escolhido");
        assert.match(document.querySelector("#capture-status").textContent, /Conta salva/);
      } else {
        document.querySelector("#cancel-save").click();
        assert.equal(closed, true);
        assert.equal(calls.length, 1);
      }
    } finally {
      dom.window.dispatchEvent(new dom.window.Event("pagehide"));
      originalClose();
    }
  });
}
