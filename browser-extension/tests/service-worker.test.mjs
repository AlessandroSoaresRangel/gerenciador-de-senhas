import {test} from "node:test";
import assert from "node:assert/strict";
let handler;
let sent = 0;
let onUpdated;
let onActivated;
globalThis.chrome = {tabs: {onRemoved: {addListener() {}}, onUpdated: {addListener(listener) {onUpdated = listener;}}, onActivated: {addListener(listener) {onActivated = listener;}}}, runtime: {
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

test("query accounts once per page visit, including reload, and skip HTTP or inactive tabs", async () => {
  const requests = [];
  const badges = [];
  let tab = {id: 8, url: "https://consulta.exemplo.com/login", status: "complete"};
  chrome.tabs.query = async () => [tab];
  chrome.action = {setBadgeText: async value => badges.push(value)};
  chrome.permissions = {contains: async () => false};
  chrome.runtime.sendNativeMessage = (_host, request, callback) => {
    requests.push(request);
    callback({...request, ok: true, accounts: [{id: "account", title: "Conta", username: "teste"}]});
  };
  const flush = () => new Promise(resolve => setImmediate(resolve));
  onUpdated(8, {status: "complete"});
  onUpdated(8, {status: "complete"});
  onActivated({tabId: 8});
  await flush();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].command, "LIST_MATCHES");
  assert.equal(requests[0].origin, "https://consulta.exemplo.com");
  assert.equal(badges.at(-1).text, "1");
  onUpdated(8, {status: "loading"});
  onUpdated(8, {status: "complete"});
  await flush();
  assert.equal(requests.length, 2);
  tab = {...tab, url: "http://consulta.exemplo.com"};
  onUpdated(8, {status: "loading"});
  onUpdated(8, {status: "complete"});
  await flush();
  assert.equal(requests.length, 2);
  assert.equal(badges.at(-1).text, "");
  onUpdated(99, {status: "complete"});
  await flush();
  assert.equal(requests.length, 2);
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
    if (request.command === "LIST_MATCHES") {
      respond({...request, ok: true, accounts: []});
      return;
    }
    respond({...request, ok: true, credential: {authorization: "ticket", expiresAt: Date.now() + 10000, username: "test", password: "fake"}});
  };
  onUpdated(1, {status: "complete"});
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ["LIST_MATCHES", "REQUEST_AUTOMATIC_FILL", "CONFIRM_FILL"]);
  url = "https://exemplo.com.falso.com";
  onUpdated(1, {status: "complete"});
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ["LIST_MATCHES", "REQUEST_AUTOMATIC_FILL", "CONFIRM_FILL", "LIST_MATCHES"]);
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


test("tab switch before native lookup must not show another tab's matches", async () => {
  const badges = [];
  let reads = 0;
  chrome.action = {setBadgeText: async value => badges.push(value)};
  chrome.permissions = {contains: async () => false};
  chrome.tabs.query = async () => [{id: ++reads === 1 ? 12 : 13,
    url: "https://troca.exemplo.com/login", status: "complete"}];
  chrome.runtime.sendNativeMessage = (_host, request, callback) =>
    callback({...request, ok: true, accounts: [{id: "account", title: "Outra aba", username: "teste"}]});
  onUpdated(12, {status: "complete"});
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(badges, []);
});


