import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { partners, type OperatorSchedule, type PartnerMember } from '../../api/partners';
import { partnerRoles, type PartnerRole } from '../../api/types';
import { Empty, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { partnerRoleLabels } from '../shell/paths';
import { FailureState, usePartnerAccessGuard, usePartnerScope } from './PartnerPage';
import { contractStatusLabels, emptyMemberDraft, emptyScheduleDraft, memberBody, memberStatusLabels, partnerKey, scheduleBody, scheduleStatusLabels,
  scheduledRoles, type MemberDraft, type ScheduleDraft } from './model';

/** Horários do painel do parceiro: fuso de operação do projeto; a escala usa o fuso do condomínio escolhido. */
const PARTNER_ZONE = 'America/Sao_Paulo';
const OFF_TEXT = 'A hierarquia de parceiros está desligada no servidor deste ambiente (CP_PARTNERS_ENABLED). Quando for ligada, esta seção passa a funcionar sem mudança na tela.';
const tone = (status: string) => status === 'ACTIVE' ? 'status-ready' : 'status-neutral';

interface ActionState { pending: boolean; error: unknown; done: string | null }
/** Uma ação por vez, sem repetição automática; falha incerta pede para atualizar e conferir antes de repetir. */
function useRun() {
  const scope = usePartnerScope();
  const [state, setState] = useState<ActionState>({ pending: false, error: null, done: null });
  async function run<T>(action: () => Promise<T>, done: (result: T) => string, after: () => Promise<void>): Promise<boolean> {
    if (state.pending) return false;
    setState({ pending: true, error: null, done: null });
    try {
      const result = await action();
      await after();
      setState({ pending: false, error: null, done: done(result) });
      return true;
    } catch (error) {
      // Recusa de autorização vai ao tratador único (a tela é trocada); o resto fica na mensagem da ação.
      setState({ pending: false, error: scope.refuse(error) ? null : error, done: null });
      return false;
    }
  }
  return { state, run, clear: () => setState({ pending: false, error: null, done: null }) };
}
function Feedback({ state }: { state: ActionState }) {
  if (state.error) {
    const uncertain = (state.error as { uncertain?: boolean }).uncertain;
    return <Notice tone="error">{errorMessage(state.error)}{uncertain ? ' Atualize a lista e confira antes de repetir.' : ''}</Notice>;
  }
  return state.done ? <Notice tone="success">{state.done}</Notice> : null;
}

function ReasonInline({ id, label, confirm, pending, submit, cancel }: { id: string; label: string; confirm: string; pending: boolean;
  submit(reason: string): void; cancel(): void }) {
  const [reason, setReason] = useState('');
  return <form className="reason-form" onSubmit={(event) => { event.preventDefault(); if (reason.trim()) submit(reason); }}>
    <label htmlFor={id}>{label}</label>
    <textarea id={id} rows={2} maxLength={500} required value={reason} disabled={pending} onChange={(event) => setReason(event.target.value)} />
    <div className="button-row"><button className="button button-danger" type="submit" disabled={pending || !reason.trim()}>{pending ? 'Enviando…' : confirm}</button>
      <button className="button button-secondary" type="button" disabled={pending} onClick={cancel}>Voltar</button></div>
  </form>;
}

function useTeam() {
  const { accountId, context } = usePartnerScope();
  return useQuery({ queryKey: partnerKey(accountId, context.partnerId, 'team'), queryFn: ({ signal }) => partners.team(context.partnerId, signal) });
}

function MemberRow({ member }: { member: PartnerMember }) {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const action = useRun();
  const [revoking, setRevoking] = useState(false);
  const own = member.accountId.toLowerCase() === accountId.toLowerCase();
  const revoke = (reason: string) => void action.run(() => partners.revokeMember(context.partnerId, member.id, reason),
    (result) => `${result.displayName}: papel revogado.`, async () => { setRevoking(false); await queries.invalidateQueries({ queryKey: partnerKey(accountId, context.partnerId) }); });
  return <li className="management-row">
    <div><strong><bdi>{member.displayName}</bdi></strong><span>{partnerRoleLabels[member.role]} · desde {formatWhen(member.createdAt, PARTNER_ZONE)}
      {member.revokedAt ? ` · revogado em ${formatWhen(member.revokedAt, PARTNER_ZONE)}` : ''}{own ? ' · sua conta' : ''}</span>
      <span className={`status-pill ${tone(member.status)}`}>{memberStatusLabels[member.status]}</span></div>
    {/* O gestor não altera o próprio papel: o servidor recusa (PARTNER_SELF_CHANGE_DENIED); a tela só não oferece. */}
    {member.status === 'ACTIVE' && !own && !revoking ? <div className="button-row"><button className="text-button danger-text" type="button" disabled={action.state.pending}
      onClick={() => { action.clear(); setRevoking(true); }} aria-label={`Revogar papel de ${member.displayName}`}>Revogar</button></div> : null}
    {revoking ? <ReasonInline id={`revoke-member-${member.id}`} label="Motivo da revogação" confirm="Confirmar revogação" pending={action.state.pending}
      submit={revoke} cancel={() => setRevoking(false)} /> : null}
    <Feedback state={action.state} />
  </li>;
}

function MemberForm() {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const action = useRun();
  const [draft, setDraft] = useState<MemberDraft>(emptyMemberDraft);
  const [problem, setProblem] = useState<string | null>(null);
  const set = (patch: Partial<MemberDraft>) => setDraft((current) => ({ ...current, ...patch }));
  function submit(event: FormEvent) {
    event.preventDefault();
    const body = memberBody(draft, accountId);
    if (typeof body === 'string') { setProblem(body); return; }
    setProblem(null);
    void action.run(() => partners.addMember(context.partnerId, body), (result) => `${result.displayName}: papel ${partnerRoleLabels[result.role]} adicionado.`,
      async () => { setDraft(emptyMemberDraft()); await queries.invalidateQueries({ queryKey: partnerKey(accountId, context.partnerId) }); });
  }
  return <form className="panel management-form stack-form" onSubmit={submit} aria-labelledby="partner-member-form">
    <h3 id="partner-member-form">Adicionar papel à equipe</h3>
    <p className="field-help">A conta precisa existir; informe o identificador recebido da plataforma. O motivo fica na auditoria.</p>
    <div className="two-fields"><div><label htmlFor="member-account">Conta (UUID)</label>
      <input id="member-account" value={draft.accountId} onChange={(event) => set({ accountId: event.target.value })} autoComplete="off" spellCheck={false} /></div>
      <div><label htmlFor="member-role">Papel</label><select id="member-role" value={draft.role} onChange={(event) => set({ role: event.target.value as PartnerRole | '' })}>
        <option value="">Escolha</option>{partnerRoles.map((role) => <option key={role} value={role}>{partnerRoleLabels[role]}</option>)}</select></div></div>
    <label htmlFor="member-name">Nome de exibição</label>
    <input id="member-name" maxLength={160} value={draft.displayName} onChange={(event) => set({ displayName: event.target.value })} />
    <label htmlFor="member-reason">Motivo</label>
    <textarea id="member-reason" rows={2} maxLength={500} value={draft.reason} onChange={(event) => set({ reason: event.target.value })} />
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending}>{action.state.pending ? 'Enviando…' : 'Adicionar papel'}</button></div>
    <Feedback state={action.state} />
  </form>;
}

