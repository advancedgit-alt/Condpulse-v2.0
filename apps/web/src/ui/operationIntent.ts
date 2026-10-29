import { useState } from 'react';
import { ApiError, newOperationKey } from '../api/client';

/** Mensagens de repetição neutras (o texto geral fala da agenda de reservas): servem à portaria e aos acessos da unidade. */
export function repeatFailure(error: unknown): unknown {
  if (!(error instanceof ApiError)) return error;
  const text = error.code === 'IDEMPOTENCY_KEY_REUSED' ? 'Este envio já foi feito com outros dados. Atualize a lista e confira antes de registrar de novo.'
    : error.code === 'IDEMPOTENCY_KEY_EXPIRED' ? 'O prazo para repetir este envio terminou. Atualize a lista e confira antes de registrar de novo.'
      : error.code === 'REQUEST_IN_PROGRESS' ? 'O envio anterior ainda está em processamento. Aguarde e atualize a lista antes de repetir.' : null;
  return text ? new ApiError(error.status, error.code, text, error.uncertain) : error;
}
/** Resultado incerto (rede ou 5xx): conferir a lista antes de repetir. */
export const uncertain = (error: unknown) => !(error instanceof ApiError) || error.uncertain || error.status >= 500;
export const codeOf = (error: unknown) => error instanceof ApiError ? error.code : '';
/** Chave nova depois de prazo vencido ou dados diferentes: a anterior nunca mais confirma esta intenção. */
export const freshKeyCodes = ['IDEMPOTENCY_KEY_EXPIRED', 'IDEMPOTENCY_KEY_REUSED'];

/** Mesma intenção reaproveita a chave: repetir depois de resultado incerto não cria segundo registro. */
export function useIntent() {
  const [intent, setIntent] = useState<{ fingerprint: string; key: string } | null>(null);
  return {
    keyFor(fingerprint: string): string {
      const key = intent?.fingerprint === fingerprint ? intent.key : newOperationKey();
      setIntent({ fingerprint, key });
      return key;
    },
    reset: () => setIntent(null),
  };
}
