import { HOST_NAME, NATIVE_REQUEST_TIMEOUT_MILLIS, PROTOCOL_VERSION, validateStatus } from "./status.js";
import { listActiveTabMatches } from "./matches.js";
import { fillAccount } from "./fill.js";
import { automaticAccount, setAutomaticAccount } from "./autofill.js";
import { unlockVault } from "./unlock.js";

const pendingAutomaticTabs = new Set<number>();

async function tryAutomaticFill(tabId: number) {
    if (pendingAutomaticTabs.has(tabId))
        return;
    pendingAutomaticTabs.add(tabId);
    try {
        const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
        if (tab?.id !== tabId || !tab.url)
            return;
        const accountId = await automaticAccount(tab.url);
        if (accountId)
            await fillAccount(accountId, new URL(tab.url).origin, true);
    }
    catch {
        // Cofre bloqueado, permissão revogada ou formulário incompatível: nenhum preenchimento.
    }
    finally {
        pendingAutomaticTabs.delete(tabId);
    }
}

chrome.tabs.onUpdated.addListener((tabId, change) => {
    if (change.status === "complete")
        void tryAutomaticFill(tabId);
});
chrome.tabs.onActivated.addListener(({tabId}) => void tryAutomaticFill(tabId));

chrome.runtime.onMessage.addListener((message: unknown, sender, respond) => {
    // Somente o popup próprio pode consultar o cofre; mensagens de páginas/sites são recusadas.
    if (sender.id !== chrome.runtime.id || sender.url !== chrome.runtime.getURL("popup.html") ||
        typeof message !== "object" || message === null ||
        !["GET_STATUS", "LIST_MATCHES", "REQUEST_FILL", "SET_AUTOFILL", "GET_AUTOFILL", "UNLOCK_VAULT"].includes(String((message as Record<string, unknown>).command)))
        return false;
    const request = message as Record<string, unknown>;
    if (request.command === "UNLOCK_VAULT") {
        if (Object.keys(request).length !== 2 || typeof request.password !== "string")
            return false;
        const unlocking = unlockVault(request.password);
        request.password = "";
        void unlocking.then(respond).catch(() => respond({ok: false, error: "UNLOCK_DENIED"}));
        return true;
    }
    if (request.command === "SET_AUTOFILL" || request.command === "GET_AUTOFILL") {
        void (async () => {
            const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
            if (!tab?.url || new URL(tab.url).origin !== request.origin)
                throw new Error("TAB_CHANGED");
            if (request.command === "GET_AUTOFILL") {
                respond({ok: true, accountId: await automaticAccount(tab.url)});
                return;
            }
            if (request.accountId !== undefined && typeof request.accountId !== "string")
                throw new Error("INVALID_ACCOUNT");
            if (request.accountId) {
                const matches = await listActiveTabMatches();
                if (matches.origin !== request.origin || !matches.accounts.some(account => account.id === request.accountId))
                    throw new Error("INVALID_ACCOUNT");
            }
            await setAutomaticAccount(String(request.origin), request.accountId as string | undefined);
            respond({ok: true});
            if (request.accountId && tab.id !== undefined)
                void tryAutomaticFill(tab.id);
        })().catch(() => respond({ok: false, error: "AUTOFILL_DENIED"}));
        return true;
    }
    if ((message as Record<string, unknown>).command === "REQUEST_FILL") {
        const request = message as Record<string, unknown>;
        if (typeof request.accountId !== "string" || typeof request.origin !== "string")
            return false;
        void fillAccount(request.accountId, request.origin).then(() => respond({ ok: true }))
            .catch(() => respond({ ok: false, error: "FILL_DENIED" }));
        return true;
    }
    if ((message as Record<string, unknown>).command === "LIST_MATCHES") {
        void listActiveTabMatches().then(result => respond({ ok: true, ...result }))
            .catch(error => respond({ ok: false, error: error instanceof Error ? error.message : "UNAVAILABLE" }));
        return true;
    }
    const requestId = crypto.randomUUID();
    const timeout = setTimeout(() => finish({ ok: false, error: "TIMEOUT" }), NATIVE_REQUEST_TIMEOUT_MILLIS);
    let complete = false;
    function finish(result: unknown) {
        if (complete)
            return;
        complete = true;
        clearTimeout(timeout);
        respond(result);
    }
    chrome.runtime.sendNativeMessage(HOST_NAME, { version: PROTOCOL_VERSION, command: "GET_STATUS", requestId }, (response: unknown) => {
        if (chrome.runtime.lastError) {
            finish({ ok: false, error: "HOST_UNAVAILABLE" });
            return;
        }
        try {
            finish({ ok: true, status: validateStatus(response, requestId) });
        }
        catch {
            finish({ ok: false, error: "INVALID_RESPONSE" });
        }
    });
    return true;
});
