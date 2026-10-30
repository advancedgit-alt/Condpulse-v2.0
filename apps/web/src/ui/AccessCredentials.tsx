import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { accessCredentials, type ConnectorHealth, type Credential, type CredentialKind, type CredentialStatus, type DeviceEvent, type DeviceHealth } from '../api/accessCredentials';
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

/** Descrição do evento sem inferir nada além do que o equipamento informou (CP-049). */
export function describeDeviceEvent(event: DeviceEvent): string {
  switch (event.kind) {
    case 'AUTHORIZED': return 'Acesso autorizado pelo equipamento';
    case 'DENIED': return 'Acesso negado pelo equipamento';
    case 'RELAY_TRIGGERED': return 'Abertura acionada';
    case 'DOOR_OPENED': return 'Porta aberta';
    case 'PASSAGE_DETECTED': return event.direction === 'ENTRY' ? 'Passagem de entrada' : event.direction === 'EXIT' ? 'Passagem de saída'
      : 'Passagem sem direção registrada';
    case 'CREDENTIAL_APPLIED': return 'Credencial aplicada no equipamento';
    case 'CREDENTIAL_REJECTED': return 'Credencial recusada pelo equipamento';
    case 'CREDENTIAL_REVOKED': return 'Credencial removida do equipamento';
    case 'DEVICE_ONLINE': return 'Equipamento voltou a responder';
    case 'DEVICE_OFFLINE': return 'Equipamento sem resposta';
  }
}

/** Unidade: as próprias credenciais e o histórico dos equipamentos (7 dias), sem presença inferida. */
export function UnitDeviceAccess({ accountId, tenantId, unitId, timeZone }: { accountId: string; tenantId: string; unitId: string; timeZone: string }) {
  const key = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'device-access'] as const;
  const own = useQuery({ queryKey: [...key, 'credentials'], queryFn: ({ signal }) => accessCredentials.unitCredentials(tenantId, unitId, signal) });
  const events = useQuery({ queryKey: [...key, 'events'], queryFn: ({ signal }) => accessCredentials.unitEvents(tenantId, unitId, signal), enabled: !hidden(own.error) });
  if (hidden(own.error) || hidden(events.error) || codeOf(own.error) === 'UNIT_ACCESS_DENIED') return null;
  const stale = (own.isError && own.data !== undefined && !own.isFetching) || (events.isError && events.data !== undefined && !events.isFetching);
  return <section className="management-section" aria-labelledby="device-access-title">
    <div className="section-heading"><div><span className="section-index">05 / EQUIPAMENTOS</span><h2 id="device-access-title">Meu acesso pelos equipamentos</h2></div>
      <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={own.isFetching || events.isFetching}
        onClick={() => { void own.refetch(); void events.refetch(); }}>Atualizar equipamentos</button></div></div>
    <p className="field-help">O que os equipamentos informaram nos últimos 7 dias. Sem registro de saída não significa que você continua no condomínio.</p>
    {stale ? <Notice tone="warning">Não foi possível atualizar. Os dados abaixo são da última consulta.</Notice> : null}
    {own.isPending ? <div className="panel"><Loading label="Consultando suas credenciais…" /></div>
      : own.isError && !own.data ? <ErrorState error={own.error} retry={() => void own.refetch()} />
        : !own.data || own.data.credentials.length === 0 ? <div className="panel"><Empty title="Nenhuma credencial">A gestão ainda não cadastrou cartão ou QR para você.</Empty></div>
          : <ol className="management-list" aria-label="Minhas credenciais">{own.data.credentials.map((item) =>
            <li key={item.credentialId} className="management-row"><CredentialDetails item={item} timeZone={timeZone} /></li>)}</ol>}
    {events.isPending && !hidden(own.error) ? <div className="panel"><Loading label="Consultando eventos dos equipamentos…" /></div>
      : events.isError && !events.data ? <ErrorState error={events.error} retry={() => void events.refetch()} />
        : !events.data || events.data.events.length === 0 ? <p className="field-help">Nenhum evento dos equipamentos nos últimos 7 dias.</p>
          : <ol className="management-list" aria-label="Eventos dos equipamentos">{events.data.events.map((event) =>
            <li key={event.eventId} className="management-row"><div><strong>{describeDeviceEvent(event)}</strong>
              {event.exitRecorded === false ? <span className="status-pill status-pending">Sem registro de saída</span> : null}
              <span>{formatWhen(event.occurredAt, timeZone)}{event.deviceLabel ? <> · <bdi>{event.deviceLabel}</bdi></> : null}{event.credentialLabel ? <> · <bdi>{event.credentialLabel}</bdi></> : null}</span></div></li>)}</ol>}
    {events.data?.truncated ? <Notice tone="warning">A lista mostra os 100 eventos mais recentes.</Notice> : null}
  </section>;
}

