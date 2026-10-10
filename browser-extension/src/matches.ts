import { HOST_NAME, NATIVE_REQUEST_TIMEOUT_MILLIS, PROTOCOL_VERSION } from "./status.js";
export interface AccountMatch {
    id: string;
    title: string;
    username: string;
}
export function httpsOrigin(url: string): string {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password)
        throw new Error("HTTPS_REQUIRED");
    return parsed.origin;
}
export function validateMatches(response: unknown, requestId: string, origin: string): AccountMatch[] {
    if (typeof response !== "object" || response === null)
        throw new Error("INVALID_RESPONSE");
    const value = response as Record<string, unknown>;
    if (value.version !== PROTOCOL_VERSION || value.requestId !== requestId || value.ok !== true ||
        value.origin !== origin || !Array.isArray(value.accounts))
        throw new Error("INVALID_RESPONSE");
    return value.accounts.map((account: unknown) => {
        if (typeof account !== "object" || account === null)
            throw new Error("INVALID_RESPONSE");
        const item = account as Record<string, unknown>;
        if (Object.keys(item).length !== 3 || typeof item.id !== "string" || !item.id ||
            typeof item.title !== "string" || typeof item.username !== "string")
            throw new Error("INVALID_RESPONSE");
        return { id: item.id, title: item.title, username: item.username };
    });
}
export function nativeRequest(request: Record<string, unknown>): Promise<unknown> {
    return new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("TIMEOUT")), NATIVE_REQUEST_TIMEOUT_MILLIS);
        chrome.runtime.sendNativeMessage(HOST_NAME, request, response => {
            clearTimeout(timeout);
            if (chrome.runtime.lastError)
                reject(new Error("HOST_UNAVAILABLE"));
            else
                resolve(response);
        });
    });
}
/** O endereço vem do navegador; dados enviados pelo popup nunca escolhem a origem da consulta. */
export async function listActiveTabMatches() {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab?.url || tab.id === undefined)
        throw new Error("HTTPS_REQUIRED");
    const origin = httpsOrigin(tab.url);
    const requestId = crypto.randomUUID();
    const response = await nativeRequest({ version: PROTOCOL_VERSION, command: "LIST_MATCHES", requestId, origin });
    // Descarta a resposta se a aba mudou ou navegou enquanto o host processava a consulta.
    const [current] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (current?.id !== tab.id || current.url !== tab.url)
        throw new Error("TAB_CHANGED");
    const result = response as Record<string, unknown> | null;
    if (result?.ok === false && result.requestId === requestId && result.version === PROTOCOL_VERSION) {
        throw new Error(typeof result.error === "string" ? result.error : "INVALID_RESPONSE");
    }
    return { origin, accounts: validateMatches(response, requestId, origin) };
}
