import {test} from "node:test";
import assert from "node:assert/strict";

test("query on opening and button click without scheduling periodic refresh", async () => {
  let click;
  const elements = new Map(["#status", "#refresh", "#accounts", "#origin"].map(id => [id, {
    textContent: "", disabled: false, children: [],
    replaceChildren() {this.children = [];}, append(item) {this.children.push(item);},
    addEventListener(event, callback) { if (event === "click") click = callback; }
  }]));
  globalThis.document = {querySelector: id => elements.get(id), createElement: () => ({textContent: "", addEventListener() {}, append() {}})};
  let scheduledIntervals = 0;
  const originalInterval = globalThis.setInterval;
  globalThis.setInterval = () => {scheduledIntervals++; return 0;};
  let resolveStatus;
  let pending = false;
  globalThis.chrome = {runtime: {sendMessage: async request => {
    if (request.command === "GET_STATUS") {
      if (pending) return new Promise(resolve => {resolveStatus = resolve;});
      return {ok: true, status: "UNLOCKED"};
    }
    return {ok: true, origin: "https://exemplo.com", accounts: [{id: "id", title: "Conta", username: "teste"}]};
  }}};
  try {
    await import("../dist/popup.js");
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(scheduledIntervals, 0);
    assert.equal(elements.get("#accounts").children.length, 2);
    pending = true;
    click();
    assert.equal(elements.get("#refresh").disabled, true);
    resolveStatus({ok: true, status: "LOCKED"});
    await new Promise(resolve => setImmediate(resolve));
    assert.match(elements.get("#status").textContent, /bloqueado/);
    assert.equal(elements.get("#accounts").children.length, 0);
  } finally {globalThis.setInterval = originalInterval;}
});
