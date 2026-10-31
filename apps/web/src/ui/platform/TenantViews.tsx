import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { newOperationKey } from '../../api/client';
import { managerInvitationLink, platform, type CreateTenantInput, type InitialAmenity, type PlatformTenant, type TenantCreated } from '../../api/platform';
import { displayText } from '../../api/text';
import { amenityInput, cents } from '../management/Setup';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen, money } from '../dateTime';
import { PLATFORM_ZONE, isUncertain, platformKey, react, useAccessGuard, usePlatform, type Outcome } from './shared';
import { billingText, originText, parseUnitLines, usageText, validTimeZone } from './tenants';

const weekDayNames = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'];
const ZONES = ['America/Sao_Paulo', 'America/Bahia', 'America/Fortaleza', 'America/Recife', 'America/Belem', 'America/Manaus',
  'America/Cuiaba', 'America/Campo_Grande', 'America/Porto_Velho', 'America/Boa_Vista', 'America/Rio_Branco', 'America/Noronha'];

/**
 * Link do convite de gestor, mostrado uma única vez: o banco guarda só o hash do token. O token vai no fragmento do
 * link (não chega a servidores nem a logs de acesso).
 */
export function InvitationLinkNotice({ token, expiresAt, manager }: { token: string; expiresAt: string; manager: string }) {
  const link = managerInvitationLink(window.location.origin, token);
  const [copied, setCopied] = useState<boolean | null>(null);
  async function copy() {
    try { await navigator.clipboard.writeText(link); setCopied(true); } catch { setCopied(false); }
  }
  return <div className="panel invitation-link" role="group" aria-label="Convite de gestor emitido">
    <Notice tone="warning">Este link aparece só agora. Envie a <bdi>{manager}</bdi> por um canal privado: ele vale uma única vez, para o e-mail
      informado, até {formatWhen(expiresAt, PLATFORM_ZONE)}. Quem aceitar entra com a própria conta do CondPulse (e-mail confirmado).</Notice>
    <label htmlFor="manager-invitation-link">Link do convite</label>
    <input id="manager-invitation-link" readOnly value={link} onFocus={(event) => event.currentTarget.select()} />
    <div className="button-row"><button className="button button-secondary button-compact" type="button" onClick={() => void copy()}>Copiar link</button>
      {copied === true ? <span role="status">Link copiado.</span> : copied === false ? <span role="status">Não foi possível copiar; selecione o texto do campo.</span> : null}</div>
  </div>;
}

interface AmenityDraft {
  name: string; capacity: string; approvalRequired: boolean; fee: string; buffer: string; active: boolean;
  weekDays: number[]; opensAt: string; closesAt: string; minimumLead: string; maximumAdvance: string;
  maximumDuration: string; maximumActive: string; cancellationLead: string; reason: string;
}
const emptyAmenity: AmenityDraft = {
  name: '', capacity: '', approvalRequired: true, fee: '0,00', buffer: '0', active: true, weekDays: [1, 2, 3, 4, 5, 6, 7],
  opensAt: '08:00', closesAt: '22:00', minimumLead: '0', maximumAdvance: '30', maximumDuration: '240', maximumActive: '1',
  cancellationLead: '0', reason: '',
};
export interface TenantDraft {
  name: string; timeZone: string; units: string; managerName: string; managerEmail: string; validDays: string;
  withAmenity: boolean; amenity: AmenityDraft; reason: string;
}
export const emptyTenantDraft: TenantDraft = {
  name: '', timeZone: 'America/Sao_Paulo', units: '', managerName: '', managerEmail: '', validDays: '7', withAmenity: false,
  amenity: emptyAmenity, reason: '',
};

