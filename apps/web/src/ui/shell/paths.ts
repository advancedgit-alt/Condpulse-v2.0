import type { CurrentIdentity, Membership, TenantModuleId, TenantModules } from '../../api/types';

/**
 * Entrada única e casca do condomínio (docs/ESTRUTURA.md, fatias 1 e 6). Funções puras: o roteamento automático, as áreas
 * por papel e o redirecionamento das rotas antigas usam as mesmas regras, testadas sem navegador. O servidor confere o
 * vínculo de novo em cada chamada; aqui só se decide o que aparece.
 */
export type Role = Membership['role'];
export const residentialRoles = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER'] as const satisfies readonly Role[];
export const roleLabels: Record<Role, string> = { RESIDENT: 'Morador', RESPONSIBLE: 'Responsável', DEPENDENT: 'Dependente',
  OWNER: 'Proprietário', CONCIERGE: 'Portaria', MAINTENANCE: 'Zeladoria', MANAGER: 'Gestão', SUPPORT: 'Suporte' };

export const ENTRY_PATH = '/entrada';
export const condoPath = (tenantId: string) => `/c/${tenantId}`;
export const condoHomePath = (tenantId: string) => `/c/${tenantId}/inicio`;
export const residentRoot = (tenantId: string) => `/c/${tenantId}/morador`;
export const residentPath = (tenantId: string, unitId: string, section = 'inicio') => `/c/${tenantId}/morador/${unitId}/${section}`;
export const conciergePath = (tenantId: string, section = 'encomendas') => `/c/${tenantId}/portaria/${section}`;
export const managementPath = (tenantId: string, section = 'pendencias') => `/c/${tenantId}/gestao/${section}`;
export const installationPath = (tenantId: string, section = 'roteiro') => `/c/${tenantId}/implantacao/${section}`;
export const maintenancePath = (tenantId: string, requestId?: string) => `/c/${tenantId}/zeladoria/pedidos${requestId ? `/${requestId}` : ''}`;
export const centralPath = (section = 'condominios') => `/central/${section}`;
export const centralTenantPath = (tenantId: string) => `/central/condominios/${tenantId}`;

/**
 * Um condomínio da conta, com os papéis e as unidades dos vínculos vigentes informados por /v1/me e os módulos liberados
 * pela central (V045). modules null: o servidor não informou, então nada é escondido (a API responde 403 MODULE_DISABLED).
 */
export interface Condo { tenantId: string; tenantName: string; timeZone: string; roles: Role[]; unitIds: string[]; modules: readonly TenantModuleId[] | null }

const roleOrder: Role[] = ['MANAGER', 'CONCIERGE', 'MAINTENANCE', 'RESPONSIBLE', 'RESIDENT', 'OWNER', 'DEPENDENT', 'SUPPORT'];

/** Condomínios distintos da conta, em ordem alfabética; papéis sem repetição e unidades residenciais distintas. */
export function condominiums(memberships: readonly Membership[], tenantModules: readonly TenantModules[] | null = null): Condo[] {
  const byTenant = new Map<string, Condo>();
  const modulesOf = (tenantId: string) => tenantModules?.find((item) => item.tenantId.toLowerCase() === tenantId.toLowerCase())?.enabled ?? null;
  for (const membership of memberships) {
    const condo = byTenant.get(membership.tenantId) ?? { tenantId: membership.tenantId, tenantName: membership.tenantName,
      timeZone: membership.timeZone, roles: [], unitIds: [], modules: modulesOf(membership.tenantId) };
    if (!condo.roles.includes(membership.role)) condo.roles.push(membership.role);
    if (membership.unitId && (residentialRoles as readonly Role[]).includes(membership.role) && !condo.unitIds.includes(membership.unitId)) {
      condo.unitIds.push(membership.unitId);
    }
    byTenant.set(membership.tenantId, condo);
  }
  return [...byTenant.values()].map((condo) => ({ ...condo, roles: condo.roles.toSorted((a, b) => roleOrder.indexOf(a) - roleOrder.indexOf(b)) }))
    .toSorted((a, b) => a.tenantName.localeCompare(b.tenantName, 'pt-BR') || a.tenantId.localeCompare(b.tenantId));
}

/** Condomínio da conta pelo id (undefined sem vínculo vigente nele), com os módulos informados por /v1/me. */
export function condoOf(identity: CurrentIdentity | null | undefined, tenantId: string): Condo | undefined {
  return condominiums(identity?.memberships ?? [], identity?.tenantModules ?? null).find((condo) => condo.tenantId === tenantId);
}

export type AreaId = 'inicio' | 'morador' | 'portaria' | 'zeladoria' | 'gestao' | 'implantacao';
/** disabled: todos os módulos da área foram desligados pela administradora; a área aparece sem link, com o motivo. */
export interface Area { id: AreaId; label: string; disabled: boolean }

