import type { Membership } from '../../api/types';

/**
 * Entrada única e casca do condomínio (docs/ESTRUTURA.md, fatias 1 e 6). Funções puras: o roteamento automático, as áreas
 * por papel e o redirecionamento das rotas antigas usam as mesmas regras, testadas sem navegador. O servidor confere o
 * vínculo de novo em cada chamada; aqui só se decide o que aparece.
 */
export type Role = Membership['role'];
export const residentialRoles = ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT', 'OWNER'] as const satisfies readonly Role[];
export const roleLabels: Record<Role, string> = { RESIDENT: 'Morador', RESPONSIBLE: 'Responsável', DEPENDENT: 'Dependente',
  OWNER: 'Proprietário', CONCIERGE: 'Portaria', MANAGER: 'Gestão', SUPPORT: 'Suporte' };

export const ENTRY_PATH = '/entrada';
export const condoPath = (tenantId: string) => `/c/${tenantId}`;
export const condoHomePath = (tenantId: string) => `/c/${tenantId}/inicio`;
export const residentRoot = (tenantId: string) => `/c/${tenantId}/morador`;
export const residentPath = (tenantId: string, unitId: string, section = 'inicio') => `/c/${tenantId}/morador/${unitId}/${section}`;
export const conciergePath = (tenantId: string, section = 'encomendas') => `/c/${tenantId}/portaria/${section}`;
export const managementPath = (tenantId: string, section = 'pendencias') => `/c/${tenantId}/gestao/${section}`;
export const installationPath = (tenantId: string, section = 'roteiro') => `/c/${tenantId}/implantacao/${section}`;
export const centralPath = (section = 'condominios') => `/central/${section}`;
export const centralTenantPath = (tenantId: string) => `/central/condominios/${tenantId}`;

/** Um condomínio da conta, com os papéis e as unidades dos vínculos vigentes informados por /v1/me. */
export interface Condo { tenantId: string; tenantName: string; timeZone: string; roles: Role[]; unitIds: string[] }

const roleOrder: Role[] = ['MANAGER', 'CONCIERGE', 'RESPONSIBLE', 'RESIDENT', 'OWNER', 'DEPENDENT', 'SUPPORT'];

/** Condomínios distintos da conta, em ordem alfabética; papéis sem repetição e unidades residenciais distintas. */
export function condominiums(memberships: readonly Membership[]): Condo[] {
  const byTenant = new Map<string, Condo>();
  for (const membership of memberships) {
    const condo = byTenant.get(membership.tenantId) ?? { tenantId: membership.tenantId, tenantName: membership.tenantName,
      timeZone: membership.timeZone, roles: [], unitIds: [] };
    if (!condo.roles.includes(membership.role)) condo.roles.push(membership.role);
    if (membership.unitId && (residentialRoles as readonly Role[]).includes(membership.role) && !condo.unitIds.includes(membership.unitId)) {
      condo.unitIds.push(membership.unitId);
    }
    byTenant.set(membership.tenantId, condo);
  }
  return [...byTenant.values()].map((condo) => ({ ...condo, roles: condo.roles.toSorted((a, b) => roleOrder.indexOf(a) - roleOrder.indexOf(b)) }))
    .toSorted((a, b) => a.tenantName.localeCompare(b.tenantName, 'pt-BR') || a.tenantId.localeCompare(b.tenantId));
}

export type AreaId = 'inicio' | 'morador' | 'portaria' | 'zeladoria' | 'gestao' | 'implantacao';
export interface Area { id: AreaId; label: string; disabled: boolean }

/**
 * Áreas visíveis dentro do condomínio. Só aparecem as dos papéis da conta. Zeladoria fica desabilitada ("em breve") e
 * só para a gestão, que vai convidar a equipe quando o papel MAINTENANCE existir no servidor; não há rota para ela.
 * Implantação aparece só com concessão técnica confirmada pelo servidor (ou enquanto a própria tela está aberta).
 */
export function condoAreas(condo: Condo | undefined, technical: boolean): Area[] {
  const roles = condo?.roles ?? [];
  const has = (...wanted: Role[]) => wanted.some((role) => roles.includes(role));
  const areas: Area[] = [];
  if (condo) areas.push({ id: 'inicio', label: 'Início', disabled: false });
  if (condo && condo.unitIds.length > 0) areas.push({ id: 'morador', label: 'Morador', disabled: false });
  if (has('CONCIERGE')) areas.push({ id: 'portaria', label: 'Portaria', disabled: false });
  if (has('MANAGER')) areas.push({ id: 'zeladoria', label: 'Zeladoria', disabled: true });
  if (has('MANAGER')) areas.push({ id: 'gestao', label: 'Gestão', disabled: false });
  if (technical) areas.push({ id: 'implantacao', label: 'Implantação', disabled: false });
  return areas;
}

/**
 * Área do papel principal: gestão, depois portaria, depois morador; sem nenhuma delas, o início do condomínio. O morador
 * sempre passa pelo seletor interno, que confere em /units se a unidade do vínculo continua ativa antes de abrir a área
 * (/v1/me mantém o vínculo de uma unidade desativada).
 */
export function principalPath(tenantId: string, condo: Condo | undefined): string {
  if (!condo) return installationPath(tenantId);
  if (condo.roles.includes('MANAGER')) return managementPath(tenantId);
  if (condo.roles.includes('CONCIERGE')) return conciergePath(tenantId);
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
  const area = (['inicio', 'morador', 'portaria', 'gestao', 'implantacao'] as const).find((item) => item === parts[2]) ?? '';
  if (area === 'morador') return { world: 'condo', tenantId, area, ...(parts[3] ? { unitId: parts[3] } : {}), section: parts[4] ?? 'inicio' };
  const defaults: Record<string, string> = { portaria: 'encomendas', gestao: 'pendencias', implantacao: 'roteiro', inicio: '' };
  return { world: 'condo', tenantId, area, section: parts[3] ?? defaults[area] ?? '' };
}
