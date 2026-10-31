import { UnitDeviceAccess } from './AccessCredentials';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { collapseText, displayText } from '../api/text';
import { visits, type AccessRecord, type Arrival, type CreatePermitBody, type CreateRuleBody, type VisitPermit, type VisitRule } from '../api/visits';
import { useAuth } from './Auth';
import { condoOf, panelEnabled } from './shell/paths';
import { Empty, ErrorState, Icon, Loading, Notice, errorMessage } from './primitives';
import { dateInZone, formatWhen, toInstant, wallTimeInZone } from './dateTime';
import { codeOf, repeatFailure, settleFailure, uncertain, useIntent } from './operationIntent';
import './management/Management.css';

/**
 * Acessos da unidade (MOR-02/MOR-03): chegadas que aguardam a resposta da unidade e convites (visitas previstas).
 * Convite é autorização temporal, não prova de identidade nem de entrada: a portaria segue conferindo a pessoa.
 * Sem resposta dentro do prazo nada é liberado automaticamente.
 */
export function VisitsPage() {
  const { identity } = useAuth();
  const { tenantId = '', unitId = '' } = useParams();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.unitId === unitId
    && ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT'].includes(item.role));
  if (!identity || !membership) return <div className="panel"><Empty title="Unidade indisponível">Selecione uma unidade em que você mora para acompanhar os acessos.</Empty></div>;
  try { dateInZone(membership.timeZone); }
  catch { return <Notice tone="error">O fuso do condomínio precisa ser conferido pela gestão antes de acompanhar os acessos.</Notice>; }
  // Cada painel segue o próprio módulo: visitas (chegadas, convites, recorrentes, histórico) e credenciais (equipamentos).
  const condo = condoOf(identity, tenantId);
  const scope = { accountId: identity.accountId, tenantId, unitId, tenantName: membership.tenantName, timeZone: membership.timeZone,
    adult: membership.role !== 'DEPENDENT' };
  return <>
    {panelEnabled(condo, 'morador.acessos.visitas') ? <UnitAccess key={`${identity.accountId}:${tenantId}:${unitId}`} {...scope} />
      : <div className="residence-heading"><div><span className="eyebrow">ACESSOS</span><h1>Acessos da unidade</h1>
        <p>Visitas e convites estão desligados pela administradora neste condomínio. Abaixo, as credenciais e o histórico dos equipamentos.</p></div></div>}
    {panelEnabled(condo, 'morador.acessos.equipamentos') ? <UnitDeviceAccess accountId={identity.accountId} tenantId={tenantId} unitId={unitId} timeZone={membership.timeZone} /> : null}
  </>;
}

interface Scope { accountId: string; tenantId: string; unitId: string; tenantName: string; timeZone: string; adult: boolean }
const unitKey = (scope: Scope, ...parts: unknown[]) => ['private', scope.accountId, 'tenant', scope.tenantId, 'unit', scope.unitId, 'visits', ...parts] as const;
const denied = (error: unknown) => ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(codeOf(error));
const unavailable = (error: unknown) => codeOf(error) === 'API_NOT_AVAILABLE';

/** Uma ação por vez; o resultado vem do servidor e falha incerta pede para conferir antes de repetir. */
function useLocalAction() {
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  async function run<T>(action: () => Promise<T>): Promise<{ value?: T; error?: unknown }> {
    if (pending) return {};
    setPending(true); setFailure(null);
    try { return { value: await action() }; }
    catch (error) { setFailure(error); return { error }; }
    finally { setPending(false); }
  }
  const notice = failure ? <Notice tone={uncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}
    {uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}</Notice> : null;
  return { pending, failure, notice, run, clear: () => setFailure(null) };
}

