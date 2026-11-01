import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ApiError, newOperationKey } from '../../api/client';
import { partners, type PartnerMember } from '../../api/partners';
import { platform, type DemoGeneration, type DemoProvisioned } from '../../api/platform';
import { displayText } from '../../api/text';
import { partnerRoles, type PartnerRole } from '../../api/types';
import { formatWhen } from '../dateTime';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { centralTenantPath } from '../shell/paths';
import { partnerRoleLabels } from '../shell/paths';
import { PLATFORM_ZONE, isUncertain, platformKey, react, short, useAccessGuard, usePlatform, type Outcome } from './shared';

export const demoKey = (accountId: string) => platformKey(accountId, 'demo');

const countLabels: Record<string, string> = {
  blocks: 'torres', units: 'unidades', amenities: 'áreas comuns com regras de reserva', memberships: 'pessoas sintéticas no condomínio',
  announcements: 'avisos', parcels: 'encomendas', visits: 'visitas', occurrences: 'ocorrências', requests: 'pedidos',
  partnerMembers: 'pessoas sintéticas no parceiro', schedules: 'escalas', sampleSecurityEvents: 'eventos de exemplo no Log de Segurança',
};
/** Contagens legíveis, na ordem acima; chaves desconhecidas ficam de fora (o servidor pode acrescentar). */
export function demoCountsText(counts: Record<string, number>): string {
  return Object.keys(countLabels).filter((key) => typeof counts[key] === 'number').map((key) => `${counts[key]} ${countLabels[key]}`).join(' · ');
}

/** Mensagem fiel ao que o servidor fez (repetição da mesma operação não aparece como criação nova). */
export function demoOutcome(result: DemoProvisioned): Outcome {
  if (!result.applied) return { tone: 'info', text: `Esta operação já tinha sido feita: o demo atual é a geração ${result.generation.generation}. Nada novo foi criado.` };
  return { tone: 'success', text: result.retiredTenantId
    ? `Demo resetado: geração ${result.generation.generation} criada. A geração anterior ficou suspensa (nada foi apagado) e as pessoas reais vinculadas a ela precisam ser vinculadas de novo.`
    : `Condomínio demo criado (geração ${result.generation.generation}). Nenhuma conta real recebeu papel: use os passos abaixo para vincular quem vai testar.` };
}

/** Texto do erro próprio do demo; os demais seguem a mensagem padrão. */
export function demoErrorText(error: unknown): string {
  if (error instanceof ApiError && error.code === 'DEMO_CHANGED') return 'O condomínio demo mudou desde que a tela foi aberta. Atualize e confira antes de repetir.';
  if (error instanceof ApiError && error.code === 'DEMO_RESET_REAL_TENANT') return 'Somente o condomínio demo pode ser resetado. Condomínios reais nunca são resetados.';
  return errorMessage(error);
}

/** Central: condomínio demo isolado, com dados sintéticos (CPX-046). Criar e resetar exigem confirmação e motivo. */
export function DemoSection() {
  const scope = usePlatform();
  const state = useQuery({ queryKey: demoKey(scope.accountId), queryFn: ({ signal }) => platform.demo(signal) });
  const [acting, setActing] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  if (useAccessGuard(state.error)) return null;
  const current = state.data?.current ?? null;
  return <section className="management-section" aria-labelledby="demo-title">
    <div className="section-heading"><div><span className="section-index">CONDOMÍNIO DEMO</span><h2 id="demo-title">Condomínio demo</h2></div>
      <div className="button-row">{state.data && !acting ? <button className={`button button-compact${current ? ' button-danger' : ''}`} type="button"
        onClick={() => { setOutcome(null); setActing(true); }}>{current ? 'Resetar condomínio demo' : 'Criar condomínio demo'}</button> : null}
        <button className="button button-secondary button-compact" type="button" disabled={state.isFetching} onClick={() => void state.refetch()}>Atualizar</button></div></div>
    <p className="field-help">Condomínio de demonstração isolado, com dados 100% fictícios (nomes com “fictício”), marcado como <strong>DEMO</strong> em todas as
      telas. Nunca é misturado com condomínios reais. Resetar não apaga nada: a geração atual fica suspensa e uma nova é criada.</p>
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {acting && state.data ? <ProvisionForm current={current} close={(result) => { setActing(false); if (result) setOutcome(result); }} /> : null}
    {state.isPending ? <div className="panel"><Loading label="Consultando o condomínio demo…" /></div>
      : state.isError && !state.data ? <ErrorState error={state.error} retry={() => void state.refetch()} />
        : !current ? <div className="panel"><Empty icon="building" title="Nenhum condomínio demo ativo">Crie o demo para começar os testes com dados fictícios.</Empty></div>
          : <CurrentDemo generation={current} />}
    {state.data && state.data.retired.length > 0 ? <details className="panel"><summary>Gerações anteriores ({state.data.retired.length})</summary>
      <ol className="management-list" aria-label="Gerações anteriores do demo">{state.data.retired.map((item) => <li key={item.generationId} className="management-row">
        <div><strong><bdi>{item.tenantName}</bdi></strong> <span className="status-pill status-neutral">Suspensa</span>
          <span>Criada em {formatWhen(item.createdAt, PLATFORM_ZONE)}{item.retiredAt ? ` · aposentada em ${formatWhen(item.retiredAt, PLATFORM_ZONE)}` : ''}</span></div>
      </li>)}</ol></details> : null}
  </section>;
}

