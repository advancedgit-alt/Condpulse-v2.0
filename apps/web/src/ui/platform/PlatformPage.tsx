import { useCallback, useMemo, useState, type FormEvent } from 'react';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { platform, type PlatformTenant, type PlatformTenantChange, type TenantCreated } from '../../api/platform';
import { collapseText, displayText } from '../../api/text';
import { useAuth } from '../Auth';
import { MfaPanel, mfaStatusKey } from '../Mfa';
import { Empty, ErrorState, Icon, Loading, Notice, SectionTabs, errorMessage, type IconName } from '../primitives';
import { formatWhen } from '../dateTime';
import '../management/Management.css';
import { OutboxSection } from './Outbox';
import { TechnicalGrantSection } from './TechnicalGrants';
import { ModuleSection } from './Modules';
import { BillingSection, billingActionLabel, billingAuditDetails } from './Billing';
import { PLATFORM_ZONE, PlatformContext, isUncertain, platformKey, react, short, tenantListKey, useAccessGuard, usePlatform, type Outcome } from './shared';
import { InvitationLinkNotice, TenantCreateForm, TenantDetailView, creationOutcome } from './TenantViews';
import { billingText, originText, usageText } from './tenants';
import { ENTRY_PATH, centralPath, centralTenantPath } from '../shell/paths';

export type { Outcome } from './shared';
const sections = [{ id: 'condominios', label: 'Condomínios', icon: 'building' }, { id: 'modulos', label: 'Módulos por condomínio', icon: 'grid' },
  { id: 'financeiro', label: 'Financeiro', icon: 'card' },
  { id: 'concessoes', label: 'Concessões técnicas', icon: 'key' }, { id: 'operacao', label: 'Operação', icon: 'list' },
  { id: 'auditoria', label: 'Auditoria', icon: 'clock' }] as const satisfies readonly { id: string; label: string; icon: IconName }[];
/**
 * Seções da central ainda sem backend (docs/ESTRUTURA.md §4): aparecem desabilitadas, sem rota, até a API existir.
 * Nenhuma delas mostra ou recebe segredo nesta versão.
 */
export const upcomingCentralSections = [{ id: 'contas', label: 'Contas e acessos', icon: 'users' }, { id: 'chaves', label: 'Chaves e integrações', icon: 'shield' },
  { id: 'facial', label: 'Acesso facial', icon: 'door' }] as const satisfies readonly { id: string; label: string; icon: IconName }[];
type Section = typeof sections[number]['id'];
/** Seções liberadas pelas capacidades da sessão; a navegação lateral e as abas usam a mesma regra (o servidor confere de novo). */
export function visiblePlatformSections(capabilities: readonly string[]) {
  return sections.filter((item) => (item.id !== 'financeiro' || capabilities.includes('billing.read'))
    && (item.id !== 'modulos' || capabilities.includes('tenants.read'))
    && (item.id !== 'operacao' || capabilities.includes('outbox.operate'))
    && (item.id !== 'concessoes' || capabilities.includes('technical_grants.manage')));
}
/** Seção efetivamente exibida: uma seção não liberada cai em Condomínios. */
export function currentPlatformSection(section: string, capabilities: readonly string[]): Section {
  return visiblePlatformSections(capabilities).find((item) => item.id === section)?.id ?? 'condominios';
}
/** Navegação lateral da central: só observa a sessão que o painel já consultou, sem requisição própria. */
export function usePlatformNavigation(accountId: string | undefined, section: string) {
  const session = useQuery({ queryKey: platformKey(accountId ?? '', 'session'), queryFn: ({ signal }) => platform.session(signal),
    enabled: false });
  const capabilities = session.data && !session.data.mfaRequired ? session.data.capabilities : [];
  return { items: visiblePlatformSections(capabilities), current: currentPlatformSection(section, capabilities) };
}
/** Mensagem fiel ao que o servidor fez: repetição natural (applied=false) não aparece como sucesso desta ação. */
export function tenantChangeOutcome(change: PlatformTenantChange, before: Pick<PlatformTenant, 'name' | 'active'>): Outcome {
  if (!change.applied) return { tone: 'info', text: 'O condomínio já estava neste estado. Nada foi alterado e o seu motivo não foi registrado.' };
  const parts: string[] = [];
  if (change.tenant.name !== before.name) parts.push(`Nome atualizado para "${change.tenant.name}".`);
  if (change.tenant.active !== before.active) parts.push(change.tenant.active
    ? 'Condomínio reativado: moradores e gestão local voltam a acessar.'
    : 'Condomínio suspenso: moradores e gestão local perdem o acesso na próxima requisição. Nenhum dado foi apagado.');
  return { tone: 'success', text: parts.length ? parts.join(' ') : 'Alteração registrada.' };
}