/**
 * Módulos de cada seção (qualquer um basta), na mesma regra das rotas do servidor (@RequiresTenantModule). Seção fora da
 * tabela não depende de módulo. As áreas inteiras de zeladoria e implantação dependem de um módulo só.
 */
export const sectionModules: Partial<Record<AreaId, Record<string, readonly TenantModuleId[]>>> = {
  morador: { reservas: ['reservations'], encomendas: ['parcels'], acessos: ['visits'], financeiro: ['finance'] },
  portaria: { encomendas: ['parcels'], visitas: ['visits'], ocorrencias: ['visits'] },
  gestao: { reservas: ['reservations'], areas: ['reservations'], avisos: ['announcements'], pedidos: ['requests'], mudancas: ['requests'],
    acesso: ['access_credentials'], 'mapa-acesso': ['facial_access'], ocorrencias: ['visits'], financeiro: ['finance'] },
};
const areaModules: Partial<Record<AreaId, readonly TenantModuleId[]>> = {
  portaria: ['parcels', 'visits'], zeladoria: ['requests'], implantacao: ['facial_access'],
};
export const conciergeSectionOrder = ['encomendas', 'visitas', 'ocorrencias'] as const;

/** Módulo liberado no condomínio; sem informação do servidor, liberado (a API confere de novo). */
export function moduleEnabled(condo: Pick<Condo, 'modules'> | undefined, module: TenantModuleId): boolean {
  return !condo || condo.modules === null || condo.modules.includes(module);
}
const anyEnabled = (condo: Pick<Condo, 'modules'> | undefined, modules: readonly TenantModuleId[] | undefined) =>
  !modules || modules.some((module) => moduleEnabled(condo, module));

/** Módulos exigidos pelo lugar (área e seção); null quando não depende de módulo. */
export function placeModules(area: AreaId | '', section: string): readonly TenantModuleId[] | null {
  if (!area) return null;
  if (area === 'zeladoria' || area === 'implantacao') return areaModules[area]!;
  return sectionModules[area]?.[section] ?? null;
}
/** A seção da área está liberada para o condomínio. */
export function sectionEnabled(condo: Pick<Condo, 'modules'> | undefined, area: AreaId, section: string): boolean {
  return anyEnabled(condo, placeModules(area, section) ?? undefined);
}
/** Primeira seção liberada da portaria (encomendas, visitas, ocorrências); a primeira quando nenhuma está. */
export function conciergeHome(condo: Pick<Condo, 'modules'> | undefined): string {
  return conciergeSectionOrder.find((section) => sectionEnabled(condo, 'portaria', section)) ?? 'encomendas';
}

/**
 * Áreas visíveis dentro do condomínio. Só aparecem as dos papéis da conta: zeladoria só para a equipe de zeladoria
 * (MAINTENANCE, V046; a gestão acompanha os pedidos na própria área). Área cujos módulos foram todos desligados pela
 * administradora aparece desabilitada. Implantação aparece só com concessão técnica confirmada pelo servidor (ou
 * enquanto a própria tela está aberta).
 */
export function condoAreas(condo: Condo | undefined, technical: boolean): Area[] {
  const roles = condo?.roles ?? [];
  const has = (...wanted: Role[]) => wanted.some((role) => roles.includes(role));
  const off = (id: AreaId) => !anyEnabled(condo, areaModules[id]);
  const areas: Area[] = [];
  if (condo) areas.push({ id: 'inicio', label: 'Início', disabled: false });
  if (condo && condo.unitIds.length > 0) areas.push({ id: 'morador', label: 'Morador', disabled: false });
  if (has('CONCIERGE')) areas.push({ id: 'portaria', label: 'Portaria', disabled: off('portaria') });
  if (has('MAINTENANCE')) areas.push({ id: 'zeladoria', label: 'Zeladoria', disabled: off('zeladoria') });
  if (has('MANAGER')) areas.push({ id: 'gestao', label: 'Gestão', disabled: false });
  if (technical) areas.push({ id: 'implantacao', label: 'Implantação', disabled: off('implantacao') });
  return areas;
}

/**
 * Área do papel principal: gestão, depois portaria, zeladoria e morador (pulando área com os módulos desligados); sem
 * nenhuma delas, o início do condomínio. O morador sempre passa pelo seletor interno, que confere em /units se a unidade
 * do vínculo continua ativa antes de abrir a área (/v1/me mantém o vínculo de uma unidade desativada).
 */
export function principalPath(tenantId: string, condo: Condo | undefined): string {
  if (!condo) return installationPath(tenantId);
  const open = (id: AreaId) => condoAreas(condo, false).some((area) => area.id === id && !area.disabled);
  if (open('gestao')) return managementPath(tenantId);
  if (open('portaria')) return conciergePath(tenantId, conciergeHome(condo));
  if (open('zeladoria')) return maintenancePath(tenantId);
  if (condo.unitIds.length > 0) return residentRoot(tenantId);
  return condoHomePath(tenantId);
}