function CurrentDemo({ generation }: { generation: DemoGeneration }) {
  const scope = usePlatform();
  const canLinkPartner = scope.session.capabilities.includes('partners.manage');
  return <>
    <div className="panel management-row">
      <div><strong><bdi>{generation.tenantName}</bdi></strong> <span className="status-pill demo-pill">DEMO</span> <span className="status-pill status-ready">Ativo</span>
        <span>Geração {generation.generation} · criada em {formatWhen(generation.createdAt, PLATFORM_ZONE)} · condomínio {short(generation.tenantId)}</span>
        <span>Parceiro de segurança: <bdi>{generation.partnerName}</bdi> · contrato vigente · parceiro {short(generation.partnerId)}</span>
        <span>{demoCountsText(generation.counts)}</span></div>
      <div className="button-row"><Link className="button button-secondary button-compact" to={centralTenantPath(generation.tenantId)}
        aria-label={`Abrir ficha de ${generation.tenantName}`}>Abrir ficha</Link></div>
    </div>
    <div className="panel stack-form">
      <h3>Vincular quem vai testar</h3>
      <p className="field-help">Contas reais não recebem papel no demo automaticamente. Cada vínculo é uma ação separada e fica na auditoria:</p>
      <ol className="field-help">
        <li><strong>Gestão:</strong> abra a ficha do demo e emita o convite de gestor para o e-mail da pessoa; ela aceita com a própria conta.</li>
        <li><strong>Morador, portaria e zeladoria:</strong> com a gestão aceita, convide em Gestão → Pessoas (convite por unidade ou de equipe).</li>
        <li><strong>Parceiro de segurança:</strong> conceda o papel abaixo (conta existente, pelo identificador). Operador e supervisor ainda precisam de
          escala, criada pelo gestor do parceiro no painel do parceiro.</li>
      </ol>
      {canLinkPartner ? <PartnerLinkForm partnerId={generation.partnerId} partnerName={generation.partnerName} />
        : <Notice tone="info">A hierarquia de parceiro está desligada neste ambiente (CP_PARTNERS_ENABLED): o vínculo ao parceiro demo fica indisponível.</Notice>}
    </div>
  </>;
}

