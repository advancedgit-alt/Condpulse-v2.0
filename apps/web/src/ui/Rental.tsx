import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import type { InvitationCreated } from '../api/management';
import { rental, tenantRoles, type OwnerRentalView, type RentalOccupant, type TenantRole } from '../api/rental';
import { useAuth } from './Auth';
import { Empty, ErrorState, Loading, Notice, errorMessage } from './primitives';
import { formatWhen } from './dateTime';
import { invitationLink } from './management/People';
import { ReasonForm, membershipStatusLabels, roleLabels, statusTone } from './management/shared';
import './management/Management.css';

export const rentalKey = (accountId: string, tenantId: string, unitId: string) =>
  ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'rental'] as const;
const invitationStatus: Record<string, string> = { PENDING: 'Pendente', ACCEPTED: 'Aceito', REVOKED: 'Revogado', EXPIRED: 'Expirado' };
const tenantRoleLabels: Record<TenantRole, string> = { RESPONSIBLE: 'Inquilino responsável', RESIDENT: 'Morador (inquilino)' };

/** Uma ação por vez; falha incerta pede para atualizar antes de repetir (remoção e convite não são repetidos sozinhos). */
function useOwnerAction() {
  const [state, setState] = useState<{ pending: boolean; error: unknown; done: string | null }>({ pending: false, error: null, done: null });
  async function run<T>(action: () => Promise<T>, done: (result: T) => string, after?: (result: T) => Promise<void> | void) {
    if (state.pending) return;
    setState({ pending: true, error: null, done: null });
    try { const result = await action(); await after?.(result); setState({ pending: false, error: null, done: done(result) }); }
    catch (error) { setState({ pending: false, error, done: null }); }
  }
  return { state, run };
}
function Feedback({ state }: { state: { error: unknown; done: string | null } }) {
  if (state.done) return <Notice tone="success">{state.done}</Notice>;
  if (!state.error) return null;
  const uncertain = !(state.error instanceof ApiError) || state.error.uncertain || state.error.status >= 500;
  return <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(state.error)}{uncertain ? ' Atualize a página e confira antes de repetir.' : ''}</Notice>;
}

function OccupantItem({ item, tenantId, unitId, timeZone, onDone }: { item: RentalOccupant; tenantId: string; unitId: string; timeZone: string;
  onDone(text: string): Promise<void> }) {
  const action = useOwnerAction();
  const [removing, setRemoving] = useState(false);
  // Versão lida ao abrir a confirmação: uma nova consulta não troca o alvo da remoção em andamento.
  const [readVersion, setReadVersion] = useState(item.version);
  return <li className="management-row">
    <div><strong><bdi>{item.displayName}</bdi></strong><span>{roleLabels[item.role]} · desde {formatWhen(item.validFrom, timeZone)}</span>
      <span className={`status-pill ${statusTone(item.status)}`}>{membershipStatusLabels[item.status]}</span></div>
    {!removing ? <button className="text-button danger-text" type="button" disabled={action.state.pending}
      onClick={() => { setReadVersion(item.version); setRemoving(true); }} aria-label={`Remover ${item.displayName}`}>Remover</button>
      : <ReasonForm id={`remove-occupant-${item.membershipId}`} label="Motivo da remoção" required pending={action.state.pending}
        confirmLabel="Confirmar remoção" tone="danger" cancel={() => setRemoving(false)}
        submit={(reason) => { if (reason) void action.run(() => rental.remove(tenantId, unitId, item.membershipId, readVersion, reason.slice(0, 250)),
          () => '', async (removed) => { setRemoving(false);
            await onDone(`${removed.displayName}: vínculo encerrado. O acesso e as credenciais deixam de valer.`); }); }} />}
    {action.state.error ? <Feedback state={action.state} /> : null}
  </li>;
}

function TenantInvitationForm({ tenantId, unitId, timeZone, onDone }: { tenantId: string; unitId: string; timeZone: string; onDone(): Promise<void> }) {
  const action = useOwnerAction();
  const [role, setRole] = useState<TenantRole>('RESPONSIBLE');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [validDays, setValidDays] = useState('7');
  const [created, setCreated] = useState<InvitationCreated | null>(null);
  const [copied, setCopied] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setCreated(null); setCopied(false);
    await action.run(() => rental.invite(tenantId, unitId, { role, displayName: displayName.trim(), email: email.trim(), validDays: Number(validDays) }),
      () => 'Convite criado.', async (result) => { setCreated(result); setDisplayName(''); setEmail(''); await onDone(); });
  }
  const link = created ? invitationLink(window.location.origin, created.token) : '';
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-label="Convidar inquilino">
    <h3>Convidar inquilino</h3>
    <p className="field-help">A pessoa entra com uma conta criada com o mesmo e-mail. O vínculo fica pendente até a aprovação da gestão.</p>
    <div className="two-fields"><div><label htmlFor="tenant-role">Papel</label>
      <select id="tenant-role" value={role} onChange={(event) => setRole(event.target.value as TenantRole)} disabled={action.state.pending}>
        {tenantRoles.map((item) => <option key={item} value={item}>{tenantRoleLabels[item]}</option>)}</select></div>
      <div><label htmlFor="tenant-days">Validade (dias)</label>
        <input id="tenant-days" type="number" min={1} max={14} required value={validDays} onChange={(event) => setValidDays(event.target.value)} disabled={action.state.pending} /></div></div>
    <label htmlFor="tenant-name">Nome</label>
    <input id="tenant-name" required maxLength={160} value={displayName} onChange={(event) => setDisplayName(event.target.value)} disabled={action.state.pending} />
    <label htmlFor="tenant-email">E-mail da conta</label>
    <input id="tenant-email" type="email" required maxLength={320} autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} disabled={action.state.pending} />
    <button className="button" type="submit" disabled={action.state.pending || !displayName.trim() || !email.trim()}>{action.state.pending ? 'Criando…' : 'Criar convite'}</button>
    {action.state.error ? <Feedback state={action.state} /> : null}
    {created ? <div className="invitation-link" role="status">
      <strong>Convite criado. Copie o link agora: ele não será exibido novamente.</strong>
      <code>{link}</code>
      <span>Válido até {formatWhen(created.expiresAt, timeZone)}. Envie somente pelo canal combinado com o inquilino.</span>
      <div className="button-row"><button className="button button-secondary button-compact" type="button"
        onClick={() => void navigator.clipboard.writeText(link).then(() => setCopied(true), () => setCopied(false))}>{copied ? 'Link copiado' : 'Copiar link'}</button>
        <button className="text-button" type="button" onClick={() => setCreated(null)}>Ocultar link</button></div>
    </div> : null}
  </form>;
}

