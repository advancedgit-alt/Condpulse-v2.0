import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, useParams } from 'react-router-dom';
import { accessInstall } from '../../api/accessInstall';
import { api, ApiError } from '../../api/client';
import { useAuth } from '../Auth';
import { technicalSessionKey } from '../installation/InstallationPage';
import { Empty, ErrorState, Icon, Loading, ModuleOff, type IconName } from '../primitives';
import { tenantModuleLabels } from '../../api/tenantModules';
import type { TenantModuleId } from '../../api/types';
import { ENTRY_PATH, activeResidentUnits, condoAreas, condoHomePath, condoOf, conciergeHome, conciergePath, installationPath, maintenancePath, managementPath,
  principalPath, residentPath, residentRoot, type AreaId, type Condo } from './paths';

/** Condomínio da conta pelo id do endereço (undefined quando a conta não tem vínculo nele). */
export function useCondo(tenantId: string): Condo | undefined {
  const { identity } = useAuth();
  return condoOf(identity, tenantId);
}

/**
 * Concessão técnica no condomínio. /v1/me ainda não traz concessões e a gestão/portaria nunca consulta a API técnica
 * (a separação é ensaiada no e2e da implantação). Por isso a casca só observa a sessão técnica que a própria tela de
 * implantação já consultou: a área aparece na tela de implantação e continua no menu enquanto a casca estiver aberta.
 * A entrada é pelo link da central (ficha do condomínio) ou pelo link recebido; a tela confere a concessão de novo.
 */
export function useTechnicalAccess(accountId: string | undefined, tenantId: string | undefined, installing: boolean): boolean {
  const session = useQuery({ queryKey: technicalSessionKey(accountId ?? '', tenantId ?? ''), queryFn: ({ signal }) => accessInstall.session(tenantId!, signal),
    enabled: false });
  const revoked = session.error instanceof ApiError && session.error.code === 'TENANT_ACCESS_DENIED';
  return installing || (Boolean(accountId && tenantId) && session.data !== undefined && !revoked);
}

const areaDetails: Record<Exclude<AreaId, 'inicio'>, { icon: IconName; text: string }> = {
  morador: { icon: 'home', text: 'Sua unidade: acessos, encomendas, reservas, pedidos e financeiro.' },
  portaria: { icon: 'door', text: 'Encomendas, visitas e ocorrências do turno. Exige segundo fator.' },
  zeladoria: { icon: 'truck', text: 'Pedidos de manutenção atribuídos a você: conversa com a unidade e andamento. Exige segundo fator.' },
  gestao: { icon: 'building', text: 'Unidades, pessoas, reservas, avisos, pedidos, financeiro e mapa de acesso. Exige segundo fator.' },
  implantacao: { icon: 'key', text: 'Áreas físicas, pontos, equipamentos, canais e aceite. Exige concessão técnica vigente.' },
};

export function areaPath(tenantId: string, area: AreaId, condo?: Condo): string {
  if (area === 'morador') return residentRoot(tenantId);
  if (area === 'portaria') return conciergePath(tenantId, conciergeHome(condo));
  if (area === 'zeladoria') return maintenancePath(tenantId);
  if (area === 'gestao') return managementPath(tenantId);
  if (area === 'implantacao') return installationPath(tenantId);
  return `/c/${tenantId}/inicio`;
}

/** /c/{condomínio}: abre direto a área do papel principal. */
export function CondoIndex() {
  const { tenantId = '' } = useParams();
  const condo = useCondo(tenantId);
  return <Navigate to={principalPath(tenantId, condo)} replace />;
}

/** /c/{condomínio}/portaria: abre a primeira seção liberada pela administradora. */
export function ConciergeIndex() {
  const { tenantId = '' } = useParams();
  return <Navigate to={conciergePath(tenantId, conciergeHome(useCondo(tenantId)))} replace />;
}

/**
 * Rota de um módulo desligado pela administradora, aberta direto pelo endereço: explica o motivo e não consulta a API.
 * O menu já esconde a seção; o servidor continua respondendo 403 MODULE_DISABLED.
 */
export function CondoModuleOff({ tenantId, modules }: { tenantId: string; modules: readonly TenantModuleId[] }) {
  const names = modules.map((module) => tenantModuleLabels[module]).join(' / ');
  return <><ModuleOff>{names} não está liberado para este condomínio. A administradora pode religar o módulo na central; quando isso acontecer, a
    área volta ao menu. Nenhum dado foi apagado.</ModuleOff>
    <Link className="button button-secondary" to={condoHomePath(tenantId)}>Voltar ao início do condomínio</Link></>;
}