function UnitAccess(scope: Scope) {
  const queries = useQueryClient();
  // Sem aviso por push ainda: a lista de chegadas é consultada de novo a cada 10 segundos enquanto a tela está aberta
  // (pausa em segundo plano e quando o recurso não está disponível para o vínculo ou no ambiente).
  const arrivals = useQuery({ queryKey: unitKey(scope, 'arrivals'), queryFn: ({ signal }) => visits.unitArrivals(scope.tenantId, scope.unitId, signal),
    refetchInterval: (query) => denied(query.state.error) || unavailable(query.state.error) ? false : 10_000 });
  const permits = useQuery({ queryKey: unitKey(scope, 'permits'), queryFn: ({ signal }) => visits.unitPermits(scope.tenantId, scope.unitId, signal) });
  const rules = useQuery({ queryKey: unitKey(scope, 'rules'), queryFn: ({ signal }) => visits.unitRules(scope.tenantId, scope.unitId, signal) });
  const records = useQuery({ queryKey: unitKey(scope, 'access'), queryFn: ({ signal }) => visits.unitRecords(scope.tenantId, scope.unitId, signal) });
  const refresh = () => { void queries.invalidateQueries({ queryKey: unitKey(scope) }); };
  return <>
    <div className="residence-heading"><div><span className="eyebrow">ACESSOS</span><h1>Acessos da unidade</h1>
      <p>Chegadas que aguardam a sua resposta, convites e entradas registradas em {scope.tenantName}. Convite não substitui a conferência da portaria. Horários em {scope.timeZone}.</p></div>
      <button className="button button-secondary button-compact" type="button" disabled={arrivals.isFetching || permits.isFetching || rules.isFetching || records.isFetching} onClick={refresh}>Atualizar</button></div>
    {unavailable(arrivals.error) || unavailable(permits.error) ? <div className="panel"><Empty title="Acessos indisponíveis">Os acessos ainda não estão ativos neste ambiente.</Empty></div> : <>
      <section className="management-section" aria-labelledby="arrivals-title">
        <div className="section-heading"><div><span className="section-index">01 / CHEGADAS</span><h2 id="arrivals-title">Chegadas na portaria</h2></div></div>
        {arrivals.isPending ? <div className="panel"><Loading label="Consultando chegadas…" /></div>
          : denied(arrivals.error) ? <p className="field-help">As chegadas desta unidade não estão disponíveis para o seu vínculo.</p>
            : arrivals.isError && !arrivals.data ? <ErrorState error={arrivals.error} retry={() => void arrivals.refetch()} />
              : <ArrivalList scope={scope} arrivals={arrivals.data?.arrivals ?? []} />}
      </section>
      <section className="management-section" aria-labelledby="permits-title">
        <div className="section-heading"><div><span className="section-index">02 / CONVITES</span><h2 id="permits-title">Visitas previstas</h2></div></div>
        {permits.isPending ? <div className="panel"><Loading label="Consultando convites…" /></div>
          : denied(permits.error) ? <p className="field-help">Convidar não está disponível para o seu vínculo. Peça a um responsável pela unidade.</p>
            : permits.isError && !permits.data ? <ErrorState error={permits.error} retry={() => void permits.refetch()} />
              : <Permits scope={scope} permits={permits.data?.permits ?? []} truncated={permits.data?.truncated ?? false} />}
      </section>
      <section className="management-section" aria-labelledby="rules-title">
        <div className="section-heading"><div><span className="section-index">03 / RECORRENTES</span><h2 id="rules-title">Prestadores recorrentes</h2></div></div>
        {rules.isPending ? <div className="panel"><Loading label="Consultando prestadores…" /></div>
          : denied(rules.error) ? <p className="field-help">Prestadores recorrentes não estão disponíveis para o seu vínculo. Peça a um responsável pela unidade.</p>
            : unavailable(rules.error) ? <p className="field-help">Prestadores recorrentes ainda não estão ativos neste ambiente.</p>
              : rules.isError && !rules.data ? <ErrorState error={rules.error} retry={() => void rules.refetch()} />
                : <Rules scope={scope} rules={rules.data?.rules ?? []} truncated={rules.data?.truncated ?? false} />}
      </section>
      <section className="management-section" aria-labelledby="history-title">
        <div className="section-heading"><div><span className="section-index">04 / HISTÓRICO</span><h2 id="history-title">Entradas e saídas</h2></div></div>
        {records.isPending ? <div className="panel"><Loading label="Consultando registros…" /></div>
          : denied(records.error) ? <p className="field-help">O histórico de entradas desta unidade não está disponível para o seu vínculo.</p>
            : records.isError && !records.data ? <ErrorState error={records.error} retry={() => void records.refetch()} />
              : <AccessHistory scope={scope} records={records.data?.records ?? []} truncated={records.data?.truncated ?? false} />}
      </section>
    </>}
  </>;
}

const arrivalLabels: Record<Arrival['state'], [string, string]> = {
  WAITING: ['Aguardando resposta', 'status-pending'], APPROVED: ['Entrada autorizada', 'status-ready'], DENIED: ['Entrada recusada', 'status-neutral'],
  EXPIRED: ['Sem resposta no prazo', 'status-neutral'], CANCELLED: ['Encerrada pela portaria', 'status-neutral'],
};
const sourceLabels = { APP: 'pelo aplicativo', INTERCOM: 'pelo interfone, registrada pela portaria', IN_PERSON: 'presencialmente, registrada pela portaria', CONCIERGE: 'pela portaria' } as const;

type Outcome = { tone: 'success' | 'warning' | 'info'; text: string };
/** Estado final mudado por outra via (portaria, outra pessoa da unidade, prazo): a lista atualizada mostra a situação real. */
const closedArrivalCodes = ['ARRIVAL_EXPIRED', 'ARRIVAL_ALREADY_DECIDED', 'ARRIVAL_CANCELLED', 'ARRIVAL_NOT_FOUND'];