/** Validação local que orienta o preenchimento; o servidor confere tudo de novo. Devolve o pedido ou a mensagem. */
export function tenantInput(draft: TenantDraft): CreateTenantInput | string {
  const name = displayText(draft.name, 160);
  if (name === null) return 'Informe o nome do condomínio com até 160 caracteres visíveis.';
  const timeZone = draft.timeZone.trim();
  if (!validTimeZone(timeZone)) return 'Informe um fuso horário IANA válido, por exemplo America/Sao_Paulo.';
  const units = parseUnitLines(draft.units);
  if ('error' in units) return units.error;
  const managerName = displayText(draft.managerName, 160);
  if (managerName === null) return 'Informe o nome do primeiro gestor com até 160 caracteres visíveis.';
  const email = draft.managerEmail.trim().toLowerCase();
  if (email.length > 320 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return 'Informe um e-mail válido para o gestor.';
  const days = /^\d{1,2}$/.test(draft.validDays.trim()) ? Number(draft.validDays.trim()) : NaN;
  if (!(days >= 1 && days <= 14)) return 'A validade do convite deve ter entre 1 e 14 dias.';
  let amenity: InitialAmenity | null = null;
  if (draft.withAmenity) {
    const area = amenityInput(draft.amenity, null);
    if (typeof area === 'string') return `Área inicial: ${area}`;
    amenity = { name: area.name, capacity: area.capacity, approvalRequired: area.approvalRequired, feeCents: area.feeCents,
      bufferMinutes: area.bufferMinutes, policy: { ...area.policy, schemaVersion: 1 } };
  }
  const reason = displayText(draft.reason, 500);
  if (reason === null) return 'Informe o motivo do cadastro (contrato, aprovação), com até 500 caracteres.';
  return { name, timeZone, units: units.units, manager: { displayName: managerName, email, validDays: days }, amenity, reason };
}

/**
 * Criação de condomínio pelo painel. A chave de operação nasce com o formulário e é mantida nas repetições: depois de
 * um resultado incerto os dados ficam travados e o reenvio com a mesma chave devolve o condomínio já criado, sem
 * duplicar.
 */
export function TenantCreateForm({ onCreated, onCancel }: { onCreated(result: TenantCreated, manager: string): void; onCancel(): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [draft, setDraft] = useState<TenantDraft>(emptyTenantDraft);
  const [key, setKey] = useState(newOperationKey);
  const [pending, setPending] = useState(false);
  const [validation, setValidation] = useState('');
  const [failure, setFailure] = useState<unknown>(null);
  const uncertain = failure !== null && isUncertain(failure);
  const locked = pending || uncertain;
  const set = <K extends keyof TenantDraft>(field: K, value: TenantDraft[K]) => setDraft((current) => ({ ...current, [field]: value }));
  const setArea = <K extends keyof AmenityDraft>(field: K, value: AmenityDraft[K]) =>
    setDraft((current) => ({ ...current, amenity: { ...current.amenity, [field]: value } }));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    const input = tenantInput(draft);
    if (typeof input === 'string') { setValidation(input); return; }
    setValidation(''); setPending(true); setFailure(null);
    try {
      const result = await platform.createTenant(input, key);
      await queries.invalidateQueries({ queryKey: platformKey(scope.accountId) });
      onCreated(result, input.manager.displayName);
    } catch (error) {
      react(error, scope);
      setFailure(error);
    } finally { setPending(false); }
  }
  function discard() { setFailure(null); setKey(newOperationKey()); setDraft(emptyTenantDraft); onCancel(); }
  const preview = cents(draft.amenity.fee);
  const units = parseUnitLines(draft.units);
  const areaField = (field: 'capacity' | 'buffer' | 'minimumLead' | 'maximumAdvance' | 'maximumDuration' | 'maximumActive' | 'cancellationLead', label: string) =>
    <div><label htmlFor={`new-tenant-area-${field}`}>{label}</label>
      <input id={`new-tenant-area-${field}`} inputMode="numeric" required value={draft.amenity[field]} onChange={(event) => setArea(field, event.target.value)} disabled={locked} /></div>;
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-label="Novo condomínio">
    <h3>Novo condomínio</h3>
    <p className="field-help">Cria o condomínio ativo, as unidades e, se quiser, uma área de reserva. O primeiro gestor recebe um convite de uso
      único: ao aceitar com a própria conta (o mesmo e-mail, já confirmado), a gestão é liberada. Tudo fica na auditoria da plataforma.</p>
    <label htmlFor="new-tenant-name">Nome do condomínio</label>
    <input id="new-tenant-name" required maxLength={400} value={draft.name} onChange={(event) => set('name', event.target.value)} disabled={locked} />
    <label htmlFor="new-tenant-zone">Fuso horário (IANA)</label>
    <input id="new-tenant-zone" required list="new-tenant-zones" value={draft.timeZone} onChange={(event) => set('timeZone', event.target.value)} disabled={locked}
      aria-describedby="new-tenant-zone-help" />
    <datalist id="new-tenant-zones">{ZONES.map((zone) => <option key={zone} value={zone} />)}</datalist>
    <small className="field-help" id="new-tenant-zone-help">Horários de reservas, visitas e avisos do condomínio usam este fuso.</small>
    <label htmlFor="new-tenant-units">Unidades (uma por linha)</label>
    <textarea id="new-tenant-units" required rows={5} value={draft.units} onChange={(event) => set('units', event.target.value)} disabled={locked}
      aria-describedby="new-tenant-units-help" />
    <small className="field-help" id="new-tenant-units-help">{draft.units.trim() && 'units' in units ? `${units.units.length} unidade(s). ` : ''}Até 250, com 1 a 40
      caracteres cada, sem repetir. Blocos e importação em lote continuam na gestão local.</small>
    <fieldset className="stack-form" disabled={locked}><legend>Primeiro gestor</legend>
      <label htmlFor="new-tenant-manager-name">Nome</label>
      <input id="new-tenant-manager-name" required maxLength={400} value={draft.managerName} onChange={(event) => set('managerName', event.target.value)} />
      <label htmlFor="new-tenant-manager-email">E-mail da conta no CondPulse</label>
      <input id="new-tenant-manager-email" type="email" required maxLength={320} value={draft.managerEmail} onChange={(event) => set('managerEmail', event.target.value)}
        aria-describedby="new-tenant-manager-help" />
      <small className="field-help" id="new-tenant-manager-help">O e-mail não é guardado; só serve para conferir quem aceita o convite.</small>
      <label htmlFor="new-tenant-manager-days">Validade do convite (dias)</label>
      <input id="new-tenant-manager-days" inputMode="numeric" required value={draft.validDays} onChange={(event) => set('validDays', event.target.value)} />
    </fieldset>
    <label className="checkbox-label"><input type="checkbox" checked={draft.withAmenity} onChange={(event) => set('withAmenity', event.target.checked)} disabled={locked} />
      <span>Cadastrar uma área de reserva inicial</span></label>
    {draft.withAmenity ? <fieldset className="stack-form" disabled={locked}><legend>Área inicial</legend>
      <label htmlFor="new-tenant-area-name">Nome da área</label>
      <input id="new-tenant-area-name" required maxLength={120} value={draft.amenity.name} onChange={(event) => setArea('name', event.target.value)} />
      <div className="two-fields">{areaField('capacity', 'Capacidade (pessoas)')}{areaField('buffer', 'Limpeza após o uso (min)')}</div>
      <div className="two-fields"><div><label htmlFor="new-tenant-area-fee">Taxa informativa (R$)</label>
        <input id="new-tenant-area-fee" inputMode="decimal" required value={draft.amenity.fee} onChange={(event) => setArea('fee', event.target.value)} />
        <small className="field-help">{preview === null ? 'Use o formato 1.234,56.' : `Será exibida como ${money(preview)}.`}</small></div>
        <label className="checkbox-label"><input type="checkbox" checked={draft.amenity.approvalRequired} onChange={(event) => setArea('approvalRequired', event.target.checked)} />
          <span>Pedidos exigem aprovação da gestão</span></label></div>
      <fieldset className="weekday-fieldset"><legend>Dias permitidos</legend>
        {weekDayNames.map((label, index) => <label key={label} className="weekday-option"><input type="checkbox" checked={draft.amenity.weekDays.includes(index + 1)}
          onChange={(event) => setArea('weekDays', event.target.checked ? [...draft.amenity.weekDays, index + 1] : draft.amenity.weekDays.filter((day) => day !== index + 1))} />
          <span>{label}</span></label>)}</fieldset>
      <div className="two-fields"><div><label htmlFor="new-tenant-area-opens">Abertura</label><input id="new-tenant-area-opens" type="time" required value={draft.amenity.opensAt} onChange={(event) => setArea('opensAt', event.target.value)} /></div>
        <div><label htmlFor="new-tenant-area-closes">Fechamento</label><input id="new-tenant-area-closes" type="time" required value={draft.amenity.closesAt} onChange={(event) => setArea('closesAt', event.target.value)} /></div></div>
      <div className="two-fields">{areaField('minimumLead', 'Antecedência mínima (min)')}{areaField('maximumAdvance', 'Janela máxima (dias)')}</div>
      <div className="two-fields">{areaField('maximumDuration', 'Duração máxima (min)')}{areaField('maximumActive', 'Reservas ativas por unidade')}</div>
      <div className="two-fields">{areaField('cancellationLead', 'Prazo de cancelamento (min antes do início)')}<div /></div>
    </fieldset> : null}
    <label htmlFor="new-tenant-reason">Motivo (fica na auditoria da plataforma)</label>
    <textarea id="new-tenant-reason" required maxLength={500} rows={2} value={draft.reason} onChange={(event) => set('reason', event.target.value)} disabled={locked} />
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain
      ? ' O cadastro pode ter sido gravado. Os dados ficaram travados: reenvie sem alterar (a mesma operação devolve o condomínio já criado, sem duplicar) ou descarte e confira a lista.'
      : ''}</Notice> : null}
    <div className="button-row">
      <button className="button" type="submit" disabled={pending}>{pending ? 'Criando…' : uncertain ? 'Reenviar os mesmos dados' : 'Criar condomínio'}</button>
      <button className="button button-secondary" type="button" onClick={discard} disabled={pending}>{uncertain ? 'Descartar e conferir a lista' : 'Cancelar'}</button>
    </div>
  </form>;
}

