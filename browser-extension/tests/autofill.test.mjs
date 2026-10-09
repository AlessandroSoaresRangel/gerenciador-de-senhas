import {test} from "node:test";
import assert from "node:assert/strict";
import {automaticAccount, permissionPattern, setAutomaticAccount} from "../dist/autofill.js";

test("automatic choice requires exact HTTPS origin and persistent permission", async () => {
  globalThis.chrome = {
    storage: {local: {get: async () => ({autofill: {"https://exemplo.com": "account"}})}},
    permissions: {contains: async () => true}
  };
  assert.equal(await automaticAccount("https://exemplo.com/login"), "account");
  assert.equal(await automaticAccount("https://exemplo.com.falso.com"), undefined);
  assert.equal(await automaticAccount("https://sub.exemplo.com"), undefined);
  assert.equal(await automaticAccount("https://exemplo.com:8443"), undefined);
  await assert.rejects(automaticAccount("http://exemplo.com"));
  chrome.permissions.contains = async () => false;
  assert.equal(await automaticAccount("https://exemplo.com"), undefined);
  assert.equal(permissionPattern("https://exemplo.com:8443"), "https://exemplo.com/*");
});

test("choice can be replaced and revoked without storing credentials", async () => {
  let stored = {autofill: {}};
  globalThis.chrome = {
    storage: {local: {get: async () => structuredClone(stored), set: async value => {stored = value;}}},
    permissions: {contains: async () => true}
  };
  await setAutomaticAccount("https://exemplo.com", "first");
  await setAutomaticAccount("https://exemplo.com", "second");
  assert.deepEqual(stored, {autofill: {"https://exemplo.com": "second"}});
  await setAutomaticAccount("https://exemplo.com");
  assert.equal(await automaticAccount("https://exemplo.com"), undefined);
  chrome.permissions.contains = async () => false;
  await assert.rejects(setAutomaticAccount("https://exemplo.com", "first"));
});
