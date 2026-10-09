import {test} from "node:test";
import assert from "node:assert/strict";
import {fillAccount} from "../dist/fill.js";

test("fill targets the probed document after confirmation and erases transient credential", async () => {
  const calls = [];
  const credential = {authorization: "ticket", expiresAt: Date.now() + 10000, username: "teste", password: "senha fictícia"};
  globalThis.chrome = {
    tabs: {query: async () => [{id: 1, url: "https://exemplo.com/login"}]},
    runtime: {sendNativeMessage: (_host, request, respond) => {
      calls.push(request.command);
      respond({...request, ok: true, credential});
    }},
    scripting: {executeScript: async request => {
      if (request.target.frameIds) return [{frameId: 0, documentId: "document", result: {token: "plan", origin: "https://exemplo.com"}}];
      assert.deepEqual(request.target.documentIds, ["document"]);
      assert.equal(request.args[4], "senha fictícia");
      return [{result: true}];
    }}
  };
  await fillAccount("account", "https://exemplo.com");
  assert.deepEqual(calls, ["REQUEST_FILL", "CONFIRM_FILL"]);
  assert.equal(credential.password, "");
});
test("navigation and vault lock prevent insertion", async () => {
  for (const block of ["navigation", "lock"]) {
    let queries = 0, inserts = 0;
    globalThis.chrome = {
      tabs: {query: async () => [{id: 1, url: ++queries > 1 && block === "navigation" ? "https://outro.com" : "https://exemplo.com/login"}]},
      runtime: {sendNativeMessage: (_host, request, respond) => respond({...request, ok: request.command !== "CONFIRM_FILL",
        credential: {authorization: "ticket", expiresAt: Date.now() + 10000, username: "u", password: "p"}})},
      scripting: {executeScript: async request => {
        if (request.target.frameIds) return [{frameId: 0, documentId: "document", result: {token: "plan", origin: "https://exemplo.com"}}];
        inserts++; return [{result: true}];
      }}
    };
    await assert.rejects(fillAccount("account", "https://exemplo.com"));
    assert.equal(inserts, 0);
  }
});

test("automatic fill uses distinct native authorization and stops after site revocation", async () => {
  let enabled = true;
  let inserts = 0;
  const calls = [];
  globalThis.chrome = {
    storage: {local: {get: async () => ({autofill: enabled ? {"https://exemplo.com": "account"} : {}})}},
    permissions: {contains: async () => true},
    tabs: {query: async () => [{id: 1, url: "https://exemplo.com/login"}]},
    runtime: {sendNativeMessage: (_host, request, respond) => {
      calls.push(request.command);
      enabled = false;
      respond({...request, ok: true, credential: {authorization: "ticket", expiresAt: Date.now() + 10000, username: "test", password: "fake"}});
    }},
    scripting: {executeScript: async request => {
      if (request.target.frameIds) return [{frameId: 0, documentId: "doc", result: {token: "plan", origin: "https://exemplo.com"}}];
      inserts++;
      return [{result: true}];
    }}
  };
  await assert.rejects(fillAccount("account", "https://exemplo.com", true));
  assert.deepEqual(calls, ["REQUEST_AUTOMATIC_FILL"]);
  assert.equal(inserts, 0);
});
