// O contrato BFF usa delta-seconds inteiros entre 1 e 900. Nunca refletir o header.
export const DEFAULT_RETRY_AFTER_SECONDS = 60;
export const MAX_RETRY_AFTER_SECONDS = 900;

export function parseRetryAfterSeconds(value: string | null): number {
  if (value === null || !/^(0|[1-9]\d{0,2})$/.test(value)) return DEFAULT_RETRY_AFTER_SECONDS;
  const seconds = Number(value);
  return seconds <= MAX_RETRY_AFTER_SECONDS ? Math.max(1, seconds) : DEFAULT_RETRY_AFTER_SECONDS;
}

export function remainingCooldown(deadline: number | undefined, now = globalThis.performance.now()): number {
  return deadline === undefined || !Number.isFinite(deadline) ? 0 : Math.max(0, Math.ceil((deadline - now) / 1_000));
}
