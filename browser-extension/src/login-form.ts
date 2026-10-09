interface LoginPlan {
    automatic: boolean;
    token: string;
    origin: string;
    url: string;
    expiresAt: number;
    username: HTMLInputElement;
    password: HTMLInputElement;
    form: HTMLFormElement;
}
declare global {
    var cofreLoginPlan: LoginPlan | undefined;
}
/** Função autocontida para executar no mundo isolado, somente no frame principal. */
export function inspectLoginForm(automatic = false) {
    // executeScript serializa a função: constantes e auxiliares precisam ficar neste corpo.
    const PLAN_LIFETIME_MILLIS = 10000;
    globalThis.cofreLoginPlan = undefined;
    if (window.top !== window || location.protocol !== "https:")
        throw new Error("HTTPS_REQUIRED");
    function visible(input: HTMLInputElement) {
        const style = getComputedStyle(input);
        for (let element: Element | null = input; element; element = element.parentElement) {
            if (getComputedStyle(element).opacity === "0")
                return false;
        }
        return !input.disabled && !input.readOnly && input.isConnected && input.type !== "hidden" &&
            input.getClientRects().length > 0 && style.visibility === "visible" && style.display !== "none";
    }
    const candidates: LoginPlan[] = [];
    for (const form of Array.from(document.forms)) {
        const inputs = Array.from(form.querySelectorAll<HTMLInputElement>("input"));
        const passwords = inputs.filter(input => input.type === "password");
        // Mais de uma senha ou new-password indica cadastro/troca, não login.
        if (passwords.length !== 1 || passwords[0].autocomplete.split(/\s+/).includes("new-password") || !visible(passwords[0]))
            continue;
        const action = new URL(form.action || location.href, location.href);
        if (action.origin !== location.origin || action.protocol !== "https:")
            continue;
        const usernames = inputs.filter(input => ["text", "email", "tel"].includes(input.type) && visible(input));
        const explicit = usernames.filter(input => input.autocomplete.split(/\s+/).some(token => ["username", "email"].includes(token)));
        const eligible = explicit.length ? explicit : usernames;
        if (eligible.length !== 1)
            continue;
        if (automatic && (eligible[0].value || passwords[0].value))
            continue;
        candidates.push({ automatic, token: crypto.randomUUID(), origin: location.origin, url: location.href,
            expiresAt: Date.now() + PLAN_LIFETIME_MILLIS, username: eligible[0], password: passwords[0], form });
    }
    if (candidates.length !== 1)
        throw new Error("FORM_NOT_SUPPORTED");
    globalThis.cofreLoginPlan = candidates[0];
    return { token: candidates[0].token, origin: candidates[0].origin };
}
/** Revalida as referências DOM: substituição do formulário ou navegação invalida o plano. */
export function applyLoginFill(token: string, origin: string, expiresAt: number, username: string, password: string) {
    const plan = globalThis.cofreLoginPlan;
    globalThis.cofreLoginPlan = undefined;
    if (!plan || plan.token !== token || window.top !== window || location.protocol !== "https:" ||
        location.origin !== origin || plan.origin !== origin || location.href !== plan.url ||
        Date.now() >= Math.min(plan.expiresAt, expiresAt))
        throw new Error("TAB_CHANGED");
    if (plan.automatic && (plan.username.value || plan.password.value))
        throw new Error("FORM_CHANGED");
    for (const input of [plan.username, plan.password]) {
        const style = getComputedStyle(input);
        for (let element: Element | null = input; element; element = element.parentElement) {
            if (getComputedStyle(element).opacity === "0")
                throw new Error("FORM_CHANGED");
        }
        if (!input.isConnected || input.form !== plan.form || input.disabled || input.readOnly ||
            input.getClientRects().length === 0 || style.visibility !== "visible" || style.display === "none")
            throw new Error("FORM_CHANGED");
    }
    const inputs = Array.from(plan.form.querySelectorAll<HTMLInputElement>("input"));
    const action = new URL(plan.form.action || location.href, location.href);
    if (action.origin !== origin || inputs.filter(input => input.type === "password").length !== 1 ||
        plan.password.type !== "password" || plan.password.autocomplete.split(/\s+/).includes("new-password") ||
        !["text", "email", "tel"].includes(plan.username.type))
        throw new Error("FORM_CHANGED");
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    if (!setter)
        throw new Error("FORM_NOT_SUPPORTED");
    // Atualiza ambos antes de emitir eventos: scripts do site não podem trocar o segundo campo
    // durante o evento do primeiro. Nenhum submit/click é disparado.
    setter.call(plan.username, username);
    setter.call(plan.password, password);
    for (const input of [plan.username, plan.password]) {
        input.dispatchEvent(new Event("input", { bubbles: true }));
        input.dispatchEvent(new Event("change", { bubbles: true }));
    }
    return true;
}