function ArrivalList({ scope, arrivals }: { scope: Scope; arrivals: Arrival[] }) {
  // O resultado fica fora da linha: depois da resposta a chegada sai da lista de espera e a mensagem continua visível.
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const waiting = arrivals.filter((item) => item.state === 'WAITING');
  const recent = arrivals.filter((item) => item.state !== 'WAITING');
  return <>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {waiting.length === 0 ? <div className="panel"><Empty title="Ninguém aguardando">Quando a portaria registrar alguém para a unidade, a chegada aparece aqui para você responder.</Empty></div>
      : <ol className="management-list" aria-label="Chegadas aguardando resposta">{waiting.map((item) =>
        <WaitingArrival key={item.arrivalId} scope={scope} arrival={item} answered={setOutcome} />)}</ol>}
    {recent.length > 0 ? <ol className="management-list" aria-label="Chegadas recentes">{recent.map((item) => <li key={item.arrivalId} className="management-row">
      <div><strong><bdi>{item.visitorName}</bdi></strong> <span className={`status-pill ${arrivalLabels[item.state][1]}`}>{arrivalLabels[item.state][0]}</span>
        <span>Chegou em {formatWhen(item.createdAt, scope.timeZone)}{item.documentLast4 ? ` · documento final ${item.documentLast4}` : ''}</span>
        {item.permitId ? <span className="field-help">A portaria vinculou esta chegada a um convite da unidade.</span> : null}
        {item.state === 'EXPIRED' ? <span className="field-help">Sem resposta pelo aplicativo no prazo: nada foi liberado automaticamente.</span> : null}
        {item.decisionSource && item.decidedAt ? <span>{item.state === 'CANCELLED' ? 'Encerrada' : 'Respondida'} {sourceLabels[item.decisionSource]}{item.decidedByMe ? ' por você' : ''} em {formatWhen(item.decidedAt, scope.timeZone)}</span> : null}
        {item.decisionNote ? <span><bdi>{item.decisionNote}</bdi></span> : null}</div>
    </li>)}</ol> : null}
  </>;
}

function WaitingArrival({ scope, arrival, answered }: { scope: Scope; arrival: Arrival; answered(outcome: Outcome): void }) {
  const queries = useQueryClient();
  const action = useLocalAction();
  async function answer(decision: 'APPROVE' | 'DENY') {
    const { value: result, error } = await action.run(() => visits.decide(scope.tenantId, arrival.arrivalId, decision));
    if (result) answered(result.arrivalId === arrival.arrivalId && result.state === (decision === 'APPROVE' ? 'APPROVED' : 'DENIED')
      ? { tone: 'success', text: decision === 'APPROVE' ? `Entrada de ${result.visitorName} autorizada. A portaria confere a pessoa antes de liberar.` : `Entrada de ${result.visitorName} recusada.` }
      : { tone: 'warning', text: 'A resposta não confirmou a sua decisão. Atualize e confira.' });
    // Prazo vencido: a resposta não vale. Já respondida ou encerrada: a lista mostra quem decidiu (pode ter sido a sua
    // tentativa anterior, com resultado incerto).
    else if (codeOf(error) === 'ARRIVAL_EXPIRED') answered({ tone: 'warning', text: `${errorMessage(error)} A sua resposta não foi registrada.` });
    else if (closedArrivalCodes.includes(codeOf(error))) answered({ tone: 'warning', text: `${errorMessage(error)} Confira a decisão registrada na lista.` });
    await queries.invalidateQueries({ queryKey: unitKey(scope, 'arrivals') });
  }
  return <li className="management-row">
    <div><strong><bdi>{arrival.visitorName}</bdi></strong> <span className="status-pill status-pending">Aguardando resposta</span>
      <span>Na portaria desde {formatWhen(arrival.createdAt, scope.timeZone)} · responda até {formatWhen(arrival.expiresAt, scope.timeZone)}{arrival.documentLast4 ? ` · documento final ${arrival.documentLast4}` : ''}</span>
      {arrival.permitId ? <span className="field-help">A portaria vinculou esta chegada a um convite da unidade e pede a sua confirmação.</span> : null}
      <span className="field-help">Sem resposta no prazo nada é liberado: a portaria segue pelo interfone ou pelo procedimento do condomínio.</span></div>
    <div className="button-row">
      <button className="button button-compact" type="button" disabled={action.pending} onClick={() => void answer('APPROVE')}>Autorizar entrada</button>
      <button className="button button-secondary button-compact" type="button" disabled={action.pending} onClick={() => void answer('DENY')}>Recusar</button></div>
    {closedArrivalCodes.includes(codeOf(action.failure)) ? null : action.notice}
  </li>;
}

/**
 * Histórico de 7 dias da unidade: só o que a portaria registrou manualmente (nunca "passagem pelo equipamento"). Sem
 * registro de saída não quer dizer que a pessoa ainda esteja no condomínio.
 */
function AccessHistory({ scope, records, truncated }: { scope: Scope; records: AccessRecord[]; truncated: boolean }) {
  if (records.length === 0) return <div className="panel"><Empty title="Nenhum registro">Entradas e saídas de visitantes registradas pela portaria aparecem aqui por 7 dias.</Empty></div>;
  return <>
    <ol className="management-list" aria-label="Entradas e saídas registradas">{records.map((record) => {
      const exited = record.exit !== null && record.exit.correction === null;
      const [label, tone] = record.correction ? ['Registro corrigido', 'status-neutral'] : exited ? ['Saída registrada', 'status-ready'] : ['Sem registro de saída', 'status-pending'];
      return <li key={record.entryId} className="management-row">
        <div><strong><bdi>{record.visitorName}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
          <span>Entrada registrada pela portaria em {formatWhen(record.enteredAt, scope.timeZone)} · {record.arrivalId ? 'chegada autorizada pela unidade' : 'convite da unidade'}</span>
          {record.correction ? <span>Corrigido pela portaria: <bdi>{record.correction.reason}</bdi></span> : null}
          {record.exit ? <span>Saída registrada pela portaria em {formatWhen(record.exit.exitedAt, scope.timeZone)}{record.exit.correction ? ` (corrigida: ${record.exit.correction.reason})` : ''}</span> : null}</div>
      </li>;
    })}</ol>
    {truncated ? <Notice tone="warning">O histórico mostra as 100 entradas mais recentes.</Notice> : null}
  </>;
}

