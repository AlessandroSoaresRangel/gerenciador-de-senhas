import {test} from "node:test";
import assert from "node:assert/strict";
let handler;
let sent = 0;
globalThis.chrome = {runtime: {
  id: "extension-test", getURL: path => `chrome-extension://extension-test/${path}`,
  onMessage: {addListener: listener => {handler = listener;}},
  sendNativeMessage: (_host, request, callback) => {sent++; callback({...request, ok: true, status: "LOCKED"});}
}};
await import("../dist/service-worker.js");
test("reject website and unrelated extension senders", () => {
  assert.equal(handler({command: "GET_STATUS"}, {id: "extension-test", url: "https://exemplo.com"}, () => {}), false);
  assert.equal(handler({command: "GET_STATUS"}, {id: "other", url: "chrome-extension://extension-test/popup.html"}, () => {}), false);
  assert.equal(sent, 0);
});
test("forward status only from own popup", () => {
  let response;
  assert.equal(handler({command: "GET_STATUS"}, {id: "extension-test", url: "chrome-extension://extension-test/popup.html"}, value => {response = value;}), true);
  assert.deepEqual(response, {ok: true, status: "LOCKED"});
});
