import {httpsOrigin} from "./matches.js";

/** Permissões do Chrome não distinguem portas; a regra armazenada usa a origem exata. */
export function permissionPattern(origin: string) {
    return `https://${new URL(httpsOrigin(origin)).hostname}/*`;
}

export async function automaticAccount(url: string): Promise<string | undefined> {
    const origin = httpsOrigin(url);
    if (!await chrome.permissions.contains({origins: [permissionPattern(origin)]}))
        return undefined;
    const {autofill = {}} = await chrome.storage.local.get("autofill");
    const account = Object.hasOwn(autofill, origin) ? autofill[origin] : undefined;
    return typeof account === "string" && account ? account : undefined;
}

export async function setAutomaticAccount(origin: string, accountId?: string) {
    if (httpsOrigin(origin) !== origin)
        throw new Error("INVALID_ORIGIN");
    if (accountId && !await chrome.permissions.contains({origins: [permissionPattern(origin)]}))
        throw new Error("PERMISSION_REQUIRED");
    const {autofill = {}} = await chrome.storage.local.get("autofill");
    if (accountId)
        autofill[origin] = accountId;
    else
        delete autofill[origin];
    await chrome.storage.local.set({autofill});
}