/** Central administradora: papel de plataforma vigente e segundo fator; o servidor confere de novo em cada ação. */
export function PlatformPage() {
  const { identity } = useAuth();
  const { section = 'condominios', tenantId } = useParams();
  if (!identity) return null;
  const current = sections.some((item) => item.id === section) ? section as Section : 'condominios';
  return <PlatformGate key={identity.accountId} accountId={identity.accountId} section={current} tenantId={tenantId ?? null} />;
}

function PlatformGate({ accountId, section, tenantId }: { accountId: string; section: Section; tenantId: string | null }) {
  const queries = useQueryClient();
  const session = useQuery({ queryKey: platformKey(accountId, 'session'), queryFn: ({ signal }) => platform.session(signal),
    refetchOnWindowFocus: false, retry: false });
  const [demanded, setDemanded] = useState(false);
  const [denied, setDenied] = useState<unknown>(null);
  const requireMfa = useCallback(() => setDemanded(true), []);
  const deny = useCallback((error: unknown) => {
    setDenied(error);
    void queries.cancelQueries({ queryKey: platformKey(accountId) }); queries.removeQueries({ queryKey: platformKey(accountId) });
  }, [queries, accountId]);
  const value = useMemo(() => session.data ? { accountId, session: session.data, requireMfa, denied: deny } : null,
    [accountId, session.data, requireMfa, deny]);
  if (denied) return <div className="panel"><Notice tone="error">A administração da plataforma não está mais disponível para esta conta. Os dados exibidos foram removidos.</Notice>
    <Link className="button button-secondary" to={ENTRY_PATH}>Voltar para meus acessos</Link></div>;
  if (session.isPending) return <div className="panel"><Loading label="Conferindo o acesso à plataforma…" /></div>;
  if (session.isError && !session.data) {
    const code = session.error instanceof ApiError ? session.error.code : '';
    if (code === 'PLATFORM_ACCESS_DENIED') return <div className="panel"><Empty icon="shield" title="Área restrita">Esta área é exclusiva da administração da plataforma. O acesso é concedido por procedimento operacional, não pelo condomínio.</Empty></div>;
    if (code === 'API_NOT_AVAILABLE') return <div className="panel"><Empty title="Painel indisponível">O painel da plataforma não está ativo neste ambiente.</Empty></div>;
    return <ErrorState error={session.error} retry={() => void session.refetch()} />;
  }
  if (!value) return null;
  if (value.session.mfaRequired || demanded) {
    return <MfaPanel accountId={accountId} onVerified={() => {
      setDemanded(false);
      void queries.invalidateQueries({ queryKey: mfaStatusKey(accountId) });
      void queries.invalidateQueries({ queryKey: platformKey(accountId) });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }} />;
  }
  return <PlatformContext.Provider value={value}><PlatformConsole section={section} tenantId={tenantId} /></PlatformContext.Provider>;
}

function PlatformConsole({ section, tenantId }: { section: Section; tenantId: string | null }) {
  const scope = usePlatform();
  const visible = visiblePlatformSections(scope.session.capabilities);
  const current = currentPlatformSection(section, scope.session.capabilities);
  return <>
    <div className="residence-heading"><div><span className="eyebrow">ADMINISTRAÇÃO DA PLATAFORMA</span><h1>Central administradora</h1>
      <p>Cadastro, uso, estado e assinatura dos condomínios. Sem acesso ao conteúdo dos moradores; toda alteração exige motivo e fica na auditoria da plataforma.</p></div>
      <div className="context-badge"><Icon name="shield" /><div><strong>Segundo fator confirmado</strong><span>Sessão de plataforma</span></div></div></div>
    <SectionTabs label="Seções da central" current={current} items={visible.map((item) => ({ id: item.id, label: item.label, to: centralPath(item.id) }))} />
    {current === 'financeiro' ? <BillingSection /> : current === 'auditoria' ? <AuditSection /> : current === 'operacao' ? <OutboxSection />
      : current === 'concessoes' ? <TechnicalGrantSection /> : current === 'modulos' ? <ModuleSection />
      : tenantId ? <TenantDetailView key={tenantId} tenantId={tenantId} describe={describeEvent}
        editor={(tenant, close) => <TenantEditor tenant={tenant} close={close} />} /> : <TenantSection />}
  </>;
}

