import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { ApiError, isUUID } from '../../api/client';
import { platform } from '../../api/platform';
import { tenantModuleLabels, tenantModules, type TenantModuleChange, type TenantModuleState } from '../../api/tenantModules';
import { displayText } from '../../api/text';
import { Empty, ErrorState, Loading, Notice, errorMessage } from '../primitives';
import { formatWhen } from '../dateTime';
import { queryFailure } from '../queryFailure';
import { PLATFORM_ZONE, isUncertain, platformKey, react, short, tenantListKey, useAccessGuard, usePlatform, type Outcome } from './shared';

/**
 * Módulos por condomínio (V045): a central libera ou suspende cada módulo dentro da flag global. Suspender vale na
 * próxima requisição (403 MODULE_DISABLED nas rotas do módulo); nada é apagado. Gravação pela versão lida, com motivo.
 */
export const modulesKey = (accountId: string, tenantId: string) => platformKey(accountId, 'modules', tenantId);

/** Mensagem fiel ao que o servidor fez: repetição (applied=false) não aparece como sucesso desta ação. */
export function moduleOutcome(change: TenantModuleChange): Outcome {
  const label = tenantModuleLabels[change.module.module];
  if (!change.applied) return { tone: 'info', text: `${label} já estava neste estado. Nada foi alterado e o seu motivo não foi registrado.` };
  if (!change.module.tenantEnabled) return { tone: 'success', text: `${label} suspenso: as telas e rotas do módulo deixam de funcionar no condomínio na próxima requisição. Nenhum dado foi apagado.` };
  return { tone: 'success', text: change.module.globallyEnabled ? `${label} liberado para o condomínio.`
    : `${label} liberado para o condomínio, mas continua desligado no servidor (flag global).` };
}

export function moduleStatus(item: TenantModuleState): { text: string; tone: 'status-ready' | 'status-neutral' | 'status-pending' } {
  if (item.enabled) return { text: 'Ativo', tone: 'status-ready' };
  if (!item.tenantEnabled) return { text: 'Suspenso pela central', tone: 'status-pending' };
  return { text: 'Desligado no servidor', tone: 'status-neutral' };
}

export function ModuleSection() {
  const scope = usePlatform();
  const [params, setParams] = useSearchParams();
  const tenants = useQuery({ queryKey: tenantListKey(scope.accountId, null), queryFn: ({ signal }) => platform.tenants(null, signal) });
  const chosen = params.get('condominio') ?? '';
  const tenantId = isUUID(chosen) ? chosen.toLowerCase() : '';
  const detail = useQuery({ queryKey: platformKey(scope.accountId, 'tenant', tenantId), queryFn: ({ signal }) => platform.tenant(tenantId, signal),
    enabled: tenantId !== '' });
  const failed = queryFailure([tenants]);
  const detailFailed = tenantId ? queryFailure([detail]) : null;
  if (useAccessGuard(failed ?? detailFailed)) return null;
  const tenant = detail.data?.tenant;
  const listed = tenants.data?.tenants ?? [];
  const options = tenant && !listed.some((item) => item.tenantId === tenant.tenantId) ? [tenant, ...listed] : listed;
  return <section className="management-section" aria-labelledby="modules-title">
    <div className="section-heading"><div><span className="section-index">MÓDULOS</span><h2 id="modules-title">Módulos por condomínio</h2></div></div>
    <p className="field-help">Libere ou suspenda o que cada condomínio pode usar. A flag global do servidor continua mandando: módulo desligado no servidor não liga por aqui. Toda alteração exige motivo e fica na auditoria da plataforma.</p>
    {tenants.isPending ? <div className="panel"><Loading label="Consultando condomínios…" /></div> : failed || !tenants.data ? <ErrorState error={failed} retry={() => void tenants.refetch()} />
      : <div className="panel"><label htmlFor="modules-tenant">Condomínio</label>
        <select id="modules-tenant" value={tenantId} onChange={(event) => setParams(event.target.value ? { condominio: event.target.value } : {}, { replace: true })}>
          <option value="">Escolha…</option>{options.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.name}{item.active ? '' : ' (suspenso)'}</option>)}</select>
        {tenants.data.truncated ? <p className="field-help">A lista foi limitada pelo servidor; para outro condomínio, busque-o em Condomínios e use "Módulos" na ficha.</p> : null}</div>}
    {tenantId && detail.isPending ? <div className="panel"><Loading label="Consultando o condomínio…" /></div> : null}
    {tenantId && detailFailed ? <ErrorState error={detailFailed} retry={() => void detail.refetch()} /> : null}
    {tenant && !detailFailed ? <TenantModules key={tenant.tenantId} tenantId={tenant.tenantId} tenantName={tenant.name} /> : null}
  </section>;
}

