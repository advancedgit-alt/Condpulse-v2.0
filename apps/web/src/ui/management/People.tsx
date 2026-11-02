import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { management, type InvitationCreated, type InvitationRole, type ManagedMembership, type MembershipStatus } from '../../api/management';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { RentalsPanel } from './Rentals';
import { ActionFeedback, ReasonForm, managementKey, membershipStatusLabels, roleLabels, statusTone, useAccessGuard, useAction, useManagement, type ActionResult } from './shared';

const invitationRoles: InvitationRole[] = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER', 'CONCIERGE', 'MAINTENANCE', 'MANAGER'];
const invitationStatus: Record<string, string> = { PENDING: 'Pendente', ACCEPTED: 'Aceito', REVOKED: 'Revogado', EXPIRED: 'Expirado' };

function MembershipItem({ membership, onDone }: { membership: ManagedMembership; onDone(result: ActionResult): void }) {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const [mode, setMode] = useState<'idle' | 'approve' | 'reject' | 'revoke'>('idle');
  // Versão lida ao abrir a decisão: uma nova consulta da lista não troca o alvo da confirmação em andamento.
  const [readVersion, setReadVersion] = useState(membership.version);
  const open = (next: 'approve' | 'reject' | 'revoke') => { action.clear(); setReadVersion(membership.version); setMode(next); };
  const outcome = (result: ManagedMembership): ActionResult => ({ tone: 'success', text: result.status === 'ACTIVE'
    ? `${result.displayName}: vínculo aprovado.` : mode === 'revoke'
      ? `${result.displayName}: vínculo revogado. Novas operações deixam de ser autorizadas.` : `${result.displayName}: vínculo rejeitado.` });
  const after = async (result: ManagedMembership) => {
    setMode('idle'); onDone(outcome(result));
    await queries.invalidateQueries({ queryKey: managementKey(scope, 'memberships') });
  };
  const decide = (decision: 'APPROVE' | 'REJECT', reason: string | null) => void action.run(
    () => management.decideMembership(scope.tenantId, membership.membershipId, { decision, expectedVersion: readVersion, reason }),
    '', after);
  const revoke = (reason: string | null) => {
    if (!reason) return;
    void action.run(() => management.revokeMembership(scope.tenantId, membership.membershipId, { expectedVersion: readVersion, reason }),
      '', after);
  };
  const revocable = ['PENDING', 'ACTIVE', 'SUSPENDED'].includes(membership.status);
  return <li className="management-row">
    <div><strong>{membership.displayName}</strong><span>{roleLabels[membership.role] ?? membership.role}{membership.unitLabel ? ` · ${membership.unitLabel}` : ''} · desde {formatWhen(membership.validFrom, scope.timeZone)}</span>
      <span className={`status-pill ${statusTone(membership.status)}`}>{membershipStatusLabels[membership.status]}</span></div>
    {mode === 'idle' ? <div className="button-row">
      {membership.status === 'PENDING' ? <>
        <button className="button button-compact" type="button" disabled={action.state.pending} onClick={() => open('approve')} aria-label={`Aprovar vínculo de ${membership.displayName}`}>Aprovar</button>
        <button className="button button-secondary button-compact" type="button" disabled={action.state.pending} onClick={() => open('reject')} aria-label={`Rejeitar vínculo de ${membership.displayName}`}>Rejeitar</button>
      </> : null}
      {revocable && membership.status !== 'PENDING' ? <button className="text-button danger-text" type="button" disabled={action.state.pending} onClick={() => open('revoke')} aria-label={`Revogar vínculo de ${membership.displayName}`}>Revogar</button> : null}
    </div> : null}
    {mode === 'approve' ? <ReasonForm id={`approve-membership-${membership.membershipId}`} label="Observação" required={false} pending={action.state.pending}
      confirmLabel="Confirmar aprovação" submit={(reason) => decide('APPROVE', reason)} cancel={() => setMode('idle')} /> : null}
    {mode === 'reject' ? <ReasonForm id={`reject-membership-${membership.membershipId}`} label="Motivo da rejeição" required pending={action.state.pending}
      confirmLabel="Confirmar rejeição" tone="danger" submit={(reason) => decide('REJECT', reason)} cancel={() => setMode('idle')} /> : null}
    {mode === 'revoke' ? <ReasonForm id={`revoke-membership-${membership.membershipId}`} label="Motivo da revogação" required pending={action.state.pending}
      confirmLabel="Confirmar revogação" tone="danger" submit={revoke} cancel={() => setMode('idle')} /> : null}
    <ActionFeedback state={action.state} success={false} />
  </li>;
}