/** Portaria: eventos dos equipamentos nas últimas 24 horas, inclusive quarentena. */
export function ConciergeDeviceEvents() {
  const scope = useManagement();
  const list = useQuery({ queryKey: conciergeKey(scope, 'device-events'), queryFn: ({ signal }) => accessCredentials.recentEvents(scope.tenantId, signal),
    refetchInterval: (query) => hidden(query.state.error) ? false : 60_000 });
  if (useAccessGuard(list.error)) return null;
  if (hidden(list.error)) return null;
  const stale = list.isError && list.data !== undefined && !list.isFetching;
  return <section className="management-section" aria-labelledby="device-events-title">
    <div className="section-heading"><div><span className="section-index">ACESSO</span><h2 id="device-events-title">Eventos dos equipamentos</h2></div>
      <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar eventos</button></div></div>
    <p className="field-help">Últimas 24 horas, como os equipamentos informaram. Sem registro de saída não significa que a pessoa continua no condomínio.</p>
    {stale ? <Notice tone="warning">{`Não foi possível atualizar os eventos (${errorMessage(list.error)}). A lista é da última consulta.`}</Notice> : null}
    {list.isPending ? <div className="panel"><Loading label="Consultando eventos dos equipamentos…" /></div>
      : list.isError && !list.data ? <ErrorState error={list.error} retry={() => void list.refetch()} />
        : !list.data || list.data.events.length === 0 ? <div className="panel"><Empty title="Nenhum evento">Nenhum evento dos equipamentos nas últimas 24 horas.</Empty></div>
          : <ol className="management-list" aria-label="Eventos dos equipamentos">{list.data.events.map((event) =>
            <li key={event.eventId} className="management-row"><div><strong>{describeDeviceEvent(event)}</strong>
              {event.state === 'QUARANTINED' ? <span className="status-pill status-overdue">Em quarentena: equipamento desconhecido ou dado fora do formato</span> : null}
              <span>{formatWhen(event.occurredAt, scope.timeZone)}{event.deviceLabel ? <> · <bdi>{event.deviceLabel}</bdi></> : null}
                {event.unitLabel ? <> · unidade <bdi>{event.unitLabel}</bdi></> : null}{event.credentialLabel ? <> · <bdi>{event.credentialLabel}</bdi></> : null}</span></div></li>)}</ol>}
    {list.data?.truncated ? <Notice tone="warning">A lista mostra os 100 eventos mais recentes.</Notice> : null}
  </section>;
}

const connectorHealthText: Record<ConnectorHealth['status'], [string, string]> = {
  OK: ['Integração respondendo', 'status-ready'], UNREACHABLE: ['Integração sem resposta', 'status-overdue'],
  UNKNOWN: ['Sem informação recente da integração', 'status-pending'], DISABLED: ['Integração desativada', 'status-neutral'],
};
const deviceHealthText: Record<DeviceHealth['status'], [string, string]> = {
  ONLINE: ['No ar', 'status-ready'], OFFLINE: ['Fora do ar', 'status-overdue'], UNKNOWN: ['Sem informação recente', 'status-pending'], RETIRED: ['Retirado', 'status-neutral'],
};
const deviceReasonText: Record<NonNullable<DeviceHealth['reason']>, string> = {
  HEARTBEAT_STALE: 'sem sinal do equipamento há mais de 5 minutos', HEARTBEAT_MISSING: 'a integração não informou sinal do equipamento', NOT_REPORTED: 'a integração não informou este equipamento',
  STALE: 'última consulta há mais de 10 minutos', NEVER_CHECKED: 'a integração ainda não foi consultada',
  CONNECTOR_UNREACHABLE: 'a integração não respondeu', CONNECTOR_DISABLED: 'integração desativada',
};
const plural = (value: number, one: string, many: string) => `${value} ${value === 1 ? one : many}`;

