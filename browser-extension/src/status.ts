export const HOST_NAME = "br.com.cofre.local";
export const NATIVE_REQUEST_TIMEOUT_MILLIS = 5000;
export const PROTOCOL_VERSION = 1;
export const statuses = ["LOCKED", "UNLOCKED", "NOT_CREATED", "APP_CLOSED", "UNAVAILABLE"] as const;
export type VaultStatus = typeof statuses[number];
export function validateStatus(response: unknown, requestId: string): VaultStatus {
    if (typeof response !== "object" || response === null)
        throw new Error("Resposta inválida");
    const value = response as Record<string, unknown>;
    if (value.version !== PROTOCOL_VERSION || value.requestId !== requestId || value.ok !== true ||
        !statuses.includes(value.status as VaultStatus))
        throw new Error("Resposta inválida");
    return value.status as VaultStatus;
}