export function MemberSection() {
  const team = useTeam();
  const guarded = usePartnerAccessGuard(team.error);
  if (guarded) return null;
  return <section className="management-section" aria-labelledby="partner-team-title">
    <div className="section-heading"><div><span className="section-index">EQUIPE</span><h2 id="partner-team-title">Equipe do parceiro</h2></div></div>
    {team.isPending ? <div className="panel"><Loading label="Consultando a equipe…" /></div>
      : team.isError ? <FailureState error={team.error} retry={() => void team.refetch()} off={OFF_TEXT} subject="Gestão do parceiro" />
        : <div className="management-columns"><div>{team.data.length === 0 ? <div className="panel"><Empty icon="users" title="Nenhum papel na equipe">Os papéis adicionados aparecem aqui.</Empty></div>
          : <ul className="management-list" aria-label="Equipe">{team.data.map((member) => <MemberRow key={member.id} member={member} />)}</ul>}</div>
          <MemberForm /></div>}
  </section>;
}

function ScheduleRow({ schedule, members, tenants }: { schedule: OperatorSchedule; members: readonly PartnerMember[]; tenants: ReadonlyMap<string, { name: string; zone: string }> }) {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const action = useRun();
  const [cancelling, setCancelling] = useState(false);
  const member = members.find((item) => item.id.toLowerCase() === schedule.memberId.toLowerCase());
  const tenant = tenants.get(schedule.tenantId.toLowerCase());
  const zone = tenant?.zone ?? PARTNER_ZONE;
  const cancel = (reason: string) => void action.run(() => partners.cancelSchedule(context.partnerId, schedule.id, reason), () => 'Escala cancelada.',
    async () => { setCancelling(false); await queries.invalidateQueries({ queryKey: partnerKey(accountId, context.partnerId, 'schedules') }); });
  return <li className="management-row">
    <div><strong><bdi>{member?.displayName ?? 'Papel fora da equipe listada'}</bdi></strong>
      <span><bdi>{tenant?.name ?? 'Condomínio fora do alcance atual'}</bdi> · {formatWhen(schedule.startsAt, zone)} até {formatWhen(schedule.endsAt, zone)}</span>
      <span className={`status-pill ${tone(schedule.status)}`}>{scheduleStatusLabels[schedule.status]}</span></div>
    {schedule.status === 'ACTIVE' && !cancelling ? <div className="button-row"><button className="text-button danger-text" type="button" disabled={action.state.pending}
      onClick={() => { action.clear(); setCancelling(true); }}>Cancelar escala</button></div> : null}
    {cancelling ? <ReasonInline id={`cancel-schedule-${schedule.id}`} label="Motivo do cancelamento" confirm="Confirmar cancelamento" pending={action.state.pending}
      submit={cancel} cancel={() => setCancelling(false)} /> : null}
    <Feedback state={action.state} />
  </li>;
}