function ProvisionForm({ current, close }: { current: DemoGeneration | null; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  const [key, setKey] = useState(newOperationKey);
  const [reason, setReason] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const uncertain = failure !== null && isUncertain(failure);
  const locked = pending || uncertain;
  const normalizedReason = displayText(reason, 500);
  const valid = normalizedReason !== null && normalizedReason.length >= 3 && confirmation === 'DEMO';
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || pending) return;
    setPending(true); setFailure(null);
    try {
      const result = await platform.provisionDemo({ expectedTenantId: current?.tenantId ?? null, reason: normalizedReason!, confirmation: 'DEMO' }, key);
      await queries.invalidateQueries({ queryKey: platformKey(scope.accountId) });
      await queries.invalidateQueries({ queryKey: ['identity'] });
      close(demoOutcome(result));
    } catch (error) {
      react(error, scope);
      setFailure(error);
      if (error instanceof ApiError && error.code === 'DEMO_CHANGED') void queries.invalidateQueries({ queryKey: demoKey(scope.accountId) });
    } finally { setPending(false); }
  }
  function discard() { setFailure(null); setKey(newOperationKey()); close(null); }
  return <form className="panel management-form stack-form" onSubmit={(event) => void submit(event)} aria-label={current ? 'Resetar condomínio demo' : 'Criar condomínio demo'}>
    <h3>{current ? 'Resetar condomínio demo' : 'Criar condomínio demo'}</h3>
    {current ? <Notice tone="warning">A geração atual (<bdi>{current.tenantName}</bdi>) fica suspensa: contrato do parceiro encerrado, parceiro suspenso e pessoas
      reais vinculadas perdem o acesso a ela. Nada é apagado. Uma geração nova é criada com dados fictícios e precisa de novos vínculos.</Notice>
      : <Notice tone="info">Cria um condomínio marcado como DEMO com torres, unidades, áreas, avisos, encomendas, visitas, ocorrências, pedidos e um
        parceiro de segurança fictício com contrato, escala e eventos de exemplo. Nenhuma conta real recebe papel.</Notice>}
    <label htmlFor="demo-reason">Motivo (fica na auditoria da plataforma)</label>
    <textarea id="demo-reason" required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={locked} />
    <label htmlFor="demo-confirmation">Para confirmar, digite DEMO</label>
    <input id="demo-confirmation" required autoComplete="off" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} disabled={locked}
      aria-invalid={confirmation !== '' && confirmation !== 'DEMO'} />
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{demoErrorText(failure)}{uncertain
      ? ' A operação pode ter sido concluída. Reenvie sem alterar (a mesma operação não cria outra geração) ou descarte e atualize a tela.' : ''}</Notice> : null}
    <div className="button-row">
      <button className={`button${current ? ' button-danger' : ''}`} type="submit" disabled={!valid || pending}>{pending ? 'Processando…'
        : uncertain ? 'Reenviar a mesma operação' : current ? 'Resetar demo' : 'Criar demo'}</button>
      <button className="button button-secondary" type="button" onClick={discard} disabled={pending}>Voltar</button>
    </div>
  </form>;
}

function PartnerLinkForm({ partnerId, partnerName }: { partnerId: string; partnerName: string }) {
  const scope = usePlatform();
  const [accountId, setAccountId] = useState(scope.accountId);
  const [role, setRole] = useState<PartnerRole>('PARTNER_ADMIN');
  const [displayName, setDisplayName] = useState('');
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [linked, setLinked] = useState<PartnerMember | null>(null);
  const account = accountId.trim().toLowerCase();
  const validAccount = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(account);
  const name = displayText(displayName, 160);
  const motive = displayText(reason, 500);
  const valid = validAccount && name !== null && motive !== null && motive.length >= 3;
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || pending) return;
    setPending(true); setFailure(null); setLinked(null);
    try {
      setLinked(await partners.platformAddMember(partnerId, { accountId: account, role, displayName: name!, reason: motive! }));
    } catch (error) { react(error, scope); setFailure(error); } finally { setPending(false); }
  }
  return <form className="stack-form" onSubmit={(event) => void submit(event)} aria-label="Vincular conta ao parceiro demo">
    <h4>Papel no parceiro <bdi>{partnerName}</bdi></h4>
    <label htmlFor="demo-partner-account">Identificador da conta (a sua já vem preenchida)</label>
    <input id="demo-partner-account" required value={accountId} onChange={(event) => setAccountId(event.target.value)} disabled={pending}
      aria-invalid={!validAccount} />
    <label htmlFor="demo-partner-role">Papel</label>
    <select id="demo-partner-role" value={role} onChange={(event) => setRole(event.target.value as PartnerRole)} disabled={pending}>
      {partnerRoles.map((item) => <option key={item} value={item}>{partnerRoleLabels[item]}</option>)}</select>
    <label htmlFor="demo-partner-name">Nome de exibição</label>
    <input id="demo-partner-name" required maxLength={160} value={displayName} onChange={(event) => setDisplayName(event.target.value)} disabled={pending} />
    <label htmlFor="demo-partner-reason">Motivo (fica na auditoria da plataforma)</label>
    <textarea id="demo-partner-reason" required maxLength={500} rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {linked ? <Notice tone="success">Papel {partnerRoleLabels[linked.role]} concedido a <bdi>{linked.displayName}</bdi>. A pessoa vê o painel em “Meus acessos”
      depois de entrar de novo (operador e supervisor só com escala vigente).</Notice> : null}
    {failure ? <Notice tone={isUncertain(failure) ? 'warning' : 'error'}>{errorMessage(failure)}</Notice> : null}
    <div className="button-row"><button className="button" type="submit" disabled={!valid || pending}>{pending ? 'Concedendo…' : 'Conceder papel'}</button></div>
  </form>;
}