/** Resultado da criação: confirmação do servidor; repetição da mesma operação não mostra token (emitir outro convite). */
export function creationOutcome(result: TenantCreated): Outcome {
  return result.applied
    ? { tone: 'success', text: `Condomínio "${result.tenant.name}" criado com ${result.tenant.usage?.activeUnits ?? 'as'} unidade(s). Envie o link do convite ao primeiro gestor.` }
    : { tone: 'info', text: `O condomínio "${result.tenant.name}" já tinha sido criado por esta mesma operação. Nada foi gravado de novo; o link do convite não é mostrado outra vez — emita um novo convite na ficha, se precisar.` };
}

const invitationLabels = { PENDING: 'Pendente', EXPIRED: 'Expirado', ACCEPTED: 'Aceito', REVOKED: 'Revogado' } as const;
const managerLabels = { PENDING: 'Pendente', ACTIVE: 'Vigente', SUSPENDED: 'Suspenso' } as const;

/** Ficha do condomínio: dados, uso agregado, assinatura, gestores, convites de gestor e eventos recentes da plataforma. */
export function TenantDetailView({ tenantId, editor, describe }: { tenantId: string;
  editor(tenant: PlatformTenant, close: (result: Outcome | null) => void): ReactNode; describe(action: string, details: Record<string, string>): ReactNode }) {
  const scope = usePlatform();
  const detail = useQuery({ queryKey: platformKey(scope.accountId, 'tenant', tenantId), queryFn: ({ signal }) => platform.tenant(tenantId, signal) });
  const [editing, setEditing] = useState(false);
  const [inviting, setInviting] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [issued, setIssued] = useState<{ token: string; expiresAt: string; manager: string } | null>(null);
  const [revoking, setRevoking] = useState<string | null>(null);
  if (useAccessGuard(detail.error)) return null;
  const manage = scope.session.capabilities.includes('tenants.manage');
  const back = <Link className="button button-secondary button-compact" to="/master/condominios">Voltar para a lista</Link>;
  if (detail.isPending) return <div className="panel"><Loading label="Consultando o condomínio…" /></div>;
  if (detail.isError && !detail.data) return <section className="management-section">{back}<ErrorState error={detail.error} retry={() => void detail.refetch()} /></section>;
  const { tenant, managers, managerInvitations, recentEvents } = detail.data!;
  const billing = billingText(tenant);
  const canInvite = manage && tenant.active && tenant.usage !== null && tenant.usage.activeManagers === 0;
  return <section className="management-section" aria-labelledby="tenant-detail-title">
    <div className="section-heading"><div><span className="section-index">CONDOMÍNIO</span><h2 id="tenant-detail-title"><bdi>{tenant.name}</bdi></h2></div>
      <div className="button-row">{back}<button className="button button-secondary button-compact" type="button" disabled={detail.isFetching} onClick={() => void detail.refetch()}>Atualizar</button></div></div>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {issued ? <InvitationLinkNotice token={issued.token} expiresAt={issued.expiresAt} manager={issued.manager} /> : null}
    <div className="panel"><p><span className={`status-pill ${tenant.active ? 'status-ready' : 'status-neutral'}`}>{tenant.active ? 'Ativo' : 'Suspenso'}</span></p>
      <ul className="detail-list">
        <li>Fuso {tenant.timeZone} · versão {tenant.version}</li>
        <li>Criado em {formatWhen(tenant.createdAt, PLATFORM_ZONE)} · {originText(tenant.origin)}</li>
        <li>{usageText(tenant, PLATFORM_ZONE)}</li>
        {billing ? <li>{billing}</li> : null}
        <li>Código <code>{tenant.tenantId}</code></li>
      </ul>
      <div className="button-row"><Link className="button button-secondary button-compact" to={`/implantacao/${tenant.tenantId}/roteiro`}>Abrir implantação técnica</Link>
        {scope.session.capabilities.includes('technical_grants.manage') ? <Link className="button button-secondary button-compact"
          to={`/master/concessoes?condominio=${tenant.tenantId}`}>Concessões técnicas</Link> : null}</div>
      <p className="field-help">A implantação exige concessão técnica vigente desta conta no condomínio, além do papel de plataforma.</p>
      {!tenant.active ? <Notice tone="warning">Suspenso: moradores, portaria e gestão local não acessam o condomínio. Nenhum dado foi apagado.</Notice> : null}
      {manage && !editing ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
        onClick={() => { setOutcome(null); setEditing(true); }}>Alterar nome, suspender ou reativar</button></div> : null}
      {manage && editing ? editor(tenant, (result) => { setEditing(false); if (result) setOutcome(result); void detail.refetch(); }) : null}
    </div>
    <h3>Gestores</h3>
    {managers.length === 0 ? <div className="panel"><Empty icon="shield" title="Sem gestor">O condomínio ainda não tem gestor. Emita um convite para o primeiro gestor.</Empty></div>
      : <ol className="management-list" aria-label="Gestores">{managers.map((item) => <li key={item.membershipId} className="management-row"><div>
        <strong><bdi>{item.displayName}</bdi></strong> <span className="status-pill status-neutral">{managerLabels[item.status]}</span>
        <span>Desde {formatWhen(item.since, PLATFORM_ZONE)}</span></div></li>)}</ol>}
    <h3>Convites de gestor</h3>
    {canInvite && !inviting ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
      onClick={() => { setOutcome(null); setIssued(null); setInviting(true); }}>Convidar primeiro gestor</button></div> : null}
    {inviting ? <ManagerInviteForm tenantId={tenant.tenantId} close={(result) => {
      setInviting(false);
      if (result) { setIssued(result); setOutcome({ tone: 'success', text: 'Convite emitido. Um convite pendente anterior foi revogado.' }); }
      void detail.refetch();
    }} /> : null}
    {managerInvitations.length === 0 ? <p className="field-help">Nenhum convite de gestor emitido pelo painel.</p>
      : <ol className="management-list" aria-label="Convites de gestor">{managerInvitations.map((item) => <li key={item.invitationId} className="management-row"><div>
        <strong><bdi>{item.displayName}</bdi></strong> <span className="status-pill status-neutral">{invitationLabels[item.status]}</span>
        <span>Emitido em {formatWhen(item.createdAt, PLATFORM_ZONE)} · {item.status === 'ACCEPTED' && item.acceptedAt ? `aceito em ${formatWhen(item.acceptedAt, PLATFORM_ZONE)}`
          : item.status === 'REVOKED' && item.revokedAt ? `revogado em ${formatWhen(item.revokedAt, PLATFORM_ZONE)}` : `vale até ${formatWhen(item.expiresAt, PLATFORM_ZONE)}`}</span></div>
        {manage && item.status === 'PENDING' && revoking !== item.invitationId ? <div className="button-row"><button className="button button-secondary button-compact" type="button"
          onClick={() => { setOutcome(null); setRevoking(item.invitationId); }}>Revogar</button></div> : null}
        {revoking === item.invitationId ? <RevokeInvitationForm tenantId={tenant.tenantId} invitationId={item.invitationId} close={(result) => {
          setRevoking(null); if (result) setOutcome(result); void detail.refetch();
        }} /> : null}
      </li>)}</ol>}
    {!tenant.active ? <p className="field-help">Convites só podem ser emitidos para condomínio ativo.</p>
      : tenant.usage && tenant.usage.activeManagers > 0 ? <p className="field-help">Com gestor vigente, novos vínculos (inclusive outros gestores) seguem pela gestão local.</p> : null}
    {scope.session.capabilities.includes('platform.audit.read') ? <>
      <h3>Eventos recentes da plataforma</h3>
      {recentEvents.length === 0 ? <p className="field-help">Nenhum evento da plataforma para este condomínio.</p>
        : <ol className="management-list audit-list" aria-label="Eventos recentes">{recentEvents.map((event) => <li key={event.eventId} className="management-row"><div>
          <strong>{describe(event.action, event.details)}</strong><span>{formatWhen(event.createdAt, PLATFORM_ZONE)}</span>
          {event.reason ? <span>Motivo: {event.reason}</span> : null}</div></li>)}</ol>}
    </> : null}
  </section>;
}

