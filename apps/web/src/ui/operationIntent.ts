import { useState, useSyncExternalStore } from 'react';
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
 *
 * A pendência pertence à conta autenticada: trocar de conta, sair ou perder a sessão descarta todas (a idempotência do
 * servidor inclui o autor, então a chave de outra conta nunca confirmaria o envio anterior). Desde o início do envio
 * (`inFlight`) a chave e os dados ficam presos ao alvo: outro formulário do mesmo alvo reabre com o mesmo conteúdo e não
 * envia até a resposta chegar.
 */
export interface PendingOperation { fingerprint: string; key: string; locked: boolean; inFlight: boolean; body: unknown }
const pendingOperations = new Map<string, PendingOperation>();
const listeners = new Set<() => void>();
let owner: string | null = null;
let revision = 0;
function changed() { revision += 1; listeners.forEach((listener) => listener()); }
export const pendingOperation = {
  get: (slot: string): PendingOperation | null => pendingOperations.get(slot) ?? null,
  /**
   * Mesmos dados reaproveitam a chave. Com o envio travado (resultado incerto) ou ainda em andamento, a chave e os dados
   * anteriores continuam mesmo com outros dados.
   */
  keyFor(slot: string, fingerprint: string, body: unknown): string {
    const previous = pendingOperations.get(slot);
    if (previous?.locked || previous?.inFlight) {
      pendingOperations.set(slot, { ...previous, inFlight: true });
      changed();
      return previous.key;
    }
    const key = previous?.fingerprint === fingerprint ? previous.key : newOperationKey();
    pendingOperations.set(slot, { fingerprint, key, locked: false, inFlight: true, body });
    changed();
    return key;
  },
  /** A resposta chegou (sucesso, recusa ou falha): o envio deixa de estar em andamento; a trava decide o resto. */
  settle(slot: string) {
    const previous = pendingOperations.get(slot);
    if (previous?.inFlight) { pendingOperations.set(slot, { ...previous, inFlight: false }); changed(); }
  },
  lock(slot: string, locked: boolean) {
    const previous = pendingOperations.get(slot);
    if (previous) { pendingOperations.set(slot, { ...previous, locked, inFlight: false }); changed(); }
  },
  clear(slot: string) { if (pendingOperations.delete(slot)) changed(); },
  /** Saída, sessão perdida ou nova entrada: nenhuma pendência passa para a próxima conta desta aba. */
  clearAll() { owner = null; if (pendingOperations.size > 0) { pendingOperations.clear(); } changed(); },
  /** Conta autenticada atual; outra conta encontra a lista vazia. */
  bindAccount(accountId: string | null) {
    if (accountId === owner) return;
    owner = accountId;
    pendingOperations.clear();
    changed();
  },
  subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
  revision: () => revision,
};
export function usePendingOperation(slot: string) {
  useSyncExternalStore(pendingOperation.subscribe, pendingOperation.revision, pendingOperation.revision);
  const current = pendingOperation.get(slot);
  return {
    locked: current?.locked ?? false,
    /** Envio deste alvo ainda sem resposta, feito por este ou por outro formulário: não enviar de novo. */
    inFlight: current?.inFlight ?? false,
    /** Dados do envio travado ou em andamento: o formulário reabre com o mesmo conteúdo. */
    lockedBody: current?.locked || current?.inFlight ? current.body : null,
    keyFor: (fingerprint: string, body: unknown) => pendingOperation.keyFor(slot, fingerprint, body),
    /** Chamar quando a resposta chegar, em qualquer resultado (bloco finally da chamada). */
    settle() { pendingOperation.settle(slot); },
    reset() { pendingOperation.clear(slot); },
    /** Envio confirmado ou recusado pelo estado: nada fica pendente (o formulário fecha em seguida). */
    finish() { pendingOperation.clear(slot); },
    setLocked(value: boolean) { pendingOperation.lock(slot, value); },
  };
}
