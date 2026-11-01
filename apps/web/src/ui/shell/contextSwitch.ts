import type { PartnerRole } from '../../api/types';
import type { Place } from './paths';

/**
 * Troca de contexto (R4.4, PRD complementar §4.2). Contexto é o "lugar" em que a conta trabalha: um condomínio (por
 * vínculo), a central ou o painel de um papel de parceiro. A /entrada não é contexto: só escolhe o próximo.
 *
 * 1. Encerrar a visão anterior: ao mudar de contexto, a casca desmonta a tela, apaga do cache tudo o que é privado da
 *    conta (exceto a decisão da entrada e o estado do segundo fator) e só então monta o contexto novo
 *    ({@link contextKeyOf}, {@link isPreviousContextData}).
 * 2. Registrar: ao entrar num painel de parceiro, a web chama POST /v1/partners/{id}/context-switches com a origem e uma
 *    chave da troca; o servidor grava CONTEXT_SWITCHED para papéis de segurança. A chave fica a mesma enquanto a mesma
 *    troca não termina (remontagem, StrictMode, nova tentativa), e o servidor não grava duas vezes a mesma chave.
 *    Voltar ao mesmo painel sem ter passado por outro contexto não é troca e não chama o servidor.
 *
 * O rastreio vive só na memória desta janela: recarregar a página começa de novo com origem ENTRY.
 */
export type ContextRef =
  | { kind: 'ENTRY' }
  | { kind: 'PLATFORM' }
  | { kind: 'TENANT'; tenantId: string }
  | { kind: 'PARTNER'; partnerId: string; role: PartnerRole };

/** Chave da visão em que a casca está; null na /entrada (não encerra nem abre contexto). */
export function contextKeyOf(place: Place): string | null {
  if (place.world === 'condo') return `c:${place.tenantId.toLowerCase()}`;
  if (place.world === 'partner') return `p:${place.partnerId.toLowerCase()}:${place.panel}`;
  if (place.world === 'central') return 'central';
  return null;
}

/** Contexto equivalente de uma chave de condomínio ou da central (o de parceiro é registrado pelo próprio painel). */
export function contextRefOfKey(key: string): ContextRef | null {
  if (key === 'central') return { kind: 'PLATFORM' };
  if (key.startsWith('c:')) return { kind: 'TENANT', tenantId: key.slice(2) };
  return null;
}

/**
 * Dados privados da visão anterior: tudo em ['private', conta, ...] exceto a decisão da entrada (papel de plataforma) e o
 * estado do segundo fator, que são da conta e não de um contexto.
 */
export function isPreviousContextData(queryKey: readonly unknown[]): boolean {
  return queryKey[0] === 'private' && queryKey[2] !== 'entry' && queryKey[2] !== 'mfa-status';
}

export const sameContext = (a: ContextRef, b: ContextRef) => JSON.stringify(normalize(a)) === JSON.stringify(normalize(b));
function normalize(context: ContextRef): ContextRef {
  if (context.kind === 'TENANT') return { kind: 'TENANT', tenantId: context.tenantId.toLowerCase() };
  if (context.kind === 'PARTNER') return { kind: 'PARTNER', partnerId: context.partnerId.toLowerCase(), role: context.role };
  return context;
}

/** Troca pendente de registro: chave estável e origem fixada no início da troca. */
export interface PlannedSwitch { switchId: string; from: ContextRef; to: ContextRef }

interface TrackerState { accountId: string | null; current: ContextRef | null; pending: PlannedSwitch | null }
const state: TrackerState = { accountId: null, current: null, pending: null };
function forAccount(accountId: string) {
  if (state.accountId !== accountId) { state.accountId = accountId; state.current = null; state.pending = null; }
}

/**
 * Contexto atual desta janela (condomínio ou central). Sair de um painel de parceiro direto para um condomínio por vínculo
 * ou para a central devolve a saída a registrar (lado da origem, R4.4), com chave nova; senão null.
 */
export function noteContext(accountId: string, context: ContextRef, newId: () => string = () => globalThis.crypto.randomUUID()): PlannedSwitch | null {
  forAccount(accountId);
  if (state.pending && !sameContext(state.pending.to, context)) state.pending = null;
  const previous = state.current;
  state.current = context;
  if (previous?.kind === 'PARTNER' && (context.kind === 'TENANT' || context.kind === 'PLATFORM')) return { switchId: newId(), from: previous, to: context };
  return null;
}

/**
 * Registra a saída de um painel de parceiro (POST /v1/partners/{id}/context-exits): o servidor grava CONTEXT_SWITCHED do
 * lado da origem para papéis de segurança. Não bloqueia o contexto novo (que é de vínculo ou da central, com a própria
 * autorização); até três tentativas com a mesma chave quando o servidor está indisponível (o servidor não grava duas
 * vezes a mesma chave). Recusa ou rota inexistente (flag desligada) encerram sem nova tentativa. Devolve se foi aceita.
 */
export async function recordExit(planned: PlannedSwitch, send: (planned: PlannedSwitch) => Promise<unknown>,
  wait: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms)), attempts = 3): Promise<boolean> {
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try { await send(planned); return true; }
    catch (error) {
      const status = (error as { status?: unknown }).status;
      const retryable = typeof status === 'number' && (status === 0 || status >= 500);
      if (!retryable || attempt === attempts) return false;
      await wait(500 * attempt);
    }
  }
  return false;
}

/**
 * Troca para o painel to: null quando já é o contexto atual (sem troca, sem chamada). A mesma troca pendente devolve a
 * mesma chave e a mesma origem até {@link completeSwitch}.
 */
export function planSwitch(accountId: string, to: ContextRef, newId: () => string = () => globalThis.crypto.randomUUID()): PlannedSwitch | null {
  forAccount(accountId);
  if (state.pending && sameContext(state.pending.to, to)) return state.pending;
  if (state.current && sameContext(state.current, to)) return null;
  state.pending = { switchId: newId(), from: state.current ?? { kind: 'ENTRY' }, to };
  return state.pending;
}

/** Troca registrada (ou sem registro exigido): o painel passa a ser o contexto atual. */
export function completeSwitch(accountId: string, planned: PlannedSwitch): void {
  forAccount(accountId);
  if (state.pending?.switchId === planned.switchId) state.pending = null;
  state.current = planned.to;
}

/** Só para testes e para a saída da conta. */
export function resetContextTracker(): void { state.accountId = null; state.current = null; state.pending = null; }