test("submitted new login asks before saving and only private confirmation can save", async () => {
  const native = [];
  const prompts = [];
  chrome.tabs.get = async () => ({id: 21, active: true, url: "https://nova.exemplo.com/login"});
  chrome.windows = {create: async options => {prompts.push(options); return {id: 77};}};
  chrome.runtime.sendNativeMessage = (_host, request, respond) => {
    native.push({...request});
    respond({...request, password: undefined, ok: true, accounts: [], saved: true});
  };
  const source = {id: "extension-test", tab: {id: 21}, frameId: 0,
    url: "https://nova.exemplo.com/login"};
  const login = {command: "CAPTURE_LOGIN", username: "teste", password: "senha ficticia"};
  const flush = () => new Promise(resolve => setImmediate(resolve));
  assert.equal(handler({...login}, {...source, frameId: 1}, () => {}), false);
  assert.equal(handler({...login}, source, () => {}), true);
  await flush();
  assert.equal(prompts.length, 1);
  assert.deepEqual(native.map(request => request.command), ["LIST_MATCHES"]);
  assert.equal(prompts[0].url.includes("senha"), false);
  const confirmation = {id: "extension-test", url: prompts[0].url};
  const token = new URL(confirmation.url).searchParams.get("token");
  let response;
  handler({command: "GET_CAPTURED_LOGIN", token}, confirmation, value => {response = value;});
  await flush();
  assert.equal(response.ok, true);
  const draft = response.draft;
  assert.equal(draft.origin, "https://nova.exemplo.com");
  assert.equal(draft.username, "teste");
  assert.equal(draft.password, "senha ficticia");
  assert.equal(handler({command: "SAVE_NEW_ACCOUNT", ...draft}, source, () => {}), false);
  assert.equal(handler({command: "SAVE_NEW_ACCOUNT", ...draft}, confirmation, () => {}), true);
  await flush();
  assert.equal(native.at(-1).command, "SAVE_NEW_ACCOUNT");
  assert.equal(native.at(-1).origin, "https://nova.exemplo.com");
});

test("known user or locked vault must not offer a new account", async () => {
  let opened = 0;
  chrome.windows = {create: async () => {opened++; return {id: 88};}};
  chrome.tabs.get = async () => ({id: 31, active: true, url: "https://conhecida.exemplo.com/login"});
  const source = {id: "extension-test", tab: {id: 31}, frameId: 0,
    url: "https://conhecida.exemplo.com/login"};
  for (const locked of [false, true]) {
    chrome.runtime.sendNativeMessage = (_host, request, respond) => respond({...request,
      ok: !locked, accounts: [{id: "existing", title: "Conta", username: "teste"}], error: "VAULT_LOCKED"});
    const request = {command: "CAPTURE_LOGIN", username: "teste", password: "senha ficticia"};
    let response;
    assert.equal(handler(request, source, value => {response = value;}), true);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(request.password, "");
    assert.equal(response.ok, !locked);
    assert.equal(opened, 0);
  }
});


test("saving first account selects automatic account; multiple accounts preserve prior choice", async () => {
  const writes = [];
  chrome.permissions = {contains: async () => true};
  chrome.storage = {local: {get: async () => ({autofill: {"https://auto.exemplo.com": "previous"}}),
    set: async value => writes.push(value)}};
  const sender = {id: "extension-test", url: "chrome-extension://extension-test/save-account.html?token=test"};
  for (const automaticEnabled of [true, false]) {
    chrome.runtime.sendNativeMessage = (_host, request, respond) => respond({version: request.version,
      requestId: request.requestId, ok: true, saved: true, accountId: "new", automaticEnabled});
    let response;
    assert.equal(handler({command: "SAVE_NEW_ACCOUNT", origin: "https://auto.exemplo.com",
      title: "Conta", username: "teste", password: "senha ficticia", expiresAt: Date.now() + 120000},
      sender, value => {response = value;}), true);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(response.ok, true);
    assert.equal(writes.length, 1);
    assert.equal(writes[0].autofill["https://auto.exemplo.com"], "new");
  }
});

test("status preserves correlated IPC timeout and rejects unrelated errors", () => {
  for (const correlated of [true, false]) {
    chrome.runtime.sendNativeMessage = (_host, request, callback) => callback({version: 1,
      requestId: correlated ? request.requestId : "other", ok: false, error: "TIMEOUT"});
    let response;
    handler({command: "GET_STATUS"}, {id: "extension-test", url: "chrome-extension://extension-test/popup.html"},
      value => {response = value;});
    assert.deepEqual(response, {ok: false, error: correlated ? "TIMEOUT" : "INVALID_RESPONSE"});
  }
});