const permitLabels: Record<VisitPermit['state'], [string, string]> = {
  SCHEDULED: ['Agendado', 'status-pending'], ACTIVE: ['Vale agora', 'status-ready'], REVOKED: ['Revogado', 'status-neutral'],
  EXPIRED: ['Vigência encerrada', 'status-neutral'],
};
/** Agendado ou em vigor: ainda pode ser revogado. */
const open = (permit: VisitPermit) => permit.state === 'ACTIVE' || permit.state === 'SCHEDULED';

function Permits({ scope, permits, truncated }: { scope: Scope; permits: VisitPermit[]; truncated: boolean }) {
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<{ tone: 'success' | 'warning' | 'info'; text: string } | null>(null);
  const active = permits.filter(open);
  const closed = permits.filter((item) => !open(item));
  const finish = (result: { tone: 'success' | 'warning' | 'info'; text: string } | null) => { setCreating(false); setRevoking(null); if (result) setOutcome(result); };
  return <>
    <div className="button-row">{!creating ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setRevoking(null); setCreating(true); }}>Convidar visitante</button> : null}</div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {creating ? <PermitForm scope={scope} close={finish} /> : null}
    {active.length === 0 && !creating ? <div className="panel"><Empty title="Nenhum convite em aberto">Convide quem você espera: a portaria vê o nome e o período e confere a pessoa na chegada.</Empty></div>
      : <ol className="management-list" aria-label="Convites em aberto">{active.map((item) => <PermitRow key={item.permitId} scope={scope} permit={item}>
        {revoking === item.permitId ? <RevokeForm scope={scope} permit={item} close={finish} />
          : <><div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => { setOutcome(null); setCreating(false); setRevoking(item.permitId); }}>Revogar convite</button></div>
            <ShareLink scope={scope} permit={item} /></>}
      </PermitRow>)}</ol>}
    {closed.length > 0 ? <ol className="management-list" aria-label="Convites encerrados">{closed.map((item) => <PermitRow key={item.permitId} scope={scope} permit={item} />)}</ol> : null}
    {truncated ? <Notice tone="warning">A lista mostra os 100 convites de fim mais recente.</Notice> : null}
  </>;
}

/**
 * Link compartilhável do convite (CP-037). O token aparece só uma vez, nesta tela, e vai no fragmento da URL (nunca
 * enviado ao servidor pela barra de endereço). Gerar outro revoga o anterior; revogar o link não revoga o convite.
 */
function ShareLink({ scope, permit }: { scope: Scope; permit: VisitPermit }) {
  const queries = useQueryClient();
  const action = useLocalAction();
  const [link, setLink] = useState<string | null>(null);
  const [copied, setCopied] = useState<'ok' | 'failed' | null>(null);
  async function share() {
    setCopied(null);
    const { value } = await action.run(() => visits.shareLink(scope.tenantId, permit.permitId));
    await queries.invalidateQueries({ queryKey: unitKey(scope, 'permits') });
    if (value) setLink(`${window.location.origin}/visita#${value.token}`);
  }
  async function revoke() {
    setLink(null); setCopied(null);
    await action.run(() => visits.revokeLink(scope.tenantId, permit.permitId));
    await queries.invalidateQueries({ queryKey: unitKey(scope, 'permits') });
  }
  async function copy() {
    if (!link) return;
    try { await navigator.clipboard.writeText(link); setCopied('ok'); } catch { setCopied('failed'); }
  }
  return <div className="share-link" aria-label={`Link do convite de ${permit.visitorName}`}>
    {link ? <>
      <label htmlFor={`share-${permit.permitId}`}>Link para enviar a <bdi>{permit.visitorName}</bdi></label>
      <input id={`share-${permit.permitId}`} readOnly value={link} onFocus={(event) => event.currentTarget.select()} />
      <span className="field-help">Este link aparece só agora. Ele mostra o condomínio, o primeiro nome e o período; não mostra a unidade. Gerar outro revoga este.</span>
      {copied === 'ok' ? <Notice tone="success">Link copiado.</Notice> : copied === 'failed' ? <Notice tone="warning">Não foi possível copiar. Selecione o link e copie manualmente.</Notice> : null}
    </> : permit.shareLinkActive ? <span className="field-help">Há um link em vigor para este convite.</span> : null}
    {action.failure && uncertain(action.failure)
      ? <Notice tone="warning">Não foi possível confirmar se o link foi gerado. Gere outro: o anterior deixa de valer.</Notice> : action.notice}
    <div className="button-row">
      {link ? <button className="button button-compact" type="button" onClick={() => void copy()}>Copiar link</button> : null}
      <button className="button button-secondary button-compact" type="button" disabled={action.pending} onClick={() => void share()}>
        {action.pending ? 'Gerando…' : permit.shareLinkActive || link ? 'Gerar novo link' : 'Compartilhar link'}</button>
      {permit.shareLinkActive || link ? <button className="button button-secondary button-compact" type="button" disabled={action.pending}
        onClick={() => void revoke()}>Revogar link</button> : null}
    </div>
  </div>;
}

