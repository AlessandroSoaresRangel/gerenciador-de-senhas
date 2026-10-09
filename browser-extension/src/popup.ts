export {};
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
  if (refresh.disabled) return;
  refresh.disabled = true;
  clearAccounts();
  statusLabel.textContent = "Consultando…";
  try {
    const response = await chrome.runtime.sendMessage({command: "GET_STATUS"});
    if (!response?.ok || response.status !== "UNLOCKED") {
      clearAccounts();
      statusLabel.textContent = response?.ok && labels[response.status] ? labels[response.status] :
        "Conexão indisponível. Confira a instalação do host e tente atualizar.";
    }
    if (response?.ok && response.status === "UNLOCKED") {
      const matches = await chrome.runtime.sendMessage({command: "LIST_MATCHES"});
      if (!matches?.ok) {
        clearAccounts();
        const errors: Record<string, string> = {
          HTTPS_REQUIRED: "Abra um site HTTPS para consultar as contas.",
          TAB_CHANGED: "A aba mudou. Atualize a consulta.",
          VAULT_LOCKED: "O cofre foi bloqueado. Desbloqueie no aplicativo.",
          APP_CLOSED: "Abra o aplicativo Cofre local."
        };
        statusLabel.textContent = errors[matches?.error] ?? "Não foi possível consultar as contas.";
      } else {
        clearAccounts();
        originLabel.textContent = matches.origin;
        for (const account of matches.accounts) {
          const item = document.createElement("li");
          // textContent mantém títulos e usuários como texto, mesmo se contiverem HTML.
          item.textContent = `${account.title} — ${account.username}`;
          accounts.append(item);
        }
        statusLabel.textContent = matches.accounts.length ? "Contas autorizadas para este site:" : "Nenhuma conta cadastrada para este site.";
      }
    }
  } catch {
    clearAccounts();
    statusLabel.textContent = "Não foi possível conectar ao aplicativo.";
  }
  finally { refresh.disabled = false; }
}
refresh.addEventListener("click", () => void update());
void update();