function RentedUnit({ view, tenantId, timeZone, refresh }: { view: OwnerRentalView; tenantId: string; timeZone: string; refresh(): Promise<void> }) {
  const action = useOwnerAction();
  const [removed, setRemoved] = useState<string | null>(null);
  return <>
    {removed ? <Notice tone="success">{removed}</Notice> : null}
    <Notice tone="info">Unidade marcada como alugada pela gestão{view.rentedSince ? ` desde ${formatWhen(view.rentedSince, timeZone)}` : ''}. Enquanto
      durar a locação, você não vê reservas, encomendas, visitas, pedidos, avisos ou acessos da unidade: só inclui e remove o inquilino.</Notice>
    <div className="management-columns">
      <div>
        <h3>Ocupantes</h3>
        {view.occupants.length === 0 ? <div className="panel"><Empty icon="users" title="Nenhum ocupante">Convide o inquilino para que ele solicite o acesso.</Empty></div>
          : <ul className="management-list" aria-label="Ocupantes da unidade">{view.occupants.map((item) =>
            <OccupantItem key={item.membershipId} item={item} tenantId={tenantId} unitId={view.unitId} timeZone={timeZone}
              onDone={async (text) => { setRemoved(text); await refresh(); }} />)}</ul>}
      </div>
      <div>
        <TenantInvitationForm tenantId={tenantId} unitId={view.unitId} timeZone={timeZone} onDone={refresh} />
        <Feedback state={action.state} />
        {view.invitations.length > 0 ? <ul className="management-list" aria-label="Convites de inquilino">{view.invitations.map((item) =>
          <li key={item.invitationId} className="management-row">
            <div><strong><bdi>{item.displayName}</bdi></strong><span>{roleLabels[item.role] ?? item.role} · expira {formatWhen(item.expiresAt, timeZone)}</span>
              <span className={`status-pill ${statusTone(item.status)}`}>{invitationStatus[item.status]}</span></div>
            {item.status === 'PENDING' ? <button className="text-button danger-text" type="button" disabled={action.state.pending}
              onClick={() => void action.run(() => rental.revokeInvitation(tenantId, view.unitId, item.invitationId), () => `Convite de ${item.displayName} revogado.`, refresh)}
              aria-label={`Revogar convite de ${item.displayName}`}>Revogar convite</button> : null}
          </li>)}</ul> : null}
      </div>
    </div>
  </>;
}

/** Área do proprietário (vínculo OWNER): locação marcada pela gestão, ocupantes e convites de inquilino. */
export function OwnerRentalPage() {
  const { identity } = useAuth();
  const { tenantId = '', unitId = '' } = useParams();
  const queries = useQueryClient();
  const membership = identity?.memberships.find((item) => item.tenantId === tenantId && item.unitId === unitId && item.role === 'OWNER');
  const key = rentalKey(identity?.accountId ?? '', tenantId, unitId);
  const view = useQuery({ queryKey: key, queryFn: ({ signal }) => rental.view(tenantId, unitId, signal), enabled: Boolean(membership) });
  if (!identity || !membership) return <div className="panel"><Empty title="Unidade indisponível">Esta área é do proprietário da unidade.</Empty>
    <Link className="button button-secondary" to="/contextos">Selecionar unidade</Link></div>;
  const refresh = async () => { await queries.invalidateQueries({ queryKey: key }); };
  return <section className="management-section" aria-labelledby="rental-title">
    <div className="section-heading"><div><span className="section-index">PROPRIETÁRIO</span>
      <h2 id="rental-title">Inquilinos{view.data?.unitLabel ? <> · <bdi>{view.data.unitLabel}</bdi></> : null}</h2></div></div>
    {view.isPending ? <div className="panel"><Loading label="Consultando a locação…" /></div>
      : view.isError ? <ErrorState error={view.error} retry={() => void view.refetch()} />
      : view.data.rented ? <RentedUnit view={view.data} tenantId={tenantId} timeZone={membership.timeZone} refresh={refresh} />
      : <div className="panel"><Empty icon="home" title="Unidade não alugada">Quando a gestão marcar a unidade como alugada, você poderá incluir e
        remover o inquilino por aqui. Durante a locação, os demais dados da unidade deixam de aparecer para você.</Empty></div>}
  </section>;
}