/** Início do condomínio: atalhos para as áreas dos papéis da conta. */
export function CondoHome() {
  const { identity } = useAuth();
  const { tenantId = '' } = useParams();
  const condo = useCondo(tenantId);
  const technical = useTechnicalAccess(identity?.accountId, tenantId, false);
  const areas = condoAreas(condo, technical).filter((area) => area.id !== 'inicio');
  if (!condo && !technical) return <div className="panel"><Empty icon="shield" title="Condomínio indisponível">Seu cadastro não tem vínculo vigente neste condomínio.</Empty>
    <Link className="button button-secondary" to={ENTRY_PATH}>Ver meus acessos</Link></div>;
  return <>
    <div className="residence-heading"><div><span className="eyebrow">CONDOMÍNIO</span><h1><bdi>{condo?.tenantName ?? 'Condomínio'}</bdi></h1>
      <p>Escolha a área. Você vê apenas as áreas dos seus papéis; trocar de área não sai do condomínio.</p></div></div>
    {areas.length === 0 ? <div className="panel"><Empty icon="shield" title="Nenhuma área disponível">Seu vínculo neste condomínio ainda não dá acesso a uma área da aplicação. Procure a gestão.</Empty></div>
      : <ul className="area-cards" aria-label="Áreas do condomínio">{areas.map((area) => <li key={area.id}>
        {area.disabled ? <div className="area-card area-card-disabled" aria-disabled="true"><span className="round-icon"><Icon name={areaDetails[area.id as keyof typeof areaDetails].icon} /></span>
          <span><strong>{area.label}</strong> <span className="status-pill status-neutral">Desligado pela administradora</span><span>{areaDetails[area.id as keyof typeof areaDetails].text}</span></span></div>
          : <Link className="area-card" to={areaPath(tenantId, area.id, condo)}><span className="round-icon"><Icon name={areaDetails[area.id as keyof typeof areaDetails].icon} /></span>
            <span><strong>{area.label}</strong><span>{areaDetails[area.id as keyof typeof areaDetails].text}</span></span><Icon name="arrow" /></Link>}
      </li>)}</ul>}
  </>;
}

/**
 * Área do morador sem unidade no endereço. Sempre confere em /units quais unidades dos vínculos continuam ativas
 * (/v1/me mantém o vínculo de unidade desativada): uma ativa abre direto; mais de uma mostra o seletor interno; nenhuma
 * mostra o estado vazio, sem redirecionar para uma tela que devolveria a pessoa para cá.
 */
export function ResidentUnitPicker() {
  const { identity } = useAuth();
  const { tenantId = '' } = useParams();
  const condo = useCondo(tenantId);
  const queries = useQueryClient();
  const unitIds = condo?.unitIds ?? [];
  const [denied, setDenied] = useState<unknown>(null);
  const units = useQuery({ queryKey: ['private', identity?.accountId, 'tenant', tenantId, 'units'], queryFn: ({ signal }) => api.units(tenantId, signal),
    enabled: Boolean(identity) && unitIds.length > 0 && !denied });
  useEffect(() => {
    if (units.error instanceof ApiError && [403, 404].includes(units.error.status)) {
      setDenied(units.error);
      void queries.cancelQueries({ queryKey: ['private', identity?.accountId, 'tenant', tenantId] });
      queries.removeQueries({ queryKey: ['private', identity?.accountId, 'tenant', tenantId] });
      void queries.invalidateQueries({ queryKey: ['identity'] });
    }
  }, [units.error, queries, identity?.accountId, tenantId]);
  if (!identity) return null;
  const home = <Link className="button button-secondary" to={`/c/${tenantId}/inicio`}>Voltar ao início do condomínio</Link>;
  if (unitIds.length === 0) return <div className="panel"><Empty icon="home" title="Nenhuma unidade vinculada">Seu cadastro não tem unidade vigente neste condomínio. A gestão precisa validar o vínculo.</Empty>{home}</div>;
  if (denied || (units.isError && !units.data)) return <ErrorState error={denied ?? units.error} retry={() => { setDenied(null); void units.refetch(); }} />;
  if (units.isPending) return <div className="panel"><Loading label="Conferindo suas unidades…" /></div>;
  const available = activeResidentUnits(unitIds, units.data ?? []);
  if (available.length === 0) return <div className="panel"><Empty icon="home" title="Nenhuma unidade ativa">Não há unidade ativa disponível nos seus vínculos. Procure a gestão do condomínio.</Empty>{home}</div>;
  if (available.length === 1) return <Navigate to={residentPath(tenantId, available[0]!.unitId)} replace />;
  return <>
    <div className="residence-heading"><div><span className="eyebrow">MORADOR</span><h1>Escolha a unidade.</h1>
      <p>Reservas, acessos, encomendas e financeiro aparecem de acordo com a unidade escolhida. O vínculo é conferido de novo em cada operação.</p></div></div>
    <ul className="area-cards" aria-label="Unidades">{available.map((unit) => <li key={unit.unitId}>
      <Link className="area-card" to={residentPath(tenantId, unit.unitId)}><span className="round-icon"><Icon name="home" /></span>
        <span><strong><bdi>{unit.label}</bdi></strong><span>Abrir a área do morador desta unidade</span></span><Icon name="arrow" /></Link></li>)}</ul>
  </>;
}
