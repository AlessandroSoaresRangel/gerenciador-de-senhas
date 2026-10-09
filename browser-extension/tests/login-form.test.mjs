import {test} from "node:test";
import assert from "node:assert/strict";
import {JSDOM} from "jsdom";
import {inspectLoginForm, applyLoginFill} from "../dist/login-form.js";

function page(html, url = "https://exemplo.com/login") {
  const dom = new JSDOM(html, {url});
  const w = dom.window;
  Object.assign(globalThis, {window: w, document: w.document, location: w.location,
    getComputedStyle: w.getComputedStyle.bind(w), HTMLInputElement: w.HTMLInputElement, Event: w.Event});
  // jsdom não calcula layout; habilita dimensões apenas para os inputs deste teste.
  w.HTMLInputElement.prototype.getClientRects = function() {return this.style.display === "none" ? [] : [{}];};
  return dom;
}
const login = '<form><input name="user" autocomplete="username"><input type="password" autocomplete="current-password"><button>Entrar</button></form>';

test("automatic mode preserves fields already edited by user", () => {
  const dom = page(login);
  try {
    document.querySelector("input").value = "typed";
    assert.throws(() => inspectLoginForm(true));
    document.querySelector("input").value = "";
    const plan = inspectLoginForm(true);
    document.querySelector("input").value = "typed during request";
    assert.throws(() => applyLoginFill(plan.token, plan.origin, Date.now() + 10000, "u", "p"));
    assert.equal(document.querySelector("input").value, "typed during request");
    assert.equal(document.querySelector('[type="password"]').value, "");
  } finally {dom.window.close();}
});

test("fill login fields, emit input events and never submit", () => {
  const dom = page(login);
  try {
    let submits = 0, changes = 0;
    document.querySelector("form").addEventListener("submit", () => submits++);
    document.querySelector("input").addEventListener("input", () => changes++);
    const plan = inspectLoginForm();
    assert.equal(applyLoginFill(plan.token, plan.origin, Date.now() + 10000, "usuário", "senha fictícia"), true);
    assert.equal(document.querySelector("input").value, "usuário");
    assert.equal(document.querySelector('[type="password"]').value, "senha fictícia");
    assert.equal(changes, 1);
    assert.equal(submits, 0);
    assert.throws(() => applyLoginFill(plan.token, plan.origin, Date.now() + 10000, "u", "p"));
  } finally {dom.window.close();}
});
test("reject hidden, password-change, ambiguous and cross-origin forms", () => {
  for (const html of [login.replace('type="password"', 'style="display:none" type="password"'),
    login.replace("current-password", "new-password"), login.replace("current-password", "section-account new-password"),
    login.replace('type="password"', 'style="opacity:0" type="password"'),
    login.replace("</form>", '<input type="password"></form>'),
    login + login, login.replace("<form>", '<form action="https://outro.com/login">')]) {
    const dom = page(html);
    try {assert.throws(inspectLoginForm);} finally {dom.window.close();}
  }
  const dom = page(login, "http://exemplo.com/login");
  try {assert.throws(inspectLoginForm);} finally {dom.window.close();}
});
test("reject expired plans, replaced fields and changed form action", () => {
  for (const mutate of [() => document.querySelector("input").remove(),
    () => document.querySelector("form").action = "https://outro.com",
    () => document.querySelector('[type="password"]').readOnly = true]) {
    const dom = page(login);
    try {
      const plan = inspectLoginForm();
      mutate();
      assert.throws(() => applyLoginFill(plan.token, plan.origin, Date.now() + 10000, "u", "p"));
    } finally {dom.window.close();}
  }
  const dom = page(login);
  try {const plan = inspectLoginForm(); assert.throws(() => applyLoginFill(plan.token, plan.origin, Date.now() - 1, "u", "p"));}
  finally {dom.window.close();}
});