function ScheduleForm({ members, tenants }: { members: readonly PartnerMember[]; tenants: ReadonlyMap<string, { name: string; zone: string }> }) {
  const { accountId, context } = usePartnerScope();
  const queries = useQueryClient();
  const action = useRun();
  const [draft, setDraft] = useState<ScheduleDraft>(emptyScheduleDraft);
  const [problem, setProblem] = useState<string | null>(null);
  const set = (patch: Partial<ScheduleDraft>) => setDraft((current) => ({ ...current, ...patch }));
  const eligible = members.filter((member) => member.status === 'ACTIVE' && scheduledRoles.includes(member.role));
  const zone = tenants.get(draft.tenantId.toLowerCase())?.zone;
  function submit(event: FormEvent) {
    event.preventDefault();
    const body = scheduleBody(draft, zone);
    if (typeof body === 'string') { setProblem(body); return; }
    setProblem(null);
    void action.run(() => partners.createSchedule(context.partnerId, body), () => 'Escala criada.',
      async () => { setDraft(emptyScheduleDraft()); await queries.invalidateQueries({ queryKey: partnerKey(accountId, context.partnerId, 'schedules') }); });
  }
  return <form className="panel management-form stack-form" onSubmit={submit} aria-labelledby="partner-schedule-form">
    <h3 id="partner-schedule-form">Nova escala</h3>
    <p className="field-help">Só supervisor e operador remoto recebem escala, em condomínio com contrato ativo, por no máximo 31 dias. Horários no fuso do condomínio; sem início, vale a partir de agora.</p>
    <div className="two-fields"><div><label htmlFor="schedule-member">Pessoa</label><select id="schedule-member" value={draft.memberId} onChange={(event) => set({ memberId: event.target.value })}>
      <option value="">Escolha</option>{eligible.map((member) => <option key={member.id} value={member.id}>{member.displayName} · {partnerRoleLabels[member.role]}</option>)}</select></div>
      <div><label htmlFor="schedule-tenant">Condomínio</label><select id="schedule-tenant" value={draft.tenantId} onChange={(event) => set({ tenantId: event.target.value })}>
        <option value="">Escolha</option>{[...tenants.entries()].map(([id, tenant]) => <option key={id} value={id}>{tenant.name}</option>)}</select></div></div>
    <div className="two-fields"><div><label htmlFor="schedule-start">Início (opcional)</label>
      <input id="schedule-start" type="datetime-local" value={draft.startsAt} onChange={(event) => set({ startsAt: event.target.value })} /></div>
      <div><label htmlFor="schedule-end">Fim</label><input id="schedule-end" type="datetime-local" value={draft.endsAt} onChange={(event) => set({ endsAt: event.target.value })} /></div></div>
    <label htmlFor="schedule-reason">Motivo</label>
    <textarea id="schedule-reason" rows={2} maxLength={500} value={draft.reason} onChange={(event) => set({ reason: event.target.value })} />
    {problem ? <Notice tone="error">{problem}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={action.state.pending || eligible.length === 0 || tenants.size === 0}>{action.state.pending ? 'Enviando…' : 'Criar escala'}</button></div>
    {eligible.length === 0 ? <p className="field-help">Nenhum supervisor ou operador remoto ativo na equipe.</p> : null}
    {tenants.size === 0 ? <p className="field-help">Nenhum condomínio com contrato ativo na carteira.</p> : null}
    <Feedback state={action.state} />
  </form>;
}

export function ScheduleSection() {
  const { accountId, context } = usePartnerScope();
  const team = useTeam();
  const schedules = useQuery({ queryKey: partnerKey(accountId, context.partnerId, 'schedules'), queryFn: ({ signal }) => partners.schedules(context.partnerId, signal) });
  // Condomínios para escala: os alcançados pelo papel agora (contrato ativo), com nome e fuso informados por /v1/me.
  const tenants = new Map(context.tenants.map((tenant) => [tenant.tenantId.toLowerCase(), { name: tenant.tenantName, zone: tenant.timeZone }]));
  const failed = schedules.error ?? team.error;
  const guarded = usePartnerAccessGuard(failed);
  if (guarded) return null;
  return <section className="management-section" aria-labelledby="partner-schedule-title">
    <div className="section-heading"><div><span className="section-index">ESCALA</span><h2 id="partner-schedule-title">Escala da central</h2></div></div>
    {schedules.isPending || team.isPending ? <div className="panel"><Loading label="Consultando a escala…" /></div>
      : failed ? <FailureState error={failed} retry={() => { void schedules.refetch(); void team.refetch(); }} off={OFF_TEXT} subject="Gestão do parceiro" />
        : <div className="management-columns"><div>{schedules.data!.length === 0 ? <div className="panel"><Empty icon="calendar" title="Nenhuma escala">As escalas criadas aparecem aqui.</Empty></div>
          : <ul className="management-list" aria-label="Escalas">{schedules.data!.map((item) => <ScheduleRow key={item.id} schedule={item} members={team.data!} tenants={tenants} />)}</ul>}</div>
          <ScheduleForm members={team.data!} tenants={tenants} /></div>}
  </section>;
}

export function PortfolioSection() {
  const { accountId, context } = usePartnerScope();
  const portfolio = useQuery({ queryKey: partnerKey(accountId, context.partnerId, 'portfolio'), queryFn: ({ signal }) => partners.portfolio(context.partnerId, signal) });
  const guarded = usePartnerAccessGuard(portfolio.error);
  if (guarded) return null;
  return <section className="management-section" aria-labelledby="partner-portfolio-title">
    <div className="section-heading"><div><span className="section-index">CARTEIRA</span><h2 id="partner-portfolio-title">Carteira de condomínios</h2></div></div>
    <p className="field-help">Contratos do parceiro com cada condomínio. Vincular ou encerrar contrato é feito pela central da plataforma.</p>
    {portfolio.isPending ? <div className="panel"><Loading label="Consultando a carteira…" /></div>
      : portfolio.isError ? <FailureState error={portfolio.error} retry={() => void portfolio.refetch()} off={OFF_TEXT} subject="Gestão do parceiro" />
        : portfolio.data.length === 0 ? <div className="panel"><Empty icon="building" title="Nenhum contrato">Os contratos vinculados pela plataforma aparecem aqui.</Empty></div>
          : <ul className="management-list" aria-label="Contratos">{portfolio.data.map((contract) => <li key={contract.id} className="management-row">
            <div><strong><bdi>{contract.tenantName ?? 'Condomínio fora do alcance atual'}</bdi></strong>
              <span>Operação de segurança · desde {formatWhen(contract.startsAt, PARTNER_ZONE)}{contract.endsAt ? ` · fim previsto ${formatWhen(contract.endsAt, PARTNER_ZONE)}` : ''}
                {contract.endedAt ? ` · encerrado em ${formatWhen(contract.endedAt, PARTNER_ZONE)}` : ''}</span>
              <span className={`status-pill ${tone(contract.status)}`}>{contractStatusLabels[contract.status]}</span></div></li>)}</ul>}
  </section>;
}
