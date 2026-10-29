import { createContext, useContext, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { ApiError } from '../../api/client';
import { Notice, errorMessage } from '../primitives';

/** Contexto já autorizado pela porta de gestão: conta, condomínio e fuso vêm do vínculo vigente. */
export interface ManagementScope { accountId: string; tenantId: string; tenantName: string; timeZone: string }
interface ManagementValue extends ManagementScope { requireMfa(): void; denied(error: unknown): void }
const ManagementContext = createContext<ManagementValue | null>(null);

export function ManagementProvider({ value, children }: { value: ManagementValue; children: ReactNode }) {
  return <ManagementContext.Provider value={value}>{children}</ManagementContext.Provider>;
}
export function useManagement(): ManagementValue {
  const value = useContext(ManagementContext);
  if (!value) throw new Error('Contexto de gestão ausente.');
  return value;
}
export const managementKey = (scope: Pick<ManagementScope, 'accountId' | 'tenantId'>, ...parts: unknown[]) =>
  ['private', scope.accountId, 'tenant', scope.tenantId, 'management', ...parts] as const;

/** Erro que pede novo segundo fator ou encerra a gestão: a tela não deve exibir dados nem tentar de novo. */
export function isAccessError(error: unknown): boolean {
  return error instanceof ApiError && ['MFA_REQUIRED', 'STAFF_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code);
}
function reactToAccessError(error: unknown, scope: ManagementValue): void {
  if (!(error instanceof ApiError)) return;
  if (error.code === 'MFA_REQUIRED') scope.requireMfa();
  else if (error.code === 'STAFF_ACCESS_DENIED' || error.code === 'TENANT_ACCESS_DENIED') scope.denied(error);
}
/**
 * Reage a erro de acesso de uma consulta depois da renderização (nunca durante o render). Devolve se o erro é de
 * acesso, para a tela não mostrar conteúdo enquanto a porta de gestão troca de estado.
 */
export function useAccessGuard(error: unknown): boolean {
  const scope = useManagement();
  const access = isAccessError(error);
  useEffect(() => { if (access) reactToAccessError(error, scope); }, [access, error, scope]);
  return access;
}

export interface ActionResult { text: string; tone: 'success' | 'warning' | 'info' }
export interface ActionState { pending: boolean; error: unknown; done: ActionResult | null }
/**
 * Uma ação por vez, sem repetição automática. Falha incerta (rede ou 5xx) pede para atualizar a lista e conferir o
 * estado antes de repetir: nem toda ação é idempotente (decisão de vínculo repetida recebe "alterado"). A mensagem
 * de resultado é derivada da resposta do servidor, não do pedido.
 */
export function useAction() {
  const scope = useManagement();
  const [state, setState] = useState<ActionState>({ pending: false, error: null, done: null });
  async function run<T>(action: () => Promise<T>, success: string | ((result: T) => ActionResult),
    after?: (result: T) => Promise<void> | void): Promise<boolean> {
    if (state.pending) return false;
    setState({ pending: true, error: null, done: null });
    try {
      const result = await action();
      await after?.(result);
      setState({ pending: false, error: null, done: typeof success === 'string' ? { text: success, tone: 'success' } : success(result) });
      return true;
    } catch (error) {
      reactToAccessError(error, scope);
      setState({ pending: false, error, done: null });
      return false;
    }
  }
  return { state, run, clear: () => setState({ pending: false, error: null, done: null }) };
}

export function ActionFeedback({ state, success = true }: { state: ActionState; success?: boolean }) {
  if (state.done) return success && state.done.text ? <Notice tone={state.done.tone}>{state.done.text}</Notice> : null;
  if (!state.error) return null;
  const uncertain = !(state.error instanceof ApiError) || state.error.uncertain || state.error.status >= 500;
  return <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(state.error)}{uncertain
    ? ' Atualize a lista e confira o estado antes de repetir.' : ''}</Notice>;
}

/** Motivo de decisão: obrigatório quando a regra exige; até 500 caracteres. */
export function ReasonForm({ id, label, required, submit, cancel, pending, confirmLabel, tone = 'primary' }: {
  id: string; label: string; required: boolean; submit(reason: string | null): void; cancel(): void; pending: boolean;
  confirmLabel: string; tone?: 'primary' | 'danger';
}) {
  const [reason, setReason] = useState('');
  function onSubmit(event: FormEvent) {
    event.preventDefault();
    const value = reason.trim();
    if (required && !value) return;
    submit(value ? value : null);
  }
  return <form className="reason-form" onSubmit={onSubmit}>
    <label htmlFor={id}>{label}{required ? '' : ' (opcional)'}</label>
    <textarea id={id} maxLength={500} required={required} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    <div className="button-row">
      <button className={`button ${tone === 'danger' ? 'button-danger' : ''}`} type="submit" disabled={pending || (required && !reason.trim())}>{pending ? 'Enviando…' : confirmLabel}</button>
      <button className="button button-secondary" type="button" onClick={cancel} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

export const roleLabels: Record<string, string> = {
  RESIDENT: 'Morador', RESPONSIBLE: 'Responsável', DEPENDENT: 'Dependente', OWNER: 'Proprietário',
  CONCIERGE: 'Portaria', MANAGER: 'Gestão', SUPPORT: 'Suporte',
};
export const membershipStatusLabels: Record<string, string> = { PENDING: 'Pendente', ACTIVE: 'Ativo', SUSPENDED: 'Suspenso', REVOKED: 'Revogado' };
export const reservationStatusLabels: Record<string, string> = {
  HELD: 'Em retenção', PENDING_APPROVAL: 'Aguardando decisão', CONFIRMED: 'Confirmada', REJECTED: 'Não aprovada',
  CANCELLED: 'Cancelada', EXPIRED: 'Expirada',
};
export function statusTone(status: string): string {
  if (status === 'CONFIRMED' || status === 'ACTIVE' || status === 'ACCEPTED') return 'status-ready';
  if (status === 'PENDING_APPROVAL' || status === 'PENDING' || status === 'HELD') return 'status-pending';
  return 'status-neutral';
}
