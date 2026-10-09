import {permissionPattern} from "./autofill.js";
const statusLabel = document.querySelector<HTMLParagraphElement>("#status")!;
const refresh = document.querySelector<HTMLButtonElement>("#refresh")!;
const accounts = document.querySelector<HTMLUListElement>("#accounts")!;
const originLabel = document.querySelector<HTMLParagraphElement>("#origin")!;
const labels: Record<string, string> = {
    LOCKED: "Cofre bloqueado. Desbloqueie no aplicativo.",
    UNLOCKED: "Cofre desbloqueado.",
    NOT_CREATED: "Crie o cofre no aplicativo.",
    APP_CLOSED: "Abra o aplicativo Cofre local.",
    UNAVAILABLE: "O aplicativo não conseguiu consultar o cofre."
};
function clearAccounts() {
    accounts.replaceChildren();
    originLabel.textContent = "";
}
async function update() {
    if (refresh.disabled)
        return;
    refresh.disabled = true;
    clearAccounts();
    statusLabel.textContent = "Consultando…";
    try {
        const response = await chrome.runtime.sendMessage({ command: "GET_STATUS" });
        if (!response?.ok || response.status !== "UNLOCKED") {
            clearAccounts();
            statusLabel.textContent = response?.ok && labels[response.status] ? labels[response.status] :
                "Conexão indisponível. Confira a instalação do host e tente atualizar.";
        }
        if (response?.ok && response.status === "UNLOCKED") {
            const matches = await chrome.runtime.sendMessage({ command: "LIST_MATCHES" });
            if (!matches?.ok) {
                clearAccounts();
                const errors: Record<string, string> = {
                    HTTPS_REQUIRED: "Abra um site HTTPS para consultar as contas.",
                    TAB_CHANGED: "A aba mudou. Atualize a consulta.",
                    VAULT_LOCKED: "O cofre foi bloqueado. Desbloqueie no aplicativo.",
                    APP_CLOSED: "Abra o aplicativo Cofre local."
                };
                statusLabel.textContent = errors[matches?.error] ?? "Não foi possível consultar as contas.";
            }
            else {
                clearAccounts();
                originLabel.textContent = matches.origin;
                for (const account of matches.accounts) {
                    const item = document.createElement("li");
                    // textContent mantém títulos e usuários como texto, mesmo se contiverem HTML.
                    item.textContent = `${account.title} — ${account.username}`;
                    const fill = document.createElement("button");
                    fill.textContent = "Preencher";
                    fill.addEventListener("click", async () => {
                        if (refresh.disabled)
                            return;
                        refresh.disabled = true;
                        const buttons = accounts.querySelectorAll("button");
                        buttons.forEach(button => button.disabled = true);
                        statusLabel.textContent = "Preenchendo…";
                        try {
                            const result = await chrome.runtime.sendMessage({ command: "REQUEST_FILL", accountId: account.id, origin: matches.origin });
                            statusLabel.textContent = result?.ok ? "Campos preenchidos. Envie o formulário quando desejar." :
                                "Não foi possível preencher. Confira o cofre, a aba e se há um único formulário de login visível.";
                        }
                        catch {
                            statusLabel.textContent = "Não foi possível preencher.";
                        }
                        finally {
                            refresh.disabled = false;
                            buttons.forEach(button => button.disabled = false);
                        }
                    });
                    item.append(fill);
                    const automatic = document.createElement("button");
                    automatic.textContent = "Usar automaticamente neste site";
                    automatic.addEventListener("click", async () => {
                        automatic.disabled = true;
                        try {
                            const granted = await chrome.permissions.request({origins: [permissionPattern(matches.origin)]});
                            if (!granted) {
                                statusLabel.textContent = "Permissão para o site recusada.";
                                return;
                            }
                            const result = await chrome.runtime.sendMessage({command: "SET_AUTOFILL", origin: matches.origin, accountId: account.id});
                            statusLabel.textContent = result?.ok
                                ? "Automático autorizado para esta conta. Habilite também a permissão na conta no aplicativo desktop."
                                : "Não foi possível autorizar o automático.";
                        }
                        catch {
                            statusLabel.textContent = "Não foi possível autorizar o automático.";
                        }
                        finally {
                            automatic.disabled = false;
                        }
                    });
                    item.append(automatic);
                    accounts.append(item);
                }
                const disableAutomatic = document.createElement("button");
                disableAutomatic.textContent = "Desativar automático neste site";
                disableAutomatic.addEventListener("click", async () => {
                    try {
                        const result = await chrome.runtime.sendMessage({command: "SET_AUTOFILL", origin: matches.origin});
                        statusLabel.textContent = result?.ok ? "Automático desativado neste site." : "Não foi possível desativar.";
                    }
                    catch {
                        statusLabel.textContent = "Não foi possível desativar.";
                    }
                });
                const disableItem = document.createElement("li");
                disableItem.append(disableAutomatic);
                accounts.append(disableItem);
                statusLabel.textContent = matches.accounts.length ? "Contas autorizadas para este site:" : "Nenhuma conta cadastrada para este site.";
            }
        }
    }
    catch {
        clearAccounts();
        statusLabel.textContent = "Não foi possível conectar ao aplicativo.";
    }
    finally {
        refresh.disabled = false;
    }
}
refresh.addEventListener("click", () => void update());
void update();
