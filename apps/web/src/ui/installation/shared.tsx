import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import type { TechnicalScope, TechnicalSession } from '../../api/accessInstall';
import { formatWhen } from '../dateTime';
import { Notice, errorMessage } from '../primitives';

/**
 * Horários da implantação no fuso de operação do projeto: a sessão técnica não traz o fuso do condomínio e cada área
 * mostra o próprio fuso no cadastro.
 */
export const INSTALL_ZONE = 'America/Sao_Paulo';

/** Contexto já autorizado pela porta da implantação: conta, condomínio e escopos vigentes da concessão técnica. */
export interface InstallationValue {
  accountId: string; tenantId: string; session: TechnicalSession;
  requireMfa(): void; denied(error: unknown): void; refreshSession(): void;
}
export const InstallationContext = createContext<InstallationValue | null>(null);
/** Resultado de uma ação concluída, mostrado acima da seção: o formulário fecha ao concluir e levaria a mensagem junto. */
export const OutcomeContext = createContext<((text: string) => void) | null>(null);
export function useInstallation(): InstallationValue {
  const value = useContext(InstallationContext);
  if (!value) throw new Error('Contexto da implantação ausente.');
  return value;
}
export const installationKey = (scope: Pick<InstallationValue, 'accountId' | 'tenantId'>, ...parts: unknown[]) =>
  ['private', scope.accountId, 'tenant', scope.tenantId, 'installation', ...parts] as const;
export const can = (session: TechnicalSession, scope: TechnicalScope) => session.scopes.includes(scope);

function codeOf(error: unknown): string { return error instanceof ApiError ? error.code : ''; }
/** Erro que muda a porta: segundo fator, concessão encerrada (dados somem) ou escopo alterado (sessão consultada de novo). */
export function isAccessError(error: unknown): boolean {
  return ['MFA_REQUIRED', 'TENANT_ACCESS_DENIED'].includes(codeOf(error));
}
function react(error: unknown, scope: InstallationValue): void {
  const code = codeOf(error);
  if (code === 'MFA_REQUIRED') scope.requireMfa();
  else if (code === 'TENANT_ACCESS_DENIED') scope.denied(error);
  else if (code === 'TECHNICAL_SCOPE_REQUIRED') scope.refreshSession();
}
export function useAccessGuard(error: unknown): boolean {
  const scope = useInstallation();
  const access = isAccessError(error);
  const code = codeOf(error);
  useEffect(() => { if (access || code === 'TECHNICAL_SCOPE_REQUIRED') react(error, scope); }, [access, code, error, scope]);
  return access;
}

export interface ActionResult { text: string; tone: 'success' | 'warning' | 'info' }
export interface ActionState { pending: boolean; error: unknown; done: ActionResult | null }
/** Uma ação por vez, sem repetição automática; resultado incerto pede para atualizar e conferir antes de repetir. */
export function useAction() {
  const scope = useInstallation();
  const announce = useContext(OutcomeContext);
  const [state, setState] = useState<ActionState>({ pending: false, error: null, done: null });
  async function run<T>(action: () => Promise<T>, success: string, after?: (result: T) => Promise<void> | void): Promise<boolean> {
    if (state.pending) return false;
    setState({ pending: true, error: null, done: null });
    announce?.('');
    try {
      const result = await action();
      await after?.(result);
      setState({ pending: false, error: null, done: announce ? null : { text: success, tone: 'success' } });
      announce?.(success);
      return true;
    } catch (error) {
      react(error, scope);
      setState({ pending: false, error, done: null });
      return false;
    }
  }
  return { state, run, clear: () => setState({ pending: false, error: null, done: null }) };
}
export const isUncertain = (error: unknown) => !(error instanceof ApiError) || error.uncertain || error.status >= 500;
export function ActionFeedback({ state }: { state: ActionState }) {
  if (state.done) return <Notice tone={state.done.tone}>{state.done.text}</Notice>;
  if (!state.error) return null;
  const uncertain = isUncertain(state.error);
  return <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(state.error)}{uncertain
    ? ' Atualize a lista e confira o estado antes de repetir; o mesmo cadastro pode ser reenviado sem duplicar.' : ''}</Notice>;
}

/**
 * Ação física (vínculo, envio ao comissionamento, assinatura técnica, aprovação): mostra o local real e o contexto
 * autorizado e só libera a confirmação depois de a pessoa declarar que conferiu os dois.
 */
export function PhysicalContext({ place, confirmed, onConfirm, disabled }: {
  place: ReactNode; confirmed: boolean; onConfirm(value: boolean): void; disabled?: boolean;
}) {
  const scope = useInstallation();
  return <div className="install-context" role="group" aria-label="Local e contexto da ação">
    <dl className="install-facts">
      <div><dt>Local</dt><dd>{place}</dd></div>
      <div><dt>Condomínio</dt><dd><code>{scope.tenantId}</code></dd></div>
      <div><dt>Concessão técnica</dt><dd>vigente até {formatWhen(scope.session.validUntil, INSTALL_ZONE)}</dd></div>
    </dl>
    <label className="checkbox-label"><input type="checkbox" checked={confirmed} disabled={disabled} onChange={(event) => onConfirm(event.target.checked)} />
      <span>Conferi no local o ponto físico e o condomínio acima</span></label>
  </div>;
}

export function newId(): string { return globalThis.crypto.randomUUID(); }
export const short = (id: string) => id.slice(0, 8);
export const blank = (value: string) => { const trimmed = value.trim(); return trimmed ? trimmed : null; };
