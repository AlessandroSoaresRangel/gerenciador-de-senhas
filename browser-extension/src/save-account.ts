interface CapturedDraft {
    origin: string;
    title: string;
    username: string;
    password: string;
    expiresAt: number;
    automaticEligible: boolean;
}
export {};
import {permissionPattern} from "./autofill.js";
const form = document.querySelector<HTMLFormElement>("#save-account-form")!;
const titleInput = document.querySelector<HTMLInputElement>("#capture-title")!;
const status = document.querySelector<HTMLParagraphElement>("#capture-status")!;
const saveButton = document.querySelector<HTMLButtonElement>("#save-account")!;
const cancelButton = document.querySelector<HTMLButtonElement>("#cancel-save")!;
let draft: CapturedDraft | undefined;
let expirationTimer: ReturnType<typeof setTimeout> | undefined;

function clearDraft() {
    if (draft) {
        draft.password = "";
        draft.username = "";
        draft = undefined;
    }
    clearTimeout(expirationTimer);
}

async function initialize() {
    try {
        const token = new URL(location.href).searchParams.get("token");
        const result = await chrome.runtime.sendMessage({command: "GET_CAPTURED_LOGIN", token});
        if (result?.ok !== true || !result.draft)
            throw new Error("EXPIRED");
        draft = result.draft;
        document.querySelector("#capture-origin")!.textContent = draft!.origin;
        document.querySelector("#capture-username")!.textContent = draft!.username;
        titleInput.value = draft!.title;
        saveButton.disabled = false;
        status.textContent = draft!.automaticEligible
                ? "A senha será guardada criptografada. Se esta for a única conta do site, ela será usada automaticamente."
                : "A senha será guardada criptografada. A escolha atual de conta automática será mantida.";
        expirationTimer = setTimeout(() => {
            clearDraft();
            saveButton.disabled = true;
            status.textContent = "A confirmação expirou. Envie o login novamente para salvar a conta.";
        }, Math.max(0, draft!.expiresAt - Date.now()));
    } catch {
        clearDraft();
        status.textContent = "A confirmação expirou. Envie o login novamente para salvar a conta.";
    }
}

form.addEventListener("submit", async event => {
    event.preventDefault();
    if (!draft || saveButton.disabled || !titleInput.value.trim())
        return;
    saveButton.disabled = true;
    cancelButton.disabled = true;
    const request = {command: "SAVE_NEW_ACCOUNT", ...draft, title: titleInput.value.trim()};
    try {
        if (draft.automaticEligible) {
            try {
                await chrome.permissions.request({origins: [permissionPattern(draft.origin)]});
            } catch {
                // O cadastro continua mesmo se o navegador não conceder o acesso automático ao site.
            }
        }
        const pending = chrome.runtime.sendMessage(request);
        request.password = "";
        const result = await pending;
        if (result?.ok !== true)
            throw new Error("SAVE_DENIED");
        clearDraft();
        window.close();
    } catch {
        status.textContent = "Não foi possível salvar. Abra e desbloqueie o aplicativo, depois tente novamente.";
        saveButton.disabled = !draft;
    } finally {
        request.password = "";
        cancelButton.disabled = false;
    }
});
cancelButton.addEventListener("click", () => {
    clearDraft();
    window.close();
});
window.addEventListener("pagehide", clearDraft);
void initialize();
