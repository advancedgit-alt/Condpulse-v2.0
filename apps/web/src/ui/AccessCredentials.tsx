import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { accessCredentials, type Credential, type CredentialKind, type CredentialStatus } from '../api/accessCredentials';
import { management } from '../api/management';
import { displayText } from '../api/text';
import { Empty, ErrorState, Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { codeOf, repeatFailure, settleFailure, uncertain, usePendingOperation } from './operationIntent';
import { ActionFeedback, ReasonForm, useAccessGuard, useAction, useManagement, managementKey, type ActionResult } from './management/shared';
import { conciergeKey } from './concierge/shared';
import './management/Management.css';

/**
 * Credenciais de acesso (CP-046). A gestão cadastra cartão ou QR para um morador e revoga; a revogação vale na hora no
 * CondPulse, mas o equipamento só deixa de aceitar quando confirmar. Enquanto houver equipamento pendente, a tela mostra
 * a pendência e nunca afirma que o acesso está bloqueado.
 */
export const credentialStatusLabels: Record<CredentialStatus, [string, string]> = {
  ACTIVE: ['Ativa em todos os equipamentos', 'status-ready'], SYNCING: ['Enviando aos equipamentos', 'status-pending'],
  PARTIAL: ['Ativa em parte dos equipamentos', 'status-pending'], FAILED: ['Recusada por equipamento', 'status-overdue'],
  EXPIRED: ['Vínculo vencido', 'status-neutral'],
  REVOKING: ['Revogação pendente nos equipamentos', 'status-overdue'], REVOKED: ['Revogada em todos os equipamentos', 'status-neutral'],
  NO_DEVICES: ['Sem equipamento na integração', 'status-neutral'],
};
const kindLabels: Record<CredentialKind, string> = { CARD: 'Cartão', QR: 'QR' };
const deviceStateText = (item: Credential, state: 'CONFIRMED' | 'PENDING' | 'FAILED') =>
  state === 'CONFIRMED' ? (item.desiredState === 'REVOKED' ? 'revogação confirmada' : 'ativa')
    : state === 'FAILED' ? 'recusada pelo equipamento' : (item.desiredState === 'REVOKED' ? 'revogação pendente' : 'aguardando');
const hidden = (error: unknown) => codeOf(error) === 'API_NOT_AVAILABLE';

function CredentialDetails({ item, timeZone }: { item: Credential; timeZone: string }) {
  const [label, tone] = credentialStatusLabels[item.status];
  return <div><strong>{kindLabels[item.kind]} · <bdi>{item.label}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
    <span>Unidade <bdi>{item.unitLabel}</bdi>{item.holderName ? <> · <bdi>{item.holderName}</bdi></> : ' · vínculo encerrado'}</span>
    {item.validUntil && item.desiredState === 'ACTIVE' ? <span>Válida até {formatWhen(item.validUntil, timeZone)} (validade do vínculo)</span> : null}
    {item.revokedAt ? <span>Revogada em {formatWhen(item.revokedAt, timeZone)}: <bdi>{item.revokeReason}</bdi></span> : null}
    {item.devices.length ? <ul className="credential-devices" aria-label={`Equipamentos de ${item.label}`}>{item.devices.map((entry) =>
      <li key={entry.deviceId}><bdi>{entry.deviceLabel}</bdi>: {deviceStateText(item, entry.state)}
        {entry.state === 'CONFIRMED' && entry.confirmedAt ? ` em ${formatWhen(entry.confirmedAt, timeZone)}` : ''}</li>)}</ul> : null}
  </div>;
}

/** Gestão: integrações, cadastro de credencial para morador e revogação com motivo. */
export function CredentialManagementSection() {
  const scope = useManagement();
  const [pendingOnly, setPendingOnly] = useState(false);
  const connectors = useQuery({ queryKey: managementKey(scope, 'access-connectors'), queryFn: ({ signal }) => accessCredentials.connectors(scope.tenantId, signal) });
  const list = useQuery({ queryKey: managementKey(scope, 'access-credentials', pendingOnly),
    queryFn: ({ signal }) => accessCredentials.managed(scope.tenantId, pendingOnly, signal), enabled: !hidden(connectors.error) });
  const [creating, setCreating] = useState(false);
  const [outcome, setOutcome] = useState<ActionResult | null>(null);
  const [revoking, setRevoking] = useState<Credential | null>(null);
  const guarded = useAccessGuard(connectors.error ?? list.error);
  if (guarded) return null;
  const active = (connectors.data ?? []).filter((item) => item.status === 'ACTIVE');
  return <section className="management-section" aria-labelledby="credentials-title">
    <div className="section-heading"><div><span className="section-index">ACESSO</span><h2 id="credentials-title">Credenciais de acesso</h2></div>
      {!hidden(connectors.error) ? <div className="button-row">
        <label className="sr-only" htmlFor="credentials-filter">Filtrar credenciais</label>
        <select id="credentials-filter" value={pendingOnly ? 'pending' : 'all'} onChange={(event) => { setRevoking(null); setPendingOnly(event.target.value === 'pending'); }}>
          <option value="all">Todas</option><option value="pending">Aguardando equipamento</option>
        </select>
        <button className="button button-secondary button-compact" type="button" disabled={list.isFetching || connectors.isFetching}
          onClick={() => { void connectors.refetch(); void list.refetch(); }}>Atualizar</button>
      </div> : null}</div>
    <p className="field-help">Revogar vale na hora no CondPulse; o equipamento só deixa de aceitar quando confirmar. Enquanto houver revogação pendente, siga a contingência local. Encerrar o vínculo de um morador revoga as credenciais dele.</p>
    {hidden(connectors.error) ? <div className="panel"><Empty title="Credenciais indisponíveis">As credenciais de acesso ainda não estão ativas neste ambiente.</Empty></div>
      : connectors.isPending ? <div className="panel"><Loading label="Consultando integrações de acesso…" /></div>
        : connectors.isError && !connectors.data ? <ErrorState error={connectors.error} retry={() => void connectors.refetch()} />
          : <>
            {active.length === 0 ? <Notice tone="info">Nenhuma integração de acesso ativa. O cadastro de credenciais depende do parceiro físico contratado e configurado pela operação da plataforma.</Notice>
              : <p className="field-help">Integração: {active.map((item) => `${item.label} (${item.devices.filter((entry) => entry.status === 'ACTIVE').length} equipamentos)`).join('; ')}.</p>}
            {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
            {list.isError && list.data && !list.isFetching ? <Notice tone="warning">{`Não foi possível atualizar as credenciais (${errorMessage(list.error)}). A lista abaixo é da última consulta e pode estar desatualizada.`}</Notice> : null}
            {active.length > 0 && !creating ? <div className="button-row"><button className="button button-compact" type="button"
              onClick={() => { setOutcome(null); setCreating(true); }}>Cadastrar credencial</button></div> : null}
            {creating ? <CredentialForm connectors={active.map((item) => ({ id: item.connectorId, label: item.label }))}
              close={(result) => { setCreating(false); if (result) setOutcome(result); }} /> : null}
            {list.isPending ? <div className="panel"><Loading label="Consultando credenciais…" /></div>
              : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
                : !list.data || list.data.credentials.length === 0 ? <div className="panel"><Empty title="Nenhuma credencial">{pendingOnly ? 'Nenhuma credencial aguardando equipamento.' : 'Nenhuma credencial cadastrada.'}</Empty></div>
                  : <ol className="management-list" aria-label="Credenciais de acesso">{list.data.credentials.map((item) =>
                    <li key={item.credentialId} className="management-row">
                      <CredentialDetails item={item} timeZone={scope.timeZone} />
                      {revoking?.credentialId === item.credentialId ? <RevokeCredential item={revoking} close={(result) => { setRevoking(null); if (result) setOutcome(result); }} />
                        : item.desiredState === 'ACTIVE' ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
                          onClick={() => { setOutcome(null); setRevoking(item); }}>Revogar</button></div> : null}
                    </li>)}</ol>}
            {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 primeiras credenciais deste filtro.</Notice> : null}
          </>}
  </section>;
}

/** Revogação pela versão exibida ao abrir a confirmação, com motivo. */
function RevokeCredential({ item, close }: { item: Credential; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const run = useAction();
  async function submit(reason: string | null) {
    if (!reason) return;
    const ok = await run.run(() => accessCredentials.revoke(scope.tenantId, item.credentialId, { reason, expectedVersion: item.version }),
      'Credencial revogada.', () => queries.invalidateQueries({ queryKey: managementKey(scope, 'access-credentials') }));
    if (ok) close({ tone: 'warning', text: `Credencial ${item.label} revogada no CondPulse. Os equipamentos aparecem como pendentes até confirmarem; até lá, siga a contingência local.` });
    else void queries.invalidateQueries({ queryKey: managementKey(scope, 'access-credentials') });
  }
  return <div>
    <ReasonForm id={`credential-revoke-${item.credentialId}`} label="Motivo da revogação" required pending={run.state.pending}
      confirmLabel="Confirmar revogação" tone="danger" submit={(reason) => void submit(reason)} cancel={() => close(null)} />
    <ActionFeedback state={run.state} success={false} />
  </div>;
}

interface CredentialDraft { connectorId: string; membershipId: string; kind: CredentialKind; label: string }
/** Cadastro com chave de operação pendente: fechar e reabrir depois de resultado incerto não cria segunda credencial. */
function CredentialForm({ connectors, close }: { connectors: { id: string; label: string }[]; close(result: ActionResult | null): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const intent = usePendingOperation(`${scope.tenantId}:access-credential`);
  const pending = intent.lockedBody as CredentialDraft | null;
  const people = useQuery({ queryKey: managementKey(scope, 'memberships', 'ACTIVE'), queryFn: ({ signal }) => management.memberships(scope.tenantId, 'ACTIVE', signal) });
  const [draft, setDraft] = useState<CredentialDraft>(pending ?? { connectorId: connectors[0]?.id ?? '', membershipId: '', kind: 'CARD', label: '' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const residents = (people.data?.memberships ?? []).filter((item) => item.unitId !== null && ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT'].includes(item.role));
  const label = displayText(draft.label, 80);
  const locked = intent.locked;
  const disabled = busy || locked || intent.inFlight;
  const valid = locked || (draft.connectorId !== '' && draft.membershipId !== '' && label !== null);
  const set = (change: Partial<CredentialDraft>) => setDraft((value) => ({ ...value, ...change }));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || busy || intent.inFlight) return;
    const body = pending ? { connectorId: pending.connectorId, membershipId: pending.membershipId, kind: pending.kind, label: displayText(pending.label, 80)! }
      : { connectorId: draft.connectorId, membershipId: draft.membershipId, kind: draft.kind, label: label! };
    const key = intent.keyFor(JSON.stringify(body), pending ?? draft);
    setBusy(true); setFailure(null);
    try {
      const result = await accessCredentials.create(scope.tenantId, key, body);
      intent.finish();
      await queries.invalidateQueries({ queryKey: managementKey(scope, 'access-credentials') });
      close(result.replayed ? { tone: 'info', text: 'Esta credencial já estava cadastrada.' }
        : { tone: 'success', text: `Credencial ${result.credential.label} cadastrada para ${result.credential.holderName ?? 'o morador'}. Os equipamentos recebem pela integração.` });
    } catch (error) {
      setFailure(repeatFailure(error));
      settleFailure(error, intent.locked, intent, intent.setLocked);
      void queries.invalidateQueries({ queryKey: managementKey(scope, 'access-credentials') });
    } finally { intent.settle(); setBusy(false); }
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Cadastrar credencial">
    {connectors.length > 1 ? <><label htmlFor="credential-connector">Integração</label>
      <select id="credential-connector" value={draft.connectorId} disabled={disabled} onChange={(event) => set({ connectorId: event.target.value })}>
        {connectors.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select></> : null}
    <label htmlFor="credential-holder">Morador</label>
    {people.isPending ? <Loading label="Consultando moradores…" />
      : people.isError && !people.data ? <ErrorState error={people.error} retry={() => void people.refetch()} />
        : <select id="credential-holder" value={draft.membershipId} disabled={disabled} onChange={(event) => set({ membershipId: event.target.value })}>
          <option value="">Escolha o morador</option>
          {residents.map((item) => <option key={item.membershipId} value={item.membershipId}>{`${item.displayName} — unidade ${item.unitLabel ?? ''}`}</option>)}
        </select>}
    {people.data && residents.length === 0 ? <span className="field-help">Nenhum morador com vínculo ativo.</span> : null}
    <label htmlFor="credential-kind">Tipo</label>
    <select id="credential-kind" value={draft.kind} disabled={disabled} onChange={(event) => set({ kind: event.target.value as CredentialKind })}>
      <option value="CARD">Cartão</option><option value="QR">QR</option>
    </select>
    <label htmlFor="credential-label">Descrição</label>
    <input id="credential-label" maxLength={80} autoComplete="off" value={draft.label} disabled={disabled} onChange={(event) => set({ label: event.target.value })} />
    <span className="field-help">Ex.: “Cartão final 1234”. Não informe o número completo do cartão.</span>
    {failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    {locked ? <Notice tone="warning">O resultado não foi confirmado e o cadastro ficou travado, mesmo se você fechar o formulário. Use “Tentar confirmar o mesmo cadastro”: se ele já foi feito, nada novo é gravado.</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || busy || intent.inFlight}>{busy ? 'Enviando…' : locked ? 'Tentar confirmar o mesmo cadastro' : 'Cadastrar'}</button>
      <button className="button button-secondary" type="button" disabled={busy} onClick={() => close(null)}>Voltar</button>
    </div>
  </form>;
}

/** Portaria: revogações ainda não confirmadas nos equipamentos, para orientar a contingência local. */
export function ConciergePendingRevocations() {
  const scope = useManagement();
  const list = useQuery({ queryKey: conciergeKey(scope, 'pending-revocations'), queryFn: ({ signal }) => accessCredentials.pendingRevocations(scope.tenantId, signal),
    refetchInterval: (query) => hidden(query.state.error) ? false : 60_000 });
  if (useAccessGuard(list.error)) return null;
  if (hidden(list.error)) return null;
  // Atualização falhou e a tela ainda mostra a consulta anterior: nunca afirmar que tudo foi confirmado.
  const stale = list.isError && list.data !== undefined && !list.isFetching;
  return <section className="management-section" aria-labelledby="pending-revocations-title">
    <div className="section-heading"><div><span className="section-index">ACESSO</span><h2 id="pending-revocations-title">Revogações pendentes</h2></div>
      <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar</button></div></div>
    <p className="field-help">Credenciais revogadas que algum equipamento ainda não confirmou. Não trate como bloqueadas: siga a contingência local e acione o integrador se a pendência continuar.</p>
    {stale ? <Notice tone="warning">{`Não foi possível atualizar as revogações pendentes (${errorMessage(list.error)}). A lista abaixo é da última consulta e pode não mostrar revogações novas: siga a contingência local até conseguir atualizar.`}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando revogações pendentes…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.credentials.length === 0 ? <div className="panel">{stale
          ? <Empty title="Sem confirmação atual">A última consulta não tinha revogações pendentes, mas a atualização falhou.</Empty>
          : <Empty title="Nenhuma revogação pendente">Todas as revogações foram confirmadas pelos equipamentos.</Empty>}</div>
          : <ol className="management-list" aria-label="Revogações pendentes">{list.data.credentials.map((item) =>
            <li key={item.credentialId} className="management-row"><CredentialDetails item={item} timeZone={scope.timeZone} /></li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra as 100 revogações pendentes mais antigas.</Notice> : null}
  </section>;
}