function DeviceHealthRow({ device, timeZone }: { device: DeviceHealth; timeZone: string }) {
  const [label, tone] = deviceHealthText[device.status];
  const pending = [device.pendingRevoke ? plural(device.pendingRevoke, 'revogação pendente', 'revogações pendentes') : null,
    device.pendingApply ? plural(device.pendingApply, 'ativação pendente', 'ativações pendentes') : null,
    device.rejected ? plural(device.rejected, 'recusa do equipamento', 'recusas do equipamento') : null].filter(Boolean).join(' · ');
  return <li className="management-row"><div><strong><bdi>{device.label}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
    {device.clockDrift ? <span className="status-pill status-overdue">{`Relógio diferente em ${Math.abs(device.clockSkewSeconds ?? 0)} s`}</span> : null}
    <span>{device.reason ? `${deviceReasonText[device.reason]}. ` : ''}{device.status === 'OFFLINE' && device.stateSince && !device.reason ? `Fora do ar desde ${formatWhen(device.stateSince, timeZone)}. ` : ''}
      {device.observedAt ? `Última observação em ${formatWhen(device.observedAt, timeZone)}.` : 'Nenhuma observação ainda.'}</span>
    {pending ? <span>{pending}{device.oldestPendingRevocation ? ` (revogação mais antiga em ${formatWhen(device.oldestPendingRevocation, timeZone)})` : ''}</span>
      : <span>Nenhuma credencial aguardando este equipamento.</span>}</div></li>;
}

/**
 * Saúde dos equipamentos (CP-048) para a gestão e a portaria. "No ar" só aparece com observação recente; equipamento
 * fora do ar com credencial pendente pede a contingência local, porque a revogação não chegou.
 */
export function AccessHealthSection() {
  const scope = useManagement();
  const query = useQuery({ queryKey: managementKey(scope, 'access-health'), queryFn: ({ signal }) => accessCredentials.health(scope.tenantId, signal),
    refetchInterval: (state) => hidden(state.state.error) ? false : 60_000 });
  if (useAccessGuard(query.error)) return null;
  if (hidden(query.error)) return null;
  const stale = query.isError && query.data !== undefined && !query.isFetching;
  const attention = (query.data?.connectors ?? []).some((item) => item.devices.some((device) => device.status !== 'ONLINE' && device.status !== 'RETIRED' && device.pendingRevoke > 0));
  return <section className="management-section" aria-labelledby="access-health-title">
    <div className="section-heading"><div><span className="section-index">ACESSO</span><h2 id="access-health-title">Saúde dos equipamentos</h2></div>
      <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={query.isFetching} onClick={() => void query.refetch()}>Atualizar saúde</button></div></div>
    <p className="field-help">Situação informada pela integração na última consulta. Quando volta ao ar, o equipamento recebe só o estado atual de cada credencial, sem repetir comandos antigos.</p>
    {stale ? <Notice tone="warning">{`Não foi possível atualizar a saúde (${errorMessage(query.error)}). Os dados abaixo são da última consulta e podem não refletir a situação atual.`}</Notice> : null}
    {attention ? <Notice tone="warning">Há revogação pendente em equipamento fora do ar ou sem informação recente. Siga a contingência local nesse acesso.</Notice> : null}
    {query.isPending ? <div className="panel"><Loading label="Consultando a saúde dos equipamentos…" /></div>
      : query.isError && !query.data ? <ErrorState error={query.error} retry={() => void query.refetch()} />
        : !query.data || query.data.connectors.length === 0 ? <div className="panel"><Empty title="Nenhuma integração">Nenhuma integração de acesso cadastrada neste condomínio.</Empty></div>
          : query.data.connectors.map((connector) => {
            const [label, tone] = connectorHealthText[connector.status];
            return <div key={connector.connectorId} className="panel" aria-label={`Integração ${connector.label}`}>
              <p><strong><bdi>{connector.label}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span></p>
              <p className="field-help">{connector.checkedAt ? `Última consulta em ${formatWhen(connector.checkedAt, scope.timeZone)}` : 'Ainda não consultada'}
                {connector.status === 'UNREACHABLE' && connector.lastReachableAt ? `; última resposta em ${formatWhen(connector.lastReachableAt, scope.timeZone)}` : ''}
                {connector.status === 'OK' && connector.pendingCommands !== null ? `; ${plural(connector.pendingCommands, 'comando na fila', 'comandos na fila')} do parceiro` : ''}.</p>
              {connector.devices.length === 0 ? <Empty title="Nenhum equipamento">Nenhum equipamento cadastrado nesta integração.</Empty>
                : <ol className="management-list" aria-label={`Equipamentos de ${connector.label}`}>{connector.devices.map((device) =>
                  <DeviceHealthRow key={device.deviceId} device={device} timeZone={scope.timeZone} />)}</ol>}
            </div>;
          })}
  </section>;
}
