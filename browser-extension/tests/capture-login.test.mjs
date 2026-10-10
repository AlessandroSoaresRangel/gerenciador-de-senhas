import {test} from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {JSDOM} from "jsdom";

const script = await readFile(new URL("../dist/capture-login.js", import.meta.url), "utf8");
function page(html) {
  const dom = new JSDOM(html, {url: "https://conta.exemplo.com/login", runScripts: "outside-only"});
  const sent = [];
  let submit;
  const originalAddListener = dom.window.document.addEventListener.bind(dom.window.document);
  dom.window.document.addEventListener = (event, listener, ...options) => {
    if (event === "submit") submit = listener;
    originalAddListener(event, listener, ...options);
  };
  dom.window.chrome = {runtime: {sendMessage(request) {sent.push({...request}); return Promise.resolve({ok: true});}}};
  dom.window.document.querySelectorAll("input").forEach(input => input.getClientRects = () => [{}]);
  dom.window.eval(script);
  return {dom, sent, submit};
}

test("capture a submitted login without blocking submit or sending synthetic events", () => {
  const {dom, sent, submit} = page('<form><input autocomplete="username" value="teste"><input type="password" value="senha ficticia"></form>');
  try {
    const form = dom.window.document.querySelector("form");
    form.dispatchEvent(new dom.window.Event("submit", {bubbles: true, cancelable: true}));
    assert.equal(sent.length, 0);
    submit({isTrusted: true, target: form});
    assert.deepEqual({...sent[0]}, {command: "CAPTURE_LOGIN", username: "teste", password: "senha ficticia"});
    assert.equal(form.querySelector('input[type="password"]').value, "senha ficticia");
  } finally {dom.window.close();}
});

test("reject signup, cross-origin action, hidden and ambiguous login forms", () => {
  for (const html of [
    '<form><input value="teste"><input type="password" autocomplete="new-password" value="teste"></form>',
    '<form action="https://outro.com"><input value="teste"><input type="password" value="teste"></form>',
    '<form><input value="teste"><input value="outro"><input type="password" value="teste"></form>',
    '<form><input value="teste"><input type="password" style="display:none" value="teste"></form>',
    '<form><input value="teste"><input type="password" value="teste"><input type="password" value="teste"></form>'
  ]) {
    const {dom, sent, submit} = page(html);
    try {
      submit({isTrusted: true, target: dom.window.document.querySelector("form")});
      assert.equal(sent.length, 0);
    } finally {dom.window.close();}
  }
});