function TenantSection() {
  const scope = usePlatform();
  const [draft, setDraft] = useState('');
  const [search, setSearch] = useState<string | null>(null);
  const [searchInvalid, setSearchInvalid] = useState(false);
  const tenants = useQuery({ queryKey: tenantListKey(scope.accountId, search), queryFn: ({ signal }) => platform.tenants(search, signal) });
  const [editing, setEditing] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<{ result: TenantCreated; manager: string } | null>(null);
  if (useAccessGuard(tenants.error)) return null;
  const manage = scope.session.capabilities.includes('tenants.manage');
  function find(event: FormEvent) {
    event.preventDefault();
    if (!collapseText(draft)) { setSearch(null); setSearchInvalid(false); return; }
    const normalized = displayText(draft, 160);
    setSearchInvalid(normalized === null);
    if (normalized !== null) { setSearch(normalized); setEditing(null); }
  }
  return <section className="management-section" aria-labelledby="tenants-title">
    <div className="section-heading"><div><span className="section-index">01 / CONDOMÍNIOS</span><h2 id="tenants-title">Condomínios da plataforma</h2></div>
      <div className="button-row">{manage && !creating ? <button className="button button-compact" type="button"
        onClick={() => { setOutcome(null); setCreated(null); setCreating(true); }}>Novo condomínio</button> : null}
        <button className="button button-secondary button-compact" type="button" disabled={tenants.isFetching} onClick={() => void tenants.refetch()}>Atualizar</button></div></div>
    <p className="field-help">Uso agregado (unidades, vínculos e gestores), sem conteúdo dos moradores. Abra a ficha para ver gestores, convites e eventos.</p>
    {creating ? <TenantCreateForm onCancel={() => setCreating(false)} onCreated={(result, manager) => {
      setCreating(false); setCreated({ result, manager }); setOutcome(creationOutcome(result));
    }} /> : null}
    {created?.result.applied ? <InvitationLinkNotice token={created.result.invitationToken} expiresAt={created.result.invitationExpiresAt}
      manager={created.manager} /> : null}
    <form className="reservation-filter tenant-search" role="search" aria-label="Buscar condomínio" onSubmit={find}>
      <label htmlFor="tenant-search">Buscar por trecho do nome ou id</label>
      <input id="tenant-search" type="search" value={draft} onChange={(event) => { setDraft(event.target.value); setSearchInvalid(false); }} />
      <button className="button button-secondary button-compact" type="submit">Buscar</button>
      {search ? <button className="button button-secondary button-compact" type="button" onClick={() => { setDraft(''); setSearch(null); setSearchInvalid(false); }}>Limpar busca</button> : null}
      {search ? <span>Resultado para “<bdi>{search}</bdi>” (acentos contam; maiúsculas não).</span> : null}
    </form>
    {searchInvalid ? <Notice tone="error">Use até 160 caracteres visíveis na busca.</Notice> : null}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {tenants.isPending ? <div className="panel"><Loading label="Consultando condomínios…" /></div>
      : tenants.isError && !tenants.data ? <ErrorState error={tenants.error} retry={() => void tenants.refetch()} />
        : !tenants.data || tenants.data.tenants.length === 0 ? <div className="panel"><Empty icon="building" title="Nenhum condomínio">{search
          ? 'Nenhum condomínio corresponde à busca.' : 'Os condomínios cadastrados pelo onboarding aparecerão aqui.'}</Empty></div>
          : <ol className="management-list" aria-label="Condomínios">{tenants.data.tenants.map((tenant) => <li key={tenant.tenantId} className="management-row">
            <div><strong><bdi>{tenant.name}</bdi></strong> <span className={`status-pill ${tenant.active ? 'status-ready' : 'status-neutral'}`}>{tenant.active ? 'Ativo' : 'Suspenso'}</span>
              <span>Fuso {tenant.timeZone} · versão {tenant.version} · atualizado em {formatWhen(tenant.updatedAt, PLATFORM_ZONE)} · {originText(tenant.origin)}</span>
              <span>{usageText(tenant, PLATFORM_ZONE)}</span>
              {billingText(tenant) ? <span>{billingText(tenant)}</span> : null}
              <span>{tenant.onboarding ? `Onboarding em ${formatWhen(tenant.onboarding.completedAt, PLATFORM_ZONE)}: ${count(tenant.onboarding.units, 'unidade', 'unidades')}, ${count(tenant.onboarding.memberships, 'vínculo', 'vínculos')}, ${count(tenant.onboarding.amenities, 'área', 'áreas')}`
                : 'Sem recibo de onboarding registrado.'}</span></div>
            <div className="button-row"><Link className="button button-secondary button-compact" to={centralTenantPath(tenant.tenantId)}
              aria-label={`Abrir ficha de ${tenant.name}`}>Abrir ficha</Link>
              {manage && editing !== tenant.tenantId ? <button className="button button-secondary button-compact" type="button"
                onClick={() => { setOutcome(null); setEditing(tenant.tenantId); }}>Alterar nome ou estado</button> : null}</div>
            {manage && editing === tenant.tenantId ? <TenantEditor tenant={tenant} close={(result) => { setEditing(null); if (result) setOutcome(result); }} /> : null}
          </li>)}</ol>}
    {tenants.data?.truncated ? <Notice tone="warning">A lista mostra os primeiros 200 condomínios em ordem alfabética. Use a busca por nome ou id para encontrar os demais.</Notice> : null}
  </section>;
}
function count(value: number | null, singular: string, plural: string): string {
  return value === null ? `${plural}: não informado` : `${value} ${value === 1 ? singular : plural}`;
}