function ManagerInviteForm({ tenantId, close }: { tenantId: string; close(result: { token: string; expiresAt: string; manager: string } | null): void }) {
  const scope = usePlatform();
  const [name, setName] = useState(''); const [email, setEmail] = useState(''); const [days, setDays] = useState('7'); const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false); const [failure, setFailure] = useState<unknown>(null); const [validation, setValidation] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending) return;
    const displayName = displayText(name, 160); const normalizedReason = displayText(reason, 500);
    const address = email.trim().toLowerCase(); const validDays = /^\d{1,2}$/.test(days.trim()) ? Number(days.trim()) : NaN;
    if (displayName === null) { setValidation('Informe o nome do gestor com até 160 caracteres visíveis.'); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address) || address.length > 320) { setValidation('Informe um e-mail válido para o gestor.'); return; }
    if (!(validDays >= 1 && validDays <= 14)) { setValidation('A validade do convite deve ter entre 1 e 14 dias.'); return; }
    if (normalizedReason === null) { setValidation('Informe o motivo, com até 500 caracteres.'); return; }
    setValidation(''); setPending(true); setFailure(null);
    try {
      const issued = await platform.inviteManager(tenantId, { displayName, email: address, validDays, reason: normalizedReason });
      close({ token: issued.token, expiresAt: issued.expiresAt, manager: displayName });
    } catch (error) { react(error, scope); setFailure(error); } finally { setPending(false); }
  }
  const uncertain = failure !== null && isUncertain(failure);
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Convidar primeiro gestor">
    <Notice>Emitir um convite revoga o convite pendente anterior. O link aparece uma única vez.</Notice>
    <label htmlFor="invite-manager-name">Nome do gestor</label>
    <input id="invite-manager-name" required maxLength={400} value={name} onChange={(event) => setName(event.target.value)} disabled={pending} />
    <label htmlFor="invite-manager-email">E-mail da conta no CondPulse</label>
    <input id="invite-manager-email" type="email" required maxLength={320} value={email} onChange={(event) => setEmail(event.target.value)} disabled={pending} />
    <label htmlFor="invite-manager-days">Validade (dias)</label>
    <input id="invite-manager-days" inputMode="numeric" required value={days} onChange={(event) => setDays(event.target.value)} disabled={pending} />
    <label htmlFor="invite-manager-reason">Motivo (fica na auditoria da plataforma)</label>
    <textarea id="invite-manager-reason" required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {validation ? <Notice tone="error">{validation}</Notice> : null}
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain ? ' Atualize a ficha e confira os convites antes de repetir.' : ''}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={pending}>{pending ? 'Emitindo…' : 'Emitir convite'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button></div>
  </form>;
}

function RevokeInvitationForm({ tenantId, invitationId, close }: { tenantId: string; invitationId: string; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [reason, setReason] = useState(''); const [pending, setPending] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  const normalized = displayText(reason, 500);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || normalized === null) return;
    setPending(true); setFailure(null);
    try {
      const detail = await platform.revokeManagerInvitation(tenantId, invitationId, normalized);
      queries.setQueryData(platformKey(scope.accountId, 'tenant', tenantId), detail);
      close({ tone: 'success', text: 'Convite revogado: o link deixou de valer.' });
    } catch (error) { react(error, scope); setFailure(error); } finally { setPending(false); }
  }
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label="Revogar convite de gestor">
    <label htmlFor={`revoke-invitation-${invitationId}`}>Motivo da revogação</label>
    <textarea id={`revoke-invitation-${invitationId}`} required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {failure ? <Notice tone={isUncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    <div className="button-row"><button className="button button-danger" type="submit" disabled={pending || normalized === null}>{pending ? 'Revogando…' : 'Revogar convite'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button></div>
  </form>;
}
