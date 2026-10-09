import {HOST_NAME, PROTOCOL_VERSION, validateStatus} from "./status.js";

const UNLOCK_TIMEOUT_MILLIS = 20000;
const MAX_PASSWORD_LENGTH_CHARACTERS = 4096;
const unlockErrors = ["APP_CLOSED", "UNLOCK_DENIED", "UNAVAILABLE"];

/** A senha transita somente até a sessão desktop; não é salva nem devolvida ao popup. */
export async function unlockVault(password: string) {
    if (!password || password.length > MAX_PASSWORD_LENGTH_CHARACTERS)
        return {ok: false, error: "UNLOCK_DENIED"};
    const requestId = crypto.randomUUID();
    const request = {version: PROTOCOL_VERSION, command: "UNLOCK_VAULT", requestId, password};
    password = "";
    return new Promise<{ok: boolean; status?: string; error?: string}>(resolve => {
        let complete = false;
        const timeout = setTimeout(() => finish({ok: false, error: "TIMEOUT"}), UNLOCK_TIMEOUT_MILLIS);
        function finish(result: {ok: boolean; status?: string; error?: string}) {
            if (complete)
                return;
            complete = true;
            clearTimeout(timeout);
            request.password = "";
            resolve(result);
        }
        try {
            chrome.runtime.sendNativeMessage(HOST_NAME, request, (response: unknown) => {
                if (complete)
                    return;
                if (chrome.runtime.lastError) {
                    finish({ok: false, error: "HOST_UNAVAILABLE"});
                    return;
                }
                try {
                    const value = response as Record<string, unknown> | null;
                    if (value?.version === PROTOCOL_VERSION && value.requestId === requestId &&
                        value.ok === false && typeof value.error === "string" && unlockErrors.includes(value.error)) {
                        finish({ok: false, error: value.error});
                        return;
                    }
                    if (validateStatus(response, requestId) !== "UNLOCKED")
                        throw new Error("INVALID_RESPONSE");
                    finish({ok: true, status: "UNLOCKED"});
                }
                catch {
                    finish({ok: false, error: "INVALID_RESPONSE"});
                }
            });
        }
        catch {
            finish({ok: false, error: "HOST_UNAVAILABLE"});
        }
    });
}
