import {test} from "node:test";
import assert from "node:assert/strict";
import {unlockVault} from "../dist/unlock.js";

test("forwards password transiently and accepts only correlated unlocked response", async () => {
  let sent;
  globalThis.chrome = {runtime: {sendNativeMessage: (_host, request, respond) => {
    sent = request;
    respond({version: request.version, requestId: request.requestId, ok: true, status: "UNLOCKED"});
  }}};
  assert.deepEqual(await unlockVault("senha fictícia"), {ok: true, status: "UNLOCKED"});
  assert.equal(sent.command, "UNLOCK_VAULT");
  assert.equal(sent.password, "");
});

test("closed app, wrong password and uncorrelated replies cannot unlock", async () => {
  for (const error of ["APP_CLOSED", "UNLOCK_DENIED", "UNAVAILABLE", "TIMEOUT", "INVALID_RESPONSE"]) {
    globalThis.chrome = {runtime: {sendNativeMessage: (_host, request, respond) => {
      respond({version: request.version, requestId: error === "INVALID_RESPONSE" ? "other" : request.requestId,
        ok: false, error});
    }}};
    assert.deepEqual(await unlockVault("senha fictícia"), {ok: false, error});
  }
});