function PermitRow({ scope, permit, children }: { scope: Scope; permit: VisitPermit; children?: ReactNode }) {
  const [label, tone] = permitLabels[permit.state];
  return <li className="management-row">
    <div><strong><bdi>{permit.visitorName}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
      <span>De {formatWhen(permit.validFrom, scope.timeZone)} até {formatWhen(permit.validUntil, scope.timeZone)}{permit.documentLast4 ? ` · documento final ${permit.documentLast4}` : ''}</span>
      {permit.purpose ? <span><bdi>{permit.purpose}</bdi></span> : null}
      {permit.ruleId ? <span className="field-help">Janela de prestador recorrente: revogar aqui cancela só este dia.</span>
        : permit.reservationId ? <span className="field-help">Convidado de reserva: termina com o evento.</span> : null}
      <span className="field-help">{permit.createdByMe ? 'Criado por você' : 'Criado por outra pessoa da unidade'}</span>
      {permit.revokeReason ? <span>Revogado: <bdi>{permit.revokeReason}</bdi></span> : null}</div>
    {children}
  </li>;
}

function PermitForm({ scope, close }: { scope: Scope; close(result: { tone: 'success' | 'warning' | 'info'; text: string } | null): void }) {
  const queries = useQueryClient();
  const action = useLocalAction();
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [purpose, setPurpose] = useState('');
  const [from, setFrom] = useState(() => wallTimeInZone(scope.timeZone));
  const [until, setUntil] = useState(() => wallTimeInZone(scope.timeZone, new Date(Date.now() + 2 * 3_600_000)));
  // Mesma intenção reaproveita a chave; depois de resultado incerto os campos travam até conferir a lista.
  const intent = useIntent();
  const [locked, setLocked] = useState(false);
  const visitor = displayText(name, 160);
  const documentValue = collapseText(document).toUpperCase();
  const documentValid = documentValue === '' || /^[0-9A-Z]{2,4}$/.test(documentValue);
  const purposeValue = collapseText(purpose) ? displayText(purpose, 200) : '';
  let period: { validFrom: string; validUntil: string } | null = null;
  let periodError: string | null = null;
  try {
    const validFrom = toInstant(from, scope.timeZone), validUntil = toInstant(until, scope.timeZone);
    const span = Date.parse(validUntil) - Date.parse(validFrom);
    if (span <= 0) periodError = 'O fim precisa ser depois do início.';
    else if (span > 7 * 24 * 3_600_000) periodError = 'O convite vale por até 7 dias.';
    else period = { validFrom, validUntil };
  } catch (error) { periodError = error instanceof Error ? error.message : 'Informe o período.'; }
  const valid = visitor !== null && [...visitor].length >= 2 && documentValid && purposeValue !== null && period !== null;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || !period) return;
    const body: CreatePermitBody = { visitorName: visitor!, documentLast4: documentValue || null, purpose: purposeValue || null, ...period };
    const key = intent.keyFor(JSON.stringify(body));
    const { value: result, error } = await action.run(async () => {
      try { return await visits.createPermit(scope.tenantId, scope.unitId, key, body); }
      catch (failure) { throw repeatFailure(failure); }
    });
    await queries.invalidateQueries({ queryKey: unitKey(scope, 'permits') });
    if (error !== undefined) { settleFailure(error, locked, intent, setLocked); return; }
    if (result) {
      close(result.permit.visitorName === body.visitorName && open(result.permit)
        ? { tone: result.replayed ? 'info' : 'success', text: `${result.replayed ? 'Este convite já estava criado' : 'Convite criado'} para ${result.permit.visitorName}, de ${formatWhen(result.permit.validFrom, scope.timeZone)} até ${formatWhen(result.permit.validUntil, scope.timeZone)}.` }
        : { tone: 'warning', text: 'A resposta não confirmou o convite. Atualize e confira a lista.' });
    }
  }
  const busy = action.pending || locked;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Convidar visitante">
    <label htmlFor="permit-name">Nome do visitante</label>
    <input id="permit-name" required maxLength={160} value={name} onChange={(event) => setName(event.target.value)} disabled={busy} />
    <label htmlFor="permit-document">Final do documento (opcional)</label>
    <input id="permit-document" maxLength={4} value={document} onChange={(event) => setDocument(event.target.value)} disabled={busy}
      aria-describedby="permit-document-help" aria-invalid={!documentValid} />
    <span className="field-help" id="permit-document-help">Somente os 2 a 4 últimos caracteres, quando a portaria precisar conferir. Não informe o documento inteiro.</span>
    <label htmlFor="permit-purpose">Finalidade (opcional)</label>
    <input id="permit-purpose" maxLength={200} value={purpose} onChange={(event) => setPurpose(event.target.value)} disabled={busy} placeholder="Ex.: visita familiar" />
    <label htmlFor="permit-from">Início</label>
    <input id="permit-from" type="datetime-local" required value={from} onChange={(event) => setFrom(event.target.value)} disabled={busy} />
    <label htmlFor="permit-until">Fim</label>
    <input id="permit-until" type="datetime-local" required value={until} onChange={(event) => setUntil(event.target.value)} disabled={busy} />
    {periodError ? <span className="field-help" role="status">{periodError}</span> : null}
    {action.notice}
    {locked ? <Notice tone="warning">O resultado não foi confirmado e os dados ficaram travados. Atualize a lista: se o convite já aparece, não crie de novo.
      <span className="button-row"><button className="button button-secondary button-compact" type="button" disabled={action.pending}
        onClick={() => { intent.reset(); setLocked(false); action.clear(); }}>Conferi a lista: criar outro convite</button></span></Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.pending}>{action.pending ? 'Criando…' : locked ? 'Tentar confirmar o mesmo convite' : 'Criar convite'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.pending}>Voltar</button>
    </div>
  </form>;
}

