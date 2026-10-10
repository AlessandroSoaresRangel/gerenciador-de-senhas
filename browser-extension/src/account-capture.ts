import {httpsOrigin, nativeRequest, validateMatches} from "./matches.js";
import {PROTOCOL_VERSION} from "./status.js";

const DRAFT_LIFETIME_MILLIS = 120000;
const MAX_LABEL_LENGTH_CHARACTERS = 512;
const MAX_PASSWORD_LENGTH_CHARACTERS = 4096;
interface Draft {
    origin: string;
    title: string;
    username: string;
    password: string;
    expiresAt: number;
}
const drafts = new Map<string, {draft: Draft; timer: ReturnType<typeof setTimeout>}>();
const pendingTabs = new Set<number>();

function removeDraft(token: string) {
    const pending = drafts.get(token);
    if (pending) {
        clearTimeout(pending.timer);
        pending.draft.password = "";
        pending.draft.username = "";
        drafts.delete(token);
    }
}

function validText(value: unknown, maximum: number): value is string {
    return typeof value === "string" && value.trim().length > 0 && value.length <= maximum;
}

async function offerSave(request: Record<string, unknown>, sender: chrome.runtime.MessageSender) {
    const tabId = sender.tab!.id!;
    const password = String(request.password);
    request.password = "";
    if (pendingTabs.has(tabId))
        return {ok: false};
    pendingTabs.add(tabId);
    let token: string | undefined;
    try {
        const origin = httpsOrigin(sender.url!);
        const tab = await chrome.tabs.get(tabId);
        if (!tab.active || !tab.url || httpsOrigin(tab.url) !== origin)
            return {ok: false};
        const username = String(request.username).trim();
        const requestId = crypto.randomUUID();
        const response = await nativeRequest({version: PROTOCOL_VERSION, command: "LIST_MATCHES", requestId, origin});
        const accounts = validateMatches(response, requestId, origin);
        if (accounts.some(account => account.username.trim() === username))
            return {ok: true, offered: false};
        token = crypto.randomUUID();
        const draft = {origin, title: new URL(origin).hostname, username, password,
            expiresAt: Date.now() + DRAFT_LIFETIME_MILLIS};
        const draftToken = token;
        drafts.set(token, {draft, timer: setTimeout(() => removeDraft(draftToken), DRAFT_LIFETIME_MILLIS)});
        await chrome.windows.create({type: "popup", width: 410, height: 500,
            url: chrome.runtime.getURL(`save-account.html?token=${token}`)});
        return {ok: true, offered: true};
    } catch {
        if (token)
            removeDraft(token);
        return {ok: false};
    } finally {
        pendingTabs.delete(tabId);
    }
}

async function saveConfirmed(request: Record<string, unknown>) {
    const password = String(request.password);
    request.password = "";
    if (typeof request.expiresAt !== "number" || Date.now() >= request.expiresAt)
        return {ok: false, error: "EXPIRED"};
    const origin = httpsOrigin(String(request.origin));
    const requestId = crypto.randomUUID();
    const payload = {version: PROTOCOL_VERSION, command: "SAVE_NEW_ACCOUNT", requestId, origin,
        title: String(request.title), username: String(request.username), password};
    try {
        const result = await nativeRequest(payload) as Record<string, unknown>;
        if (result?.version !== PROTOCOL_VERSION || result.requestId !== requestId || result.ok !== true
                || typeof result.saved !== "boolean")
            return {ok: false, error: "SAVE_DENIED"};
        return {ok: true, saved: result.saved};
    } finally {
        payload.password = "";
    }
}

/** O content script só oferece cadastro; salvar exige uma mensagem da página privada de confirmação. */
export function handleCaptureMessage(message: Record<string, unknown>, sender: chrome.runtime.MessageSender,
        respond: (response: unknown) => void): boolean {
    if (sender.id !== chrome.runtime.id)
        return false;
    if (message.command === "CAPTURE_LOGIN") {
        if (sender.frameId !== 0 || sender.tab?.id === undefined || !sender.url
                || Object.keys(message).length !== 3
                || !validText(message.username, MAX_LABEL_LENGTH_CHARACTERS)
                || !validText(message.password, MAX_PASSWORD_LENGTH_CHARACTERS))
            return false;
        try { httpsOrigin(sender.url); } catch { return false; }
        void offerSave(message, sender).then(respond);
        return true;
    }
    if (sender.url?.split("?")[0] !== chrome.runtime.getURL("save-account.html"))
        return false;
    if (message.command === "GET_CAPTURED_LOGIN") {
        const token = new URL(sender.url).searchParams.get("token");
        const pending = typeof message.token === "string" && message.token === token ? drafts.get(token!) : undefined;
        if (!pending || Date.now() >= pending.draft.expiresAt) {
            if (token) removeDraft(token);
            respond({ok: false, error: "EXPIRED"});
        } else {
            // Transfere uma vez à janela privada; o rascunho não vai para storage, URL ou página do site.
            const draft = {...pending.draft};
            removeDraft(token!);
            respond({ok: true, draft});
        }
        return true;
    }
    if (message.command === "SAVE_NEW_ACCOUNT") {
        if (!validText(message.origin, 2048) || !validText(message.title, MAX_LABEL_LENGTH_CHARACTERS)
                || !validText(message.username, MAX_LABEL_LENGTH_CHARACTERS)
                || !validText(message.password, MAX_PASSWORD_LENGTH_CHARACTERS))
            return false;
        void saveConfirmed(message).then(respond).catch(() => respond({ok: false, error: "SAVE_DENIED"}));
        return true;
    }
    return false;
}