export function MembershipList({ status, empty }: { status: MembershipStatus | null; empty: string }) {
  const scope = useManagement();
  const [done, setDone] = useState<ActionResult | null>(null);
  const list = useQuery({ queryKey: managementKey(scope, 'memberships', status), queryFn: ({ signal }) => management.memberships(scope.tenantId, status, signal) });
  if (useAccessGuard(list.error)) return null;
  if (list.isPending) return <div className="panel"><Loading label="Consultando vínculos…" /></div>;
  if (list.isError) return <ErrorState error={list.error} retry={() => void list.refetch()} />;
  return <>
    {done ? <Notice tone={done.tone}>{done.text}</Notice> : null}
    {list.data.memberships.length === 0 ? <div className="panel"><Empty icon="users" title="Nada por aqui">{empty}</Empty></div>
      : <ul className="management-list" aria-label="Vínculos">{list.data.memberships.map((item) => <MembershipItem key={item.membershipId} membership={item} onDone={setDone} />)}</ul>}
    {list.data.truncated ? <Notice tone="warning">Há mais vínculos do que o limite desta consulta. Filtre por situação.</Notice> : null}
  </>;
}

/** Link exibido uma única vez: o token vai no fragmento, que não é enviado em requisições nem registrado pelo servidor. */
export function invitationLink(origin: string, token: string): string { return `${origin}/convite#token=${token}`; }

function InvitationForm() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const units = useQuery({ queryKey: managementKey(scope, 'units'), queryFn: ({ signal }) => management.units(scope.tenantId, signal) });
  const [role, setRole] = useState<InvitationRole>('RESIDENT');
  const [unitId, setUnitId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [validDays, setValidDays] = useState('7');
  const [created, setCreated] = useState<InvitationCreated | null>(null);
  const [copied, setCopied] = useState(false);
  const staff = role === 'CONCIERGE' || role === 'MAINTENANCE' || role === 'MANAGER';
  async function submit(event: FormEvent) {
    event.preventDefault();
    setCreated(null); setCopied(false);
    await action.run(() => management.createInvitation(scope.tenantId, { role, unitId: staff ? null : unitId || null,
      displayName: displayName.trim(), email: email.trim(), validDays: Number(validDays) }), 'Convite criado.',
    async (result) => {
      setCreated(result); setDisplayName(''); setEmail('');
      await queries.invalidateQueries({ queryKey: managementKey(scope, 'invitations') });
    });
  }
  const link = created ? invitationLink(window.location.origin, created.token) : '';
  async function copy() {
    try { await navigator.clipboard.writeText(link); setCopied(true); } catch { setCopied(false); }
  }
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-label="Novo convite">
    <h3>Novo convite</h3>
    {units.isError ? <Notice tone="error">Não foi possível carregar as unidades: {errorMessage(units.error)} <button className="text-button" type="button" onClick={() => void units.refetch()}>Tentar novamente</button></Notice> : null}
    <p className="field-help">A pessoa precisa entrar com uma conta já criada pela operação usando o mesmo e-mail. O vínculo fica pendente até a sua aprovação.</p>
    {role === 'MANAGER' ? <Notice tone="warning">Gestão dá acesso aos cadastros e decisões deste condomínio. O aceite fica pendente; outro gestor ativo aprova com segundo fator. A conta convidada não aprova o próprio vínculo.</Notice> : null}
    <div className="two-fields"><div><label htmlFor="invite-role">Papel</label>
      <select id="invite-role" value={role} onChange={(event) => setRole(event.target.value as InvitationRole)} disabled={action.state.pending}>
        {invitationRoles.map((item) => <option key={item} value={item}>{roleLabels[item]}</option>)}</select></div>
      <div><label htmlFor="invite-unit">Unidade</label>
        <select id="invite-unit" value={staff ? '' : unitId} required={!staff} onChange={(event) => setUnitId(event.target.value)} disabled={action.state.pending || staff}>
          <option value="">{staff ? 'Equipe sem unidade' : 'Selecione a unidade'}</option>
          {(units.data?.units ?? []).filter((unit) => unit.status === 'ACTIVE').map((unit) => <option key={unit.unitId} value={unit.unitId}>{unit.blockName ? `${unit.blockName} · ` : ''}{unit.label}</option>)}
        </select></div></div>
    <label htmlFor="invite-name">Nome</label>
    <input id="invite-name" required maxLength={160} value={displayName} onChange={(event) => setDisplayName(event.target.value)} disabled={action.state.pending} />
    <div className="two-fields"><div><label htmlFor="invite-email">E-mail da conta</label>
      <input id="invite-email" type="email" required maxLength={320} autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} disabled={action.state.pending} /></div>
      <div><label htmlFor="invite-days">Validade (dias)</label>
        <input id="invite-days" type="number" min={1} max={14} required value={validDays} onChange={(event) => setValidDays(event.target.value)} disabled={action.state.pending} /></div></div>
    <button className="button" type="submit" disabled={action.state.pending || !displayName.trim() || !email.trim() || (!staff && !unitId)}>{action.state.pending ? 'Criando…' : 'Criar convite'}</button>
    <ActionFeedback state={action.state} success={false} />
    {created ? <div className="invitation-link" role="status">
      <strong>Convite criado. Copie o link agora: ele não será exibido novamente.</strong>
      <code>{link}</code>
      <span>Válido até {formatWhen(created.expiresAt, scope.timeZone)}. Envie somente pelo canal combinado com a pessoa convidada.</span>
      <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => void copy()}>{copied ? 'Link copiado' : 'Copiar link'}</button>
        <button className="text-button" type="button" onClick={() => setCreated(null)}>Ocultar link</button></div>
    </div> : null}
  </form>;
}

