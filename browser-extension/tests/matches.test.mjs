import {test} from "node:test";
import assert from "node:assert/strict";
import {httpsOrigin, validateMatches, listActiveTabMatches} from "../dist/matches.js";

test("extract only HTTPS origins and preserve host and port", () => {
  assert.equal(httpsOrigin("https://CONTA.exemplo.com:443/login?q=1"), "https://conta.exemplo.com");
  assert.equal(httpsOrigin("https://exemplo.com:8443/login"), "https://exemplo.com:8443");
  for (const url of ["http://exemplo.com", "chrome://extensions", "https://user:pass@exemplo.com"]) assert.throws(() => httpsOrigin(url));
});
test("reject unrelated response and secret fields", () => {
  const account = {id: "id", title: "Conta", username: "teste"};
  const response = {version: 1, requestId: "test", ok: true, origin: "https://exemplo.com", accounts: [account]};
  assert.deepEqual(validateMatches(response, "test", response.origin), [account]);
  assert.throws(() => validateMatches(response, "other", response.origin));
  assert.throws(() => validateMatches(response, "test", "https://outro.com"));
  assert.throws(() => validateMatches({...response, accounts: [{...account, password: "segredo"}]}, "test", response.origin));
});
test("browser controls origin and navigation invalidates response", async () => {
  let navigation = false;
  let queries = 0;
  globalThis.chrome = {
    tabs: {query: async () => { queries++; return [{id: 1, url: navigation && queries > 1 ? "https://outro.com" : "https://exemplo.com/login"}]; }},
    runtime: {sendNativeMessage: (_host, request, respond) => {
      assert.equal(request.origin, "https://exemplo.com");
      respond({...request, ok: true, accounts: []});
    }}
  };
  assert.deepEqual(await listActiveTabMatches(), {origin: "https://exemplo.com", accounts: []});
  queries = 0;
  navigation = true;
  await assert.rejects(listActiveTabMatches(), /TAB_CHANGED/);
});
