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

/**
 * Depois de uma falha com chave de operação. Resultado incerto trava o formulário na mesma chave. Chave vencida ou
 * recusada só dá lugar a uma nova quando nada estava pendente: com o formulário travado a chave antiga continua, e a
 * repetição recebe o mesmo erro em vez de criar um segundo registro (a pessoa confere a lista e começa outro).
 * Recusa de validação ou de regra (400/409) na repetição prova que a tentativa anterior não gravou — a repetição com a
 * mesma chave teria devolvido o registro — e destrava; erros de acesso, sessão ou segurança mantêm a trava.
 */
export function settleFailure(failure: unknown, locked: boolean, intent: { reset(): void }, setLocked: (value: boolean) => void): void {
  const code = codeOf(failure);
  if (freshKeyCodes.includes(code)) { if (!locked) intent.reset(); return; }
  if (uncertain(failure)) { setLocked(true); return; }
  if (failure instanceof ApiError && (failure.status === 400 || (failure.status === 409 && code !== 'REQUEST_IN_PROGRESS'))) setLocked(false);
}

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

/**
 * Operação pendente por alvo (por exemplo, "entrada pelo convite X"): chave, dados e trava ficam fora do formulário.
 * Fechar e reabrir o formulário — Voltar, troca de filtro ou de unidade, outra ação aberta na lista — não gera chave nova
 * depois de um resultado incerto, e a repetição confirma o mesmo envio. Vale enquanto a página estiver aberta:
 * recarregar a página descarta a pendência, e a lista atualizada continua sendo a referência antes de repetir.
 */
export interface PendingOperation { fingerprint: string; key: string; locked: boolean; body: unknown }
const pendingOperations = new Map<string, PendingOperation>();
export const pendingOperation = {
  get: (slot: string): PendingOperation | null => pendingOperations.get(slot) ?? null,
  /** Mesmos dados reaproveitam a chave; com o envio travado a chave antiga continua mesmo com outros dados. */
  keyFor(slot: string, fingerprint: string, body: unknown): string {
    const previous = pendingOperations.get(slot);
    if (previous?.locked) return previous.key;
    const key = previous?.fingerprint === fingerprint ? previous.key : newOperationKey();
    pendingOperations.set(slot, { fingerprint, key, locked: false, body });
    return key;
  },
  lock(slot: string, locked: boolean) {
    const previous = pendingOperations.get(slot);
    if (previous) pendingOperations.set(slot, { ...previous, locked });
  },
  clear(slot: string) { pendingOperations.delete(slot); },
};
export function usePendingOperation(slot: string) {
  const [, setVersion] = useState(0);
  const changed = () => setVersion((value) => value + 1);
  const current = pendingOperation.get(slot);
  return {
    locked: current?.locked ?? false,
    /** Dados do envio travado: o formulário reabre com o mesmo conteúdo. */
    lockedBody: current?.locked ? current.body : null,
    keyFor: (fingerprint: string, body: unknown) => pendingOperation.keyFor(slot, fingerprint, body),
    reset() { pendingOperation.clear(slot); changed(); },
    /** Envio confirmado ou recusado pelo estado: nada fica pendente (o formulário fecha em seguida). */
    finish() { pendingOperation.clear(slot); },
    setLocked(value: boolean) { pendingOperation.lock(slot, value); changed(); },
  };
}