function RevokeForm({ scope, permit, close }: { scope: Scope; permit: VisitPermit; close(result: { tone: 'success' | 'warning' | 'info'; text: string } | null): void }) {
  const queries = useQueryClient();
  const action = useLocalAction();
  const [read] = useState(permit.version);
  const [reason, setReason] = useState('');
  const normalized = displayText(reason, 500);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    const { value: result, error } = await action.run(() => visits.revokePermit(scope.tenantId, permit.permitId, { reason: normalized, expectedVersion: read }));
    await queries.invalidateQueries({ queryKey: unitKey(scope, 'permits') });
    if (result) close(result.permitId === permit.permitId && result.state === 'REVOKED'
      ? { tone: 'success', text: `Convite de ${result.visitorName} revogado. A portaria deixa de vê-lo nas visitas previstas.` }
      : { tone: 'warning', text: 'A resposta não confirmou a revogação. Atualize e confira a lista.' });
    // Estado mudado por outra pessoa: o formulário fecha com o aviso e a lista atualizada mostra a situação real.
    else if (['VISIT_PERMIT_REVOKED', 'VISIT_PERMIT_EXPIRED', 'VISIT_PERMIT_CHANGED', 'VISIT_PERMIT_NOT_FOUND'].includes(codeOf(error)))
      close({ tone: 'warning', text: `${errorMessage(error)} Nada foi alterado.` });
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Revogar convite de ${permit.visitorName}`}>
    <Notice tone="warning">Quem chegar depois da revogação não tem mais o convite: a portaria segue o procedimento de chegada sem convite.</Notice>
    <label htmlFor={`revoke-${permit.permitId}`}>Motivo</label>
    <textarea id={`revoke-${permit.permitId}`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.pending} />
    {action.notice}
    <div className="button-row">
      <button className="button button-danger" type="submit" disabled={normalized === null || action.pending}>{action.pending ? 'Revogando…' : 'Revogar convite'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.pending}><Icon name="arrow" />Voltar</button>
    </div>
  </form>;
}

/** Dias da semana ISO (1 = segunda … 7 = domingo), como o servidor guarda. */
const weekDayNames = ['segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado', 'domingo'] as const;
const shortDay = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'] as const;
const dayList = (days: number[]) => days.length === 7 ? 'todos os dias' : days.map((day) => weekDayNames[day - 1]).join(', ');
const formatDay = (day: string) => { const [year, month, date] = day.split('-'); return `${date}/${month}/${year}`; };
const ruleLabels: Record<VisitRule['state'], [string, string]> = {
  ACTIVE: ['Ativa', 'status-ready'], REVOKED: ['Revogada', 'status-neutral'], EXPIRED: ['Encerrada', 'status-neutral'],
};
/** Dia de hoje (aaaa-mm-dd) no fuso do condomínio. */
const todayIn = (timeZone: string) => wallTimeInZone(timeZone).slice(0, 10);
const addDays = (day: string, amount: number) => new Date(Date.parse(`${day}T00:00:00Z`) + amount * 86_400_000).toISOString().slice(0, 10);

/**
 * Prestador recorrente (CP-039): diarista, cuidador etc. A regra gera janelas (convites) só nos dias e horários
 * escolhidos; fora delas não há convite e a portaria segue o procedimento de chegada sem convite.
 */
function Rules({ scope, rules, truncated }: { scope: Scope; rules: VisitRule[]; truncated: boolean }) {
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const active = rules.filter((rule) => rule.state === 'ACTIVE');
  const closed = rules.filter((rule) => rule.state !== 'ACTIVE');
  const finish = (result: Outcome | null) => { setCreating(false); setRevoking(null); if (result) setOutcome(result); };
  return <>
    <div className="button-row">{!creating ? <button className="button button-compact" type="button" onClick={() => { setOutcome(null); setRevoking(null); setCreating(true); }}>Cadastrar prestador</button> : null}</div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {creating ? <RuleForm scope={scope} close={finish} /> : null}
    {active.length === 0 && !creating ? <div className="panel"><Empty title="Nenhum prestador recorrente">Cadastre quem vem em dias fixos: a portaria vê o convite só nos dias e horários da regra.</Empty></div>
      : <ol className="management-list" aria-label="Prestadores ativos">{active.map((rule) => <RuleRow key={rule.ruleId} scope={scope} rule={rule}>
        {revoking === rule.ruleId ? <RuleRevokeForm scope={scope} rule={rule} close={finish} />
          : scope.adult || rule.createdByMe ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
            onClick={() => { setOutcome(null); setCreating(false); setRevoking(rule.ruleId); }}>Revogar regra</button></div> : null}
      </RuleRow>)}</ol>}
    {closed.length > 0 ? <ol className="management-list" aria-label="Prestadores encerrados">{closed.map((rule) => <RuleRow key={rule.ruleId} scope={scope} rule={rule} />)}</ol> : null}
    {truncated ? <Notice tone="warning">A lista mostra as 100 regras mais recentes.</Notice> : null}
  </>;
}

function RuleRow({ scope, rule, children }: { scope: Scope; rule: VisitRule; children?: ReactNode }) {
  const [label, tone] = ruleLabels[rule.state];
  return <li className="management-row">
    <div><strong><bdi>{rule.visitorName}</bdi></strong> <span className={`status-pill ${tone}`}>{label}</span>
      <span>{dayList(rule.weekDays)}, das {rule.startTime} às {rule.endTime} · de {formatDay(rule.validFrom)} até {formatDay(rule.validUntil)}{rule.documentLast4 ? ` · documento final ${rule.documentLast4}` : ''}</span>
      {rule.purpose ? <span><bdi>{rule.purpose}</bdi></span> : null}
      {rule.state === 'ACTIVE' ? <span className="field-help">{rule.nextWindowFrom && rule.nextWindowUntil
        ? `Próxima janela: ${formatWhen(rule.nextWindowFrom, scope.timeZone)} até ${formatWhen(rule.nextWindowUntil, scope.timeZone)}.`
        : 'Sem janela nos próximos dias.'}</span> : null}
      <span className="field-help">{rule.createdByMe ? 'Criada por você' : 'Criada por outra pessoa da unidade'}</span>
      {rule.revokeReason ? <span>Revogada: <bdi>{rule.revokeReason}</bdi></span> : null}</div>
    {children}
  </li>;
}

function RuleForm({ scope, close }: { scope: Scope; close(result: Outcome | null): void }) {
  const queries = useQueryClient();
  const action = useLocalAction();
  const today = todayIn(scope.timeZone);
  const [name, setName] = useState('');
  const [document, setDocument] = useState('');
  const [purpose, setPurpose] = useState('');
  const [days, setDays] = useState<number[]>([]);
  const [startTime, setStartTime] = useState('08:00');
  const [endTime, setEndTime] = useState('17:00');
  const [validFrom, setValidFrom] = useState(today);
  const [validUntil, setValidUntil] = useState(() => addDays(today, 30));
  const intent = useIntent();
  const [locked, setLocked] = useState(false);
  const visitor = displayText(name, 160);
  const documentValue = collapseText(document).toUpperCase();
  const documentValid = documentValue === '' || /^[0-9A-Z]{2,4}$/.test(documentValue);
  const purposeValue = collapseText(purpose) ? displayText(purpose, 200) : '';
  const timeValid = /^\d{2}:\d{2}$/.test(startTime) && /^\d{2}:\d{2}$/.test(endTime) && startTime < endTime;
  const periodError = !/^\d{4}-\d{2}-\d{2}$/.test(validFrom) || !/^\d{4}-\d{2}-\d{2}$/.test(validUntil) ? 'Informe o período.'
    : validFrom < today ? 'A regra começa hoje ou depois.'
      : validFrom > addDays(today, 90) ? 'A regra começa em até 90 dias.'
        : validUntil < validFrom ? 'O fim precisa ser no início ou depois.'
          : validUntil > addDays(validFrom, 180) ? 'A regra vale por até 180 dias.' : null;
  const valid = visitor !== null && [...visitor].length >= 2 && documentValid && purposeValue !== null && days.length > 0 && timeValid && periodError === null;
  const toggle = (day: number) => setDays((current) => current.includes(day) ? current.filter((item) => item !== day) : [...current, day].sort((a, b) => a - b));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid) return;
    const body: CreateRuleBody = { visitorName: visitor!, documentLast4: documentValue || null, purpose: purposeValue || null, weekDays: days, startTime, endTime, validFrom, validUntil };
    const key = intent.keyFor(JSON.stringify(body));
    const { value: result, error } = await action.run(async () => {
      try { return await visits.createRule(scope.tenantId, scope.unitId, key, body); }
      catch (failure) { throw repeatFailure(failure); }
    });
    await queries.invalidateQueries({ queryKey: unitKey(scope) });
    if (error !== undefined) { settleFailure(error, locked, intent, setLocked); return; }
    if (result) {
      close(result.rule.visitorName === body.visitorName && result.rule.state === 'ACTIVE'
        ? { tone: result.replayed ? 'info' : 'success', text: `${result.replayed ? 'Esta regra já estava cadastrada' : 'Prestador cadastrado'}: ${result.rule.visitorName}, ${dayList(result.rule.weekDays)}, das ${result.rule.startTime} às ${result.rule.endTime}.` }
        : { tone: 'warning', text: 'A resposta não confirmou a regra. Atualize e confira a lista.' });
    }
  }
  const busy = action.pending || locked;
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Cadastrar prestador recorrente">
    <label htmlFor="rule-name">Nome do prestador</label>
    <input id="rule-name" required maxLength={160} value={name} onChange={(event) => setName(event.target.value)} disabled={busy} />
    <label htmlFor="rule-document">Final do documento (opcional)</label>
    <input id="rule-document" maxLength={4} value={document} onChange={(event) => setDocument(event.target.value)} disabled={busy} aria-invalid={!documentValid} />
    <label htmlFor="rule-purpose">Serviço (opcional)</label>
    <input id="rule-purpose" maxLength={200} value={purpose} onChange={(event) => setPurpose(event.target.value)} disabled={busy} placeholder="Ex.: limpeza" />
    <fieldset className="weekday-picker" disabled={busy}>
      <legend>Dias da semana</legend>
      {shortDay.map((label, index) => <label key={label}><input type="checkbox" checked={days.includes(index + 1)} onChange={() => toggle(index + 1)}
        aria-label={weekDayNames[index]} />{label}</label>)}
    </fieldset>
    <label htmlFor="rule-start">Chega a partir de</label>
    <input id="rule-start" type="time" required value={startTime} onChange={(event) => setStartTime(event.target.value)} disabled={busy} />
    <label htmlFor="rule-end">Até</label>
    <input id="rule-end" type="time" required value={endTime} onChange={(event) => setEndTime(event.target.value)} disabled={busy} />
    {!timeValid ? <span className="field-help" role="status">O fim precisa ser depois do início, no mesmo dia.</span> : null}
    <label htmlFor="rule-from">Primeiro dia</label>
    <input id="rule-from" type="date" required min={today} value={validFrom} onChange={(event) => setValidFrom(event.target.value)} disabled={busy} />
    <label htmlFor="rule-until">Último dia</label>
    <input id="rule-until" type="date" required min={validFrom} value={validUntil} onChange={(event) => setValidUntil(event.target.value)} disabled={busy} />
    {periodError ? <span className="field-help" role="status">{periodError}</span> : null}
    <span className="field-help">Horários em {scope.timeZone}. Fora dos dias e horários da regra não há convite.</span>
    {action.notice}
    {locked ? <Notice tone="warning">O resultado não foi confirmado e os dados ficaram travados. Atualize a lista: se a regra já aparece, não cadastre de novo.
      <span className="button-row"><button className="button button-secondary button-compact" type="button" disabled={action.pending}
        onClick={() => { intent.reset(); setLocked(false); action.clear(); }}>Conferi a lista: cadastrar outra regra</button></span></Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={!valid || action.pending}>{action.pending ? 'Cadastrando…' : locked ? 'Tentar confirmar a mesma regra' : 'Cadastrar prestador'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.pending}>Voltar</button>
    </div>
  </form>;
}

function RuleRevokeForm({ scope, rule, close }: { scope: Scope; rule: VisitRule; close(result: Outcome | null): void }) {
  const queries = useQueryClient();
  const action = useLocalAction();
  const [read] = useState(rule.version);
  const [reason, setReason] = useState('');
  const normalized = displayText(reason, 500);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (normalized === null) return;
    const { value: result, error } = await action.run(() => visits.revokeRule(scope.tenantId, rule.ruleId, { reason: normalized, expectedVersion: read }));
    await queries.invalidateQueries({ queryKey: unitKey(scope) });
    if (result) close(result.ruleId === rule.ruleId && result.state === 'REVOKED'
      ? { tone: 'success', text: `Regra de ${result.visitorName} revogada. As próximas janelas foram canceladas.` }
      : { tone: 'warning', text: 'A resposta não confirmou a revogação. Atualize e confira a lista.' });
    else if (['VISIT_RULE_REVOKED', 'VISIT_RULE_CHANGED', 'VISIT_RULE_NOT_FOUND'].includes(codeOf(error)))
      close({ tone: 'warning', text: `${errorMessage(error)} Nada foi alterado.` });
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Revogar regra de ${rule.visitorName}`}>
    <Notice tone="warning">Revogar cancela as janelas em vigor e as próximas. Quem chegar depois segue o procedimento de chegada sem convite.</Notice>
    <label htmlFor={`rule-revoke-${rule.ruleId}`}>Motivo</label>
    <textarea id={`rule-revoke-${rule.ruleId}`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={action.pending} />
    {action.notice}
    <div className="button-row">
      <button className="button button-danger" type="submit" disabled={normalized === null || action.pending}>{action.pending ? 'Revogando…' : 'Revogar regra'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={action.pending}><Icon name="arrow" />Voltar</button>
    </div>
  </form>;
}