/**
 * Unidades do morador que podem abrir: só as dos vínculos residenciais que /units ainda devolve (a API lista só unidades
 * ativas). Mantém a ordem dos vínculos e o rótulo exibido (bloco · unidade).
 */
export function activeResidentUnits(unitIds: readonly string[], units: readonly { id: string; label: string; blockName: string | null }[]) {
  return unitIds.flatMap((unitId) => {
    const unit = units.find((item) => item.id === unitId);
    return unit ? [{ unitId, label: `${unit.blockName ? `${unit.blockName} · ` : ''}${unit.label}` }] : [];
  });
}

/** Resultado da consulta de papel de plataforma feita na entrada. */
export type PlatformAccess = 'pending' | 'yes' | 'no' | 'unknown';
export type EntryDecision = { kind: 'wait' } | { kind: 'redirect'; to: string } | { kind: 'select' } | { kind: 'empty' };

/**
 * Roteamento automático depois do login: só plataforma → central; um condomínio → área do papel principal; vários (ou
 * plataforma e condomínio) → seletor só de condomínio. Convite aberto nesta janela ou falha ao consultar a plataforma
 * mantêm o seletor para a pessoa ver o aviso, sem pular para uma tela.
 */
export function entryDecision(condos: readonly Condo[], platform: PlatformAccess, invitationOpen: boolean): EntryDecision {
  if (platform === 'pending') return { kind: 'wait' };
  if (invitationOpen || platform === 'unknown') return condos.length === 0 && platform !== 'unknown' ? { kind: 'empty' } : { kind: 'select' };
  if (platform === 'yes') return condos.length === 0 ? { kind: 'redirect', to: centralPath() } : { kind: 'select' };
  if (condos.length === 1) return { kind: 'redirect', to: principalPath(condos[0]!.tenantId, condos[0]) };
  return condos.length === 0 ? { kind: 'empty' } : { kind: 'select' };
}

const centralRenames: Record<string, string> = { fila: 'operacao' };
/** Seção antiga do painel master no endereço novo da central. */
export const centralSection = (section: string) => centralRenames[section] ?? section;

/**
 * Endereço novo para uma rota antiga (/contextos, /morador, /portaria, /gestao, /implantacao, /master), preservando
 * busca e fragmento. Devolve null quando o caminho não é uma rota antiga conhecida.
 */
export function legacyTarget(pathname: string, search = '', hash = ''): string | null {
  const parts = pathname.split('/').filter(Boolean);
  const [head, tenantId, a, b] = parts;
  const keep = (path: string) => `${path}${search}${hash}`;
  switch (head) {
    case 'contextos': return parts.length === 1 ? keep(ENTRY_PATH) : null;
    case 'master':
      if (!tenantId) return keep('/central');
      if (tenantId === 'condominios' && a && !b) return keep(centralTenantPath(a));
      return a ? null : keep(centralPath(centralSection(tenantId)));
    case 'morador':
      if (!tenantId) return keep(ENTRY_PATH);
      if (!a) return keep(residentRoot(tenantId));
      return parts.length <= 4 ? keep(residentPath(tenantId, a, b ?? 'inicio')) : null;
    case 'portaria':
      if (!tenantId) return keep(ENTRY_PATH);
      return parts.length <= 3 ? keep(conciergePath(tenantId, a ?? 'encomendas')) : null;
    case 'gestao':
      if (!tenantId) return keep(ENTRY_PATH);
      return parts.length <= 3 ? keep(a ? managementPath(tenantId, a) : `/c/${tenantId}/gestao`) : null;
    case 'implantacao':
      if (!tenantId) return keep(ENTRY_PATH);
      return parts.length <= 3 ? keep(a ? installationPath(tenantId, a) : `/c/${tenantId}/implantacao`) : null;
    default: return null;
  }
}

/** Onde a pessoa está, lido do endereço: entrada, central ou casca de um condomínio (com área e seção). */
export type Place =
  | { world: 'entry' }
  | { world: 'central'; section: string }
  | { world: 'condo'; tenantId: string; area: AreaId | ''; unitId?: string; section: string };

export function placeOf(pathname: string): Place {
  const parts = pathname.split('/').filter(Boolean);
  if (parts[0] === 'central') return { world: 'central', section: parts[1] ?? 'condominios' };
  if (parts[0] !== 'c' || !parts[1]) return { world: 'entry' };
  const tenantId = parts[1];
  const area = (['inicio', 'morador', 'portaria', 'zeladoria', 'gestao', 'implantacao'] as const).find((item) => item === parts[2]) ?? '';
  if (area === 'morador') return { world: 'condo', tenantId, area, ...(parts[3] ? { unitId: parts[3] } : {}), section: parts[4] ?? 'inicio' };
  const defaults: Record<string, string> = { portaria: 'encomendas', zeladoria: 'pedidos', gestao: 'pendencias', implantacao: 'roteiro', inicio: '' };
  return { world: 'condo', tenantId, area, section: parts[3] ?? defaults[area] ?? '' };
}
