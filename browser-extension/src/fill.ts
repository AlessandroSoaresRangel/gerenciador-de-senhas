import { httpsOrigin } from "./matches.js";
import { HOST_NAME, NATIVE_REQUEST_TIMEOUT_MILLIS, PROTOCOL_VERSION } from "./status.js";
import { inspectLoginForm, applyLoginFill } from "./login-form.js";
import { automaticAccount } from "./autofill.js";
async function requestNative(command: string, fields: Record<string, unknown>) {
    const requestId = crypto.randomUUID();
    const response = await new Promise<Record<string, unknown>>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("TIMEOUT")), NATIVE_REQUEST_TIMEOUT_MILLIS);
        chrome.runtime.sendNativeMessage(HOST_NAME, { version: PROTOCOL_VERSION, command, requestId, ...fields }, result => {
            clearTimeout(timeout);
            if (chrome.runtime.lastError)
                reject(new Error("HOST_UNAVAILABLE"));
            else
                resolve(result);
        });
    });
    if (!response || response.version !== PROTOCOL_VERSION || response.requestId !== requestId || response.ok !== true)
        throw new Error("FILL_DENIED");
    return response;
}
/** A senha permanece no worker apenas durante o preenchimento e nunca é devolvida ao popup. */
export async function fillAccount(accountId: string, expectedOrigin: string, automatic = false) {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.url || tab.id === undefined)
        throw new Error("TAB_CHANGED");
    const origin = httpsOrigin(tab.url);
    if (origin !== expectedOrigin)
        throw new Error("TAB_CHANGED");
    const [probe] = await chrome.scripting.executeScript({
        target: { tabId: tab.id, frameIds: [0] }, func: inspectLoginForm, args: [automatic]
    });
    if (!probe?.documentId || !probe.result?.token || probe.result.origin !== origin || probe.frameId !== 0)
        throw new Error("FORM_NOT_SUPPORTED");
    const document = `${tab.id}:${probe.documentId}:${probe.result.token}`;
    const context = { origin, accountId, document };
    const response = await requestNative(automatic ? "REQUEST_AUTOMATIC_FILL" : "REQUEST_FILL", context);
    const credential = response.credential as Record<string, unknown> | undefined;
    if (response.origin !== origin || response.accountId !== accountId || response.document !== document ||
        !credential || typeof credential.authorization !== "string" || typeof credential.expiresAt !== "number" ||
        typeof credential.username !== "string" || typeof credential.password !== "string")
        throw new Error("INVALID_RESPONSE");
    try {
        const [current] = await chrome.tabs.query({ active: true, currentWindow: true });
        if (current?.id !== tab.id || current.url !== tab.url)
            throw new Error("TAB_CHANGED");
        if (automatic && await automaticAccount(tab.url) !== accountId)
            throw new Error("AUTOFILL_DENIED");
        await requestNative("CONFIRM_FILL", { ...context, authorization: credential.authorization });
        const [latest] = await chrome.tabs.query({ active: true, currentWindow: true });
        if (latest?.id !== tab.id || latest.url !== tab.url)
            throw new Error("TAB_CHANGED");
        if (automatic && await automaticAccount(tab.url) !== accountId)
            throw new Error("AUTOFILL_DENIED");
        const [filled] = await chrome.scripting.executeScript({
            target: { tabId: tab.id, documentIds: [probe.documentId] }, func: applyLoginFill,
            args: [probe.result.token, origin, credential.expiresAt, credential.username, credential.password]
        });
        if (filled?.result !== true)
            throw new Error("FORM_CHANGED");
    }
    finally {
        // Libera referências transitórias; strings internas da JVM/JS não têm apagamento garantido.
        credential.username = "";
        credential.password = "";
    }
}
