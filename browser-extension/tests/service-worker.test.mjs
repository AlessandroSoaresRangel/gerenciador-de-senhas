import {test} from "node:test";
import assert from "node:assert/strict";
let handler;
let sent = 0;
let onUpdated;
globalThis.chrome = {tabs: {onUpdated: {addListener(listener) {onUpdated = listener;}}, onActivated: {addListener() {}}}, runtime: {
  id: "extension-test", getURL: path => `chrome-extension://extension-test/${path}`,
  onMessage: {addListener: listener => {handler = listener;}},
  sendNativeMessage: (_host, request, callback) => {sent++; callback({...request, ok: true, status: "LOCKED"});}
}};
await import("../dist/service-worker.js");
test("reject website and unrelated extension senders", () => {
  assert.equal(handler({command: "GET_STATUS"}, {id: "extension-test", url: "https://exemplo.com"}, () => {}), false);
  assert.equal(handler({command: "GET_STATUS"}, {id: "other", url: "chrome-extension://extension-test/popup.html"}, () => {}), false);
  assert.equal(sent, 0);
  assert.equal(handler({command: "UNLOCK_VAULT", password: "fake"},
    {id: "extension-test", url: "https://exemplo.com"}, () => {}), false);
});

test("completed authorized active tab starts automatic flow; unrelated origin does not", async () => {
  const calls = [];
  let url = "https://exemplo.com/login";
  chrome.tabs.query = async () => [{id: 1, url}];
  chrome.storage = {local: {get: async () => ({autofill: {"https://exemplo.com": "account"}})}};
  chrome.permissions = {contains: async () => true};
  chrome.scripting = {executeScript: async request => request.target.frameIds
    ? [{frameId: 0, documentId: "doc", result: {token: "plan", origin: "https://exemplo.com"}}]
    : [{result: true}]};
  chrome.runtime.sendNativeMessage = (_host, request, respond) => {
    calls.push(request.command);
    respond({...request, ok: true, credential: {authorization: "ticket", expiresAt: Date.now() + 10000, username: "test", password: "fake"}});
  };
  onUpdated(1, {status: "complete"});
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ["REQUEST_AUTOMATIC_FILL", "CONFIRM_FILL"]);
  url = "https://exemplo.com.falso.com";
  onUpdated(1, {status: "complete"});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 2);
});
test("forward status only from own popup", () => {
  chrome.runtime.sendNativeMessage = (_host, request, callback) => callback({...request, ok: true, status: "LOCKED"});
  let response;
  assert.equal(handler({command: "GET_STATUS"}, {id: "extension-test", url: "chrome-extension://extension-test/popup.html"}, value => {response = value;}), true);
  assert.deepEqual(response, {ok: true, status: "LOCKED"});
});

test("only own popup can unlock and password is removed from the received message", async () => {
  let response;
  let passwordReceived;
  chrome.runtime.sendNativeMessage = (_host, request, callback) => {
    passwordReceived = request.password;
    callback({version: request.version, requestId: request.requestId, ok: true, status: "UNLOCKED"});
  };
  const request = {command: "UNLOCK_VAULT", password: "senha fictícia"};
  assert.equal(handler(request, {id: "extension-test", url: "chrome-extension://extension-test/popup.html"},
    value => {response = value;}), true);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(passwordReceived, "senha fictícia");
  assert.equal(request.password, "");
  assert.deepEqual(response, {ok: true, status: "UNLOCKED"});
});