function TenantModules({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const scope = usePlatform();
  const modules = useQuery({ queryKey: modulesKey(scope.accountId, tenantId), queryFn: ({ signal }) => tenantModules.list(tenantId, signal) });
  const [editing, setEditing] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const failed = queryFailure([modules]);
  if (useAccessGuard(failed)) return null;
  const manage = scope.session.capabilities.includes('tenant_modules.manage');
  if (modules.isPending) return <div className="panel"><Loading label="Consultando os módulos…" /></div>;
  if (failed instanceof ApiError && failed.code === 'API_NOT_AVAILABLE')
    return <div className="panel"><Empty icon="grid" title="Módulos indisponíveis">A liberação de módulos por condomínio ainda não está ativa neste ambiente.</Empty></div>;
  if (failed && !modules.data) return <ErrorState error={failed} retry={() => void modules.refetch()} />;
  const list = modules.data?.modules ?? [];
  return <>
    <div className="section-heading"><div><h3>Módulos de <bdi>{tenantName}</bdi></h3></div>
      <button className="button button-secondary button-compact" type="button" disabled={modules.isFetching} onClick={() => void modules.refetch()}>Atualizar</button></div>
    {!manage ? <Notice>Sua sessão pode consultar os módulos, mas não alterá-los (falta tenant_modules.manage).</Notice> : null}
    {outcome ? <Notice tone={outcome.tone}>{outcome.text}</Notice> : null}
    {list.length === 0 ? <div className="panel"><Empty icon="grid" title="Nenhum módulo informado">O servidor não devolveu módulos para este condomínio.</Empty></div>
      : <ol className="management-list" aria-label="Módulos do condomínio">{list.map((item) => {
        const status = moduleStatus(item);
        const label = tenantModuleLabels[item.module];
        return <li key={item.module} className="management-row">
          <div><strong>{label}</strong> <span className={`status-pill ${status.tone}`}>{status.text}</span>
            <span>{item.defaultState ? 'Padrão: liberado (a central nunca alterou)' : `Versão ${item.version}${item.updatedAt ? ` · alterado em ${formatWhen(item.updatedAt, PLATFORM_ZONE)}` : ''}${item.updatedBy ? ` · ator ${short(item.updatedBy)}` : ''}`}</span>
            {item.reason ? <span>Motivo: {item.reason}</span> : null}
            {!item.globallyEnabled ? <span>Flag global desligada no servidor: o módulo não funciona em nenhum condomínio.</span> : null}</div>
          {manage && editing !== item.module ? <div className="button-row"><button className={`button button-compact ${item.tenantEnabled ? 'button-secondary' : ''}`} type="button"
            onClick={() => { setOutcome(null); setEditing(item.module); }}>{item.tenantEnabled ? `Suspender ${label}` : `Liberar ${label}`}</button></div> : null}
          {manage && editing === item.module ? <ModuleEditor tenantId={tenantId} item={item} close={(result) => { setEditing(null); if (result) setOutcome(result); }} /> : null}
        </li>;
      })}</ol>}
    {failed && modules.data ? <ErrorState error={failed} retry={() => void modules.refetch()} /> : null}
  </>;
}

function ModuleEditor({ tenantId, item, close }: { tenantId: string; item: TenantModuleState; close(result: Outcome | null): void }) {
  const scope = usePlatform();
  const queries = useQueryClient();
  // Versão e estado lidos ao abrir: nova consulta não troca o alvo da alteração em andamento.
  const [read] = useState({ version: item.version, enabled: item.tenantEnabled });
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const stale = item.version !== read.version;
  const target = !read.enabled;
  const normalized = displayText(reason, 500);
  const valid = normalized !== null && normalized.length >= 3;
  const label = tenantModuleLabels[item.module];
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || pending || stale) return;
    setPending(true); setFailure(null);
    try {
      const change = await tenantModules.change(tenantId, { module: item.module, enabled: target, expectedVersion: read.version, reason: normalized! });
      await queries.invalidateQueries({ queryKey: modulesKey(scope.accountId, tenantId) });
      close(moduleOutcome(change));
    } catch (error) {
      react(error, scope);
      setFailure(error);
      void queries.invalidateQueries({ queryKey: modulesKey(scope.accountId, tenantId) });
    } finally { setPending(false); }
  }
  const uncertain = failure !== null && isUncertain(failure);
  return <form className="reason-form" onSubmit={(event) => void submit(event)} aria-label={`${target ? 'Liberar' : 'Suspender'} ${label}`}>
    {stale ? <Notice tone="warning">Este módulo foi alterado depois que você abriu o formulário (versão {read.version} → {item.version}). Feche e abra de novo para decidir sobre o estado atual.</Notice> : null}
    {!target ? <Notice tone="warning">Suspender tira {label.toLowerCase()} do condomínio na próxima requisição, para moradores, portaria e gestão. Nenhum dado é apagado.</Notice> : null}
    <label htmlFor={`module-reason-${item.module}`}>Motivo (fica na auditoria da plataforma)</label>
    <textarea id={`module-reason-${item.module}`} maxLength={500} required rows={2} value={reason} onChange={(event) => setReason(event.target.value)} disabled={pending} />
    {failure ? <Notice tone={uncertain ? 'warning' : 'error'}>{errorMessage(failure)}{uncertain ? ' Atualize a lista e confira o estado antes de repetir.' : ''}</Notice> : null}
    <div className="button-row">
      <button className={`button ${target ? '' : 'button-danger'}`} type="submit" disabled={!valid || pending || stale}>{pending ? 'Salvando…' : target ? 'Confirmar liberação' : 'Confirmar suspensão'}</button>
      <button className="button button-secondary" type="button" onClick={() => close(null)} disabled={pending}>Voltar</button>
    </div>
  </form>;
}