function InvitationList() {
  const scope = useManagement();
  const queries = useQueryClient();
  const action = useAction();
  const list = useQuery({ queryKey: managementKey(scope, 'invitations'), queryFn: ({ signal }) => management.invitations(scope.tenantId, signal) });
  if (useAccessGuard(list.error)) return null;
  if (list.isPending) return <div className="panel"><Loading label="Consultando convites…" /></div>;
  if (list.isError) return <ErrorState error={list.error} retry={() => void list.refetch()} />;
  const revoke = (invitationId: string, name: string) => void action.run(() => management.revokeInvitation(scope.tenantId, invitationId),
    `Convite de ${name} revogado.`, async () => { await queries.invalidateQueries({ queryKey: managementKey(scope, 'invitations') }); });
  return <>
    <ActionFeedback state={action.state} />
    {list.data.length === 0 ? <div className="panel"><Empty icon="users" title="Nenhum convite">Os convites emitidos aparecerão aqui, sem e-mail ou token.</Empty></div>
      : <ul className="management-list" aria-label="Convites">{list.data.map((item) => <li key={item.invitationId} className="management-row">
        <div><strong>{item.displayName}</strong><span>{roleLabels[item.role] ?? item.role}{item.unitLabel ? ` · ${item.unitLabel}` : ''} · expira {formatWhen(item.expiresAt, scope.timeZone)}</span>
          <span className={`status-pill ${statusTone(item.status)}`}>{invitationStatus[item.status]}</span></div>
        {item.status === 'PENDING' ? <button className="text-button danger-text" type="button" disabled={action.state.pending} onClick={() => revoke(item.invitationId, item.displayName)} aria-label={`Revogar convite de ${item.displayName}`}>Revogar convite</button> : null}
      </li>)}</ul>}
  </>;
}

export function PeopleSection() {
  const [status, setStatus] = useState<MembershipStatus | ''>('ACTIVE');
  return <section className="management-section" aria-labelledby="people-title">
    <div className="section-heading"><div><span className="section-index">PESSOAS</span><h2 id="people-title">Vínculos e convites</h2></div></div>
    <div className="management-columns">
      <div>
        <div className="reservation-filter"><label htmlFor="membership-status">Situação</label>
          <select id="membership-status" value={status} onChange={(event) => setStatus(event.target.value as MembershipStatus | '')}>
            <option value="">Todas</option>{Object.entries(membershipStatusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
        <MembershipList status={status || null} empty="Nenhum vínculo nesta situação." />
      </div>
      <div><InvitationForm /><InvitationList /><RentalsPanel /></div>
    </div>
  </section>;
}