function TenantEditor({ tenant, close }: { tenant: PlatformTenant; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  // Versão e valores lidos ao abrir: nova consulta da lista não troca o alvo da alteração em andamento.
  const [read, setRead] = useState({ version: tenant.version, name: tenant.name, active: tenant.active });
  const [name, setName] = useState(tenant.name);
  const [active, setActive] = useState(tenant.active);
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const stale = tenant.version !== read.version;
  // Nome intocado vai como null: o servidor mantém o atual, sem renomear por normalização.
  const untouched = name === read.name;
  const normalizedName = untouched ? read.name : displayText(name, 160);
  const normalizedReason = displayText(reason, 500);
  const valid = normalizedName !== null && normalizedReason !== null;
  function reload() {
    setRead({ version: tenant.version, name: tenant.name, active: tenant.active });
    setName(tenant.name); setActive(tenant.active); setFailure(null);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || pending || stale) return;
    setPending(true); setFailure(null);
    try {
      const change = await platform.updateTenant(tenant.tenantId,
        { name: untouched ? null : normalizedName, active, expectedVersion: read.version, reason: normalizedReason! });
      await queries.invalidateQueries({ queryKey: platformKey(scope.accountId) });
      close(tenantChangeOutcome(change, read));
    } catch (error) {
      react(error, scope);
      setFailure(error);
      // Conflito ou resultado incerto: a lista atualizada mostra a versão atual e o aviso de recarga.
      void queries.invalidateQueries({ queryKey: platformKey(scope.accountId, 'tenants') });
    } finally { setPending(false); }
  }
  const uncertain = failure !== null && isUncertain(failure);
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`Alterar ${tenant.name}`}>
    {stale ? <Notice tone="warning">Este condomínio foi alterado depois que você abriu o formulário (versão {read.version} → {tenant.version}).
      <button className="button button-secondary button-compact" type="button" onClick={reload}>Recarregar os dados atuais</button></Notice> : null}
    <label htmlFor={`tenant-name-${tenant.tenantId}`}>Nome do condomínio</label>
    <input id={`tenant-name-${tenant.tenantId}`} required value={name} onChange={(event) => setName(event.target.value)} disabled={pending}
      aria-describedby={`tenant-name-help-${tenant.tenantId}`} aria-invalid={normalizedName === null} />
    <span className="field-help" id={`tenant-name-help-${tenant.tenantId}`}>{normalizedName === null
      ? 'Informe um nome com até 160 caracteres, sem caracteres invisíveis.' : 'Até 160 caracteres; espaços repetidos viram um só.'}</span>
    <fieldset className="weekday-fieldset"><legend>Estado</legend>
      <label className="weekday-option"><input type="radio" name={`tenant-state-${tenant.tenantId}`} checked={active} onChange={() => setActive(true)} disabled={pending} />Ativo</label>
      <label className="weekday-option"><input type="radio" name={`tenant-state-${tenant.tenantId}`} checked={!active} onChange={() => setActive(false)} disabled={pending} />Suspenso</label>
    </fieldset>
    {!active && read.active ? <Notice tone="warning">Suspender tira o condomínio do acesso de moradores e da gestão local na próxima requisição. Dados, reservas e sessões não são apagados.</Notice> : null}
    <label htmlFor={`tenant-reason-${tenant.tenantId}`}>Motivo (fica na auditoria da plataforma)</label>
    <textarea id={`tenant-reason-${tenant.tenantId}`} maxLength={500} required rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain ? ' Atualize a lista e confira o estado antes de repetir.' : ''}</Notice> : null}
    <div className="button-row">
      <button className={`button ${!active && read.active ? 'button-danger' : ''}`} type="submit" disabled={!valid || pending || stale}>{pending ? 'Salvando…' : 'Salvar alteração'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

const actionLabels: Record<string, string> = {
  TENANT_RENAMED: 'Condomínio renomeado', TENANT_SUSPENDED: 'Condomínio suspenso', TENANT_REACTIVATED: 'Condomínio reativado',
  TENANT_CREATED: 'Condomínio criado no painel', TENANT_MANAGER_INVITED: 'Convite de gestor emitido',
  TENANT_MANAGER_INVITATION_REVOKED: 'Convite de gestor revogado', OUTBOX_TASK_REPLAYED: 'Tarefa devolvida para a fila',
};
/** Rótulo e detalhes do evento da plataforma, usados na trilha e na ficha do condomínio. */
function describeEvent(action: string, details: Record<string, string>): string {
  const label = actionLabels[action] ?? billingActionLabel(action) ?? action;
  if (action === 'TENANT_CREATED' && details.units) return `${label}: ${details.units} unidade(s), ${details.amenities === '1' ? 'com' : 'sem'} área inicial, fuso ${details.timeZone ?? '—'}`;
  if (action === 'TENANT_MANAGER_INVITED' && details.displayName) return `${label} para ${details.displayName} (${details.validDays ?? '?'} dia(s))`;
  if (action === 'TENANT_MANAGER_INVITATION_REVOKED' && details.replaced === 'true') return `${label} (substituído por novo convite)`;
  return label;
}

function AuditSection() {
  const scope = usePlatform();
  const pages = useInfiniteQuery({
    queryKey: platformKey(scope.accountId, 'audit'),
    queryFn: ({ pageParam, signal }) => platform.audit(pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
  // Nomes atuais pela mesma lista da seção de condomínios (o cache não sobrevive à troca de aba).
  const catalog = useQuery({ queryKey: tenantListKey(scope.accountId, null), queryFn: ({ signal }) => platform.tenants(null, signal),
    enabled: scope.session.capabilities.includes('tenants.read') });
  const accessDenied = useAccessGuard(pages.error ?? catalog.error);
  if (accessDenied) return null;
  const known = catalog.data?.tenants ?? [];
  const tenantName = (id: string | null) => (id ? known.find((item) => item.tenantId === id)?.name ?? `condomínio ${short(id)}` : 'plataforma');
  const events = pages.data?.pages.flatMap((page) => page.events) ?? [];
  return <section className="management-section" aria-labelledby="platform-audit-title">
    <div className="section-heading"><div><span className="section-index">03 / AUDITORIA</span><h2 id="platform-audit-title">Trilha da plataforma</h2></div>
      <button className="button button-secondary button-compact" type="button" disabled={pages.isFetching} onClick={() => void pages.refetch()}>Atualizar</button></div>
    <p className="field-help">Registros somente leitura, com autor e motivo. Horários em {PLATFORM_ZONE}.</p>
    {pages.isPending ? <div className="panel"><Loading label="Consultando auditoria…" /></div>
      : pages.isError && events.length === 0 ? <ErrorState error={pages.error} retry={() => void pages.refetch()} />
        : events.length === 0 ? <div className="panel"><Empty icon="clock" title="Sem registros">As alterações feitas pela plataforma aparecerão aqui.</Empty></div>
          : <ol className="management-list audit-list" aria-label="Eventos da plataforma">{events.map((event) => <li key={event.eventId} className="management-row">
            <div><strong>{describeEvent(event.action, event.details)}</strong>
              <span>{formatWhen(event.createdAt, PLATFORM_ZONE)} · <bdi>{tenantName(event.tenantId)}</bdi> · ator {short(event.actorId)}</span>
              {event.action === 'TENANT_RENAMED' && event.details.previousName && event.details.name ? <span>De “<bdi>{event.details.previousName}</bdi>” para “<bdi>{event.details.name}</bdi>”</span> : null}
              {event.action.startsWith('BILLING_') ? billingAuditDetails(event.details) : null}
              {event.action === 'OUTBOX_TASK_REPLAYED' && event.details.eventType ? <span>{event.details.eventType} · estava {event.details.previousState === 'FAILED' ? 'com falha' : 'ignorada'}</span> : null}
              {event.reason ? <span>Motivo: {event.reason}</span> : null}</div>
          </li>)}</ol>}
    {pages.isError && events.length > 0 ? <ErrorState error={pages.error} retry={() => void pages.fetchNextPage()} /> : null}
    {pages.hasNextPage ? <button className="button button-secondary" type="button" disabled={pages.isFetchingNextPage} onClick={() => void pages.fetchNextPage()}>
      {pages.isFetchingNextPage ? 'Carregando…' : 'Carregar mais'}</button> : null}
  </section>;
}

