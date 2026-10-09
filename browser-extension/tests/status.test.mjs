import {test} from "node:test";
import assert from "node:assert/strict";
import {validateStatus, statuses} from "../dist/status.js";
test("accept known states and correlated responses", () => {
  for (const status of statuses) assert.equal(validateStatus({version: 1, requestId: "test", ok: true, status}, "test"), status);
});
test("reject invalid, unrelated and unsupported responses", () => {
  for (const response of [null, {}, {version: 1, requestId: "other", ok: true, status: "LOCKED"}, {version: 2, requestId: "test", ok: true, status: "LOCKED"}, {version: 1, requestId: "test", ok: true, status: "FAKE"}])
    assert.throws(() => validateStatus(response, "test"));
});
