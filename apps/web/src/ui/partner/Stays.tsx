import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { stays } from '../../api/stays';
import { Empty, Loading, Notice } from '../primitives';
import { formatWhen } from '../dateTime';
import { StayList } from '../stays/StayList';
import { staySummary } from '../stays/model';
import { FailureState, usePartnerAccessGuard, usePartnerScope } from './PartnerPage';
import { partnerStaysKey, reachedTenant } from './model';

const OFF_TEXT = 'As visitas e permanências estão desligadas no servidor deste ambiente (CP_VISITS_ENABLED) ou o módulo de visitas está suspenso para o condomínio.';

/**
 * Permanências no painel do parceiro (CPX-017): comando (security.command), turno (ops.shift.supervise) e console
 * (ops.remote.operate), por condomínio alcançado agora (contrato e, para operação, escala). Só leitura nesta versão; a
 * consulta grava SENSITIVE_VIEWED com origem central remota, por isso só roda ao abrir e ao pedir "Atualizar".
 */
export function PartnerStaysSection() {
  const { context } = usePartnerScope();
  const [chosen, setTenantId] = useState(context.tenants[0]?.tenantId ?? '');
  const tenant = reachedTenant(context.tenants, chosen);
  return <section className="management-section" aria-labelledby="partner-stays-title">
    <div className="section-heading"><div><span className="section-index">PERMANÊNCIAS</span><h2 id="partner-stays-title">Quem está agora</h2></div></div>
    <p className="field-help">Permanências abertas pelas entradas registradas no condomínio, vencidas primeiro, com a origem de cada dado. Sem registro de saída não prova que a pessoa continua no condomínio.</p>
    {context.tenants.length === 0 ? <div className="panel"><Empty icon="building" title="Nenhum condomínio alcançado agora">As permanências ficam disponíveis para os condomínios com contrato ativo (e, para operação, escala vigente).</Empty></div>
      : <>{context.tenants.length > 1 ? <div className="reservation-filter"><label htmlFor="partner-stays-tenant">Condomínio</label>
        <select id="partner-stays-tenant" value={tenant?.tenantId ?? ''} onChange={(event) => setTenantId(event.target.value)}>
          {context.tenants.map((item) => <option key={item.tenantId} value={item.tenantId}>{item.tenantName}</option>)}</select></div> : null}
        {tenant ? <TenantStays key={tenant.tenantId} tenantId={tenant.tenantId} tenantName={tenant.tenantName} timeZone={tenant.timeZone} /> : null}</>}
  </section>;
}

function TenantStays({ tenantId, tenantName, timeZone }: { tenantId: string; tenantName: string; timeZone: string }) {
  const scope = usePartnerScope();
  const [overdueOnly, setOverdueOnly] = useState(false);
  const list = useQuery({ queryKey: partnerStaysKey(scope.accountId, tenantId, 'open', overdueOnly), retry: false, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => stays.open(tenantId, overdueOnly, signal) });
  const guarded = usePartnerAccessGuard(list.error, tenantId);
  const refused = scope.deniedTenant(tenantId);
  if (refused) return <FailureState error={refused} retry={() => undefined} off={OFF_TEXT} subject="Permanências" />;
  if (guarded) return null;
  return <div className="stack-form">
    <div className="button-row">
      <label className="checkbox-row"><input type="checkbox" checked={overdueOnly} onChange={(event) => setOverdueOnly(event.target.checked)} /> Só as vencidas</label>
      <button className="button button-secondary button-compact" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>Atualizar permanências</button>
    </div>
    {list.isPending ? <div className="panel"><Loading label="Consultando permanências…" /></div>
      : list.isError && !list.data ? <FailureState error={list.error} retry={() => void list.refetch()} off={OFF_TEXT} subject="Permanências" />
        : list.data ? <>
          <p className="field-help"><bdi>{tenantName}</bdi>: {staySummary(list.data.stays)}. Consultado às {formatWhen(list.data.generatedAt, timeZone)} ({timeZone}); a consulta fica no Log de Segurança.</p>
          {list.data.stays.length === 0 ? <div className="panel"><Empty icon="door" title={overdueOnly ? 'Nenhuma permanência vencida' : 'Nenhuma permanência aberta'}>Cada entrada registrada abre uma permanência; a saída a encerra.</Empty></div>
            : <StayList stays={list.data.stays} timeZone={timeZone} label={`Permanências abertas de ${tenantName}`} />}
          {list.data.truncated ? <Notice tone="warning">A lista mostra as 200 primeiras permanências.</Notice> : null}
        </> : null}
  </div>;
}
