/** Script isolado, sem imports: os content scripts declarados no manifesto não são módulos. */
function captureSubmittedLogin(event: Event) {
    const MAX_USERNAME_LENGTH_CHARACTERS = 512;
    const MAX_PASSWORD_LENGTH_CHARACTERS = 4096;
    if (!event.isTrusted || window.top !== window || location.protocol !== "https:"
            || !(event.target instanceof HTMLFormElement))
        return;
    const form = event.target;
    const action = new URL(form.action || location.href, location.href);
    if (action.origin !== location.origin || action.protocol !== "https:")
        return;
    function visible(input: HTMLInputElement) {
        if (!input.isConnected || input.disabled || input.readOnly || input.getClientRects().length === 0)
            return false;
        for (let element: Element | null = input; element; element = element.parentElement) {
            const style = getComputedStyle(element);
            if (style.display === "none" || style.visibility === "hidden" || style.opacity === "0")
                return false;
        }
        return true;
    }
    const inputs = Array.from(form.querySelectorAll<HTMLInputElement>("input"));
    const passwords = inputs.filter(input => input.type === "password");
    if (passwords.length !== 1 || !visible(passwords[0])
            || passwords[0].autocomplete.split(/\s+/).includes("new-password"))
        return;
    const usernames = inputs.filter(input => ["text", "email", "tel"].includes(input.type) && visible(input));
    const explicit = usernames.filter(input => input.autocomplete.split(/\s+/)
        .some(token => ["username", "email"].includes(token)));
    const eligible = explicit.length ? explicit : usernames;
    if (eligible.length !== 1 || !eligible[0].value.trim() || !passwords[0].value
            || eligible[0].value.length > MAX_USERNAME_LENGTH_CHARACTERS
            || passwords[0].value.length > MAX_PASSWORD_LENGTH_CHARACTERS)
        return;
    const request = {command: "CAPTURE_LOGIN", username: eligible[0].value.trim(), password: passwords[0].value};
    // Não impede o submit e não altera os campos. Nenhuma senha é enviada ao código da página.
    const pending = chrome.runtime.sendMessage(request);
    request.password = "";
    void pending.catch(() => {});
}
document.addEventListener("submit", captureSubmittedLogin, true);
