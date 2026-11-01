import type { CurrentIdentity, Membership, PartnerCapability, PartnerContext, PartnerPanel, PartnerRole, TenantModuleId, TenantModules } from '../../api/types';

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
export const partnerPath = (partnerId: string, panel: PartnerPanel, section?: string) => `/p/${partnerId}/${panel}${section ? `/${section}` : ''}`;

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
 * Fonte única de módulo por painel: cada painel é um conjunto de chamadas à API e leva o módulo das rotas do servidor
 * (@RequiresTenantModule) que ele consulta; null marca painel que não depende de módulo. A chave é área, área.seção ou
 * área.seção.painel. Menu, guarda de rota e painéis leem esta tabela: uma seção fica liberada quando algum painel dela
 * está, e uma área (portaria, zeladoria, implantação) só fica desabilitada quando nenhum painel dela está. Seção sem
 * painel na tabela não depende de módulo.
 */
export const panelModules = {
  'morador.inicio.caixa': null, 'morador.inicio.avisos': 'announcements', 'morador.inicio.pedidos': 'requests', 'morador.inicio.mudancas': 'requests',
  'morador.inicio.reservas': 'reservations', 'morador.inicio.financeiro': 'finance',
  'morador.reservas': 'reservations', 'morador.encomendas': 'parcels', 'morador.financeiro': 'finance',
  'morador.acessos.visitas': 'visits', 'morador.acessos.permanencias': 'visits', 'morador.acessos.equipamentos': 'access_credentials',
  'portaria.encomendas': 'parcels', 'portaria.ocorrencias': 'visits',
  'portaria.visitas.chegadas': 'visits', 'portaria.visitas.permanencias': 'visits', 'portaria.visitas.revogacoes': 'access_credentials', 'portaria.visitas.saude': 'access_credentials',
  'portaria.visitas.eventos': 'access_credentials', 'portaria.visitas.mudancas': 'requests',
  'zeladoria': 'requests',
  'gestao.pendencias.pessoas': null, 'gestao.pendencias.reservas': 'reservations',
  'gestao.reservas': 'reservations', 'gestao.areas': 'reservations', 'gestao.avisos': 'announcements', 'gestao.pedidos': 'requests',
  'gestao.mudancas': 'requests', 'gestao.acesso': 'access_credentials', 'gestao.mapa-acesso': 'facial_access', 'gestao.ocorrencias': 'visits',
  'gestao.financeiro': 'finance',
  'implantacao': 'facial_access',
} as const satisfies Record<string, TenantModuleId | null>;
export type PanelId = keyof typeof panelModules;
const panelIds = Object.keys(panelModules) as PanelId[];
export const conciergeSectionOrder = ['encomendas', 'visitas', 'ocorrencias'] as const;

/** Módulo liberado no condomínio; sem informação do servidor, liberado (a API confere de novo). */
export function moduleEnabled(condo: Pick<Condo, 'modules'> | undefined, module: TenantModuleId): boolean {
  return !condo || condo.modules === null || condo.modules.includes(module);
}
/** O painel pode aparecer: não depende de módulo ou o módulo dele está liberado. */
export function panelEnabled(condo: Pick<Condo, 'modules'> | undefined, panel: PanelId): boolean {
  const module: TenantModuleId | null = panelModules[panel];
  return module === null || moduleEnabled(condo, module);
}
/** Painéis de uma área (section omitida) ou de uma seção: chave da área inteira, da seção ou de painel da seção. */
function panelsOf(area: AreaId, section?: string): PanelId[] {
  return panelIds.filter((id) => id === area || (section === undefined ? id.startsWith(`${area}.`)
    : id === `${area}.${section}` || id.startsWith(`${area}.${section}.`)));
}

/** Módulos dos painéis do lugar (área e seção), sem repetição; null quando o lugar não depende de módulo. */
export function placeModules(area: AreaId | '', section: string): readonly TenantModuleId[] | null {
  if (!area) return null;
  const panels = panelsOf(area, section);
  if (panels.length === 0 || panels.some((panel) => panelModules[panel] === null)) return null;
  return [...new Set(panels.map((panel) => panelModules[panel] as TenantModuleId))];
}
/** A seção da área está liberada: sem painel dependente de módulo ou com algum painel liberado. */
export function sectionEnabled(condo: Pick<Condo, 'modules'> | undefined, area: AreaId, section: string): boolean {
  const panels = panelsOf(area, section);
  return panels.length === 0 || panels.some((panel) => panelEnabled(condo, panel));
}
/** Área só de painéis dependentes de módulo (portaria, zeladoria, implantação) com todos eles desligados. */
function areaOff(condo: Pick<Condo, 'modules'> | undefined, area: AreaId): boolean {
  const panels = panelsOf(area);
  return panels.length > 0 && !panels.some((panel) => panelEnabled(condo, panel));
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
  const off = (id: AreaId) => areaOff(condo, id);
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
 * Roteamento automático depois do login (R4.1/R4.2): só plataforma → central; um único contexto (um condomínio ou um
 * papel de parceiro) → painel dele; vários contextos (ou plataforma e outro contexto) → seletor. Convite aberto nesta
 * janela ou falha ao consultar a plataforma mantêm o seletor para a pessoa ver o aviso, sem pular para uma tela.
 * Cada papel de parceiro conta como um contexto: o mesmo parceiro com dois papéis mostra dois cartões.
 */
export function entryDecision(condos: readonly Condo[], platform: PlatformAccess, invitationOpen: boolean,
  partners: readonly PartnerContext[] = []): EntryDecision {
  const contexts = condos.length + partners.length;
  if (platform === 'pending') return { kind: 'wait' };
  if (invitationOpen || platform === 'unknown') return contexts === 0 && platform !== 'unknown' ? { kind: 'empty' } : { kind: 'select' };
  if (platform === 'yes') return contexts === 0 ? { kind: 'redirect', to: centralPath() } : { kind: 'select' };
  if (contexts === 1) return condos.length === 1 ? { kind: 'redirect', to: principalPath(condos[0]!.tenantId, condos[0]) }
    : { kind: 'redirect', to: partnerHomePath(partners[0]!) };
  return contexts === 0 ? { kind: 'empty' } : { kind: 'select' };
}

export const partnerRoleLabels: Record<PartnerRole, string> = { PARTNER_ADMIN: 'Gestor do parceiro', SECURITY_LEAD: 'Gestor líder de segurança',
  SECURITY_SUPERVISOR: 'Supervisor de operação', REMOTE_OPERATOR: 'Operador de portaria remota', TECHNICIAN: 'Técnico / integrador' };
export const partnerPanelLabels: Record<PartnerPanel, string> = { gestao: 'Painel do Parceiro', comando: 'Comando de Segurança',
  turno: 'Painel do Turno', console: 'Console Remoto', tecnico: 'Painel Técnico' };
const panelOrder: PartnerPanel[] = ['gestao', 'comando', 'turno', 'console', 'tecnico'];

/** Contextos de parceiro da conta em ordem estável: nome do parceiro, id e painel. */
export function partnerEntries(contexts: readonly PartnerContext[]): PartnerContext[] {
  return contexts.toSorted((a, b) => a.partnerName.localeCompare(b.partnerName, 'pt-BR') || a.partnerId.localeCompare(b.partnerId)
    || panelOrder.indexOf(a.panel) - panelOrder.indexOf(b.panel));
}
/** Contexto do endereço /p/{parceiro}/{painel}: o papel ativo da conta naquele parceiro com aquele painel. */
export function partnerContextOf(contexts: readonly PartnerContext[], partnerId: string, panel: string): PartnerContext | undefined {
  return contexts.find((context) => context.partnerId.toLowerCase() === partnerId.toLowerCase() && context.panel === panel);
}

/**
 * Seções de cada painel de parceiro, geradas pelas capacidades do papel (R4.5): sem a capacidade a seção não existe no
 * menu, e a API recusa de qualquer forma. ready=false marca seção sem backend nesta versão: aparece "em breve", sem
 * rota nem dado.
 */
export type PartnerSectionId = 'equipe' | 'escala' | 'carteira' | 'log' | 'carteira-seguranca' | 'aberturas' | 'politicas' | 'bloqueio'
  | 'turno' | 'log-turno' | 'fila' | 'equipamentos' | 'permanencias';
export interface PartnerSection { id: PartnerSectionId; label: string; capability: PartnerCapability; ready: boolean }
const partnerSectionCatalog: Record<PartnerPanel, readonly PartnerSection[]> = {
  gestao: [{ id: 'equipe', label: 'Equipe', capability: 'partner.team.manage', ready: true },
    { id: 'escala', label: 'Escala', capability: 'partner.team.manage', ready: true },
    { id: 'carteira', label: 'Carteira', capability: 'partner.manage', ready: true },
    { id: 'log', label: 'Log de Segurança', capability: 'security.log.read', ready: true }],
  comando: [{ id: 'carteira-seguranca', label: 'Visão da carteira', capability: 'security.command', ready: false },
    { id: 'log', label: 'Log de Segurança', capability: 'security.log.read', ready: true },
    { id: 'permanencias', label: 'Permanências', capability: 'security.command', ready: true },
    { id: 'aberturas', label: 'Revisão de aberturas', capability: 'security.remote_open.review', ready: false },
    { id: 'politicas', label: 'Políticas de segurança', capability: 'security.policy.publish', ready: false },
    { id: 'bloqueio', label: 'Lista de bloqueio', capability: 'security.blocklist.approve', ready: false }],
  // O servidor ainda recusa a leitura de turno (security.log.read.shift) no log: fica em breve.
  turno: [{ id: 'turno', label: 'Fila do turno', capability: 'ops.shift.supervise', ready: false },
    { id: 'permanencias', label: 'Permanências', capability: 'ops.shift.supervise', ready: true },
    { id: 'log-turno', label: 'Log do turno', capability: 'security.log.read.shift', ready: false }],
  console: [{ id: 'fila', label: 'Fila de atendimento', capability: 'ops.remote.operate', ready: false },
    { id: 'permanencias', label: 'Permanências', capability: 'ops.remote.operate', ready: true }],
  tecnico: [{ id: 'equipamentos', label: 'Equipamentos', capability: 'devices.commission', ready: false }],
};
export function partnerSections(context: Pick<PartnerContext, 'panel' | 'capabilities'>): PartnerSection[] {
  return partnerSectionCatalog[context.panel].filter((section) => context.capabilities.includes(section.capability));
}
/** Seção exibida: a pedida, se liberada e pronta; senão a primeira pronta; sem nenhuma pronta, null (painel em breve). */
export function currentPartnerSection(context: Pick<PartnerContext, 'panel' | 'capabilities'>, section: string): PartnerSectionId | null {
  const ready = partnerSections(context).filter((item) => item.ready);
  return ready.find((item) => item.id === section)?.id ?? ready[0]?.id ?? null;
}
/** Destino do papel na entrada: painel do parceiro na primeira seção pronta (ou o painel, em breve). */
export function partnerHomePath(context: Pick<PartnerContext, 'partnerId' | 'panel' | 'capabilities'>): string {
  const section = currentPartnerSection(context, '');
  return partnerPath(context.partnerId, context.panel, section ?? undefined);
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

/** Onde a pessoa está, lido do endereço: entrada, central, casca de um condomínio (com área e seção) ou de um parceiro. */
export type Place =
  | { world: 'entry' }
  | { world: 'central'; section: string }
  | { world: 'condo'; tenantId: string; area: AreaId | ''; unitId?: string; section: string }
  | { world: 'partner'; partnerId: string; panel: string; section: string };

export function placeOf(pathname: string): Place {
  const parts = pathname.split('/').filter(Boolean);
  if (parts[0] === 'central') return { world: 'central', section: parts[1] ?? 'condominios' };
  if (parts[0] === 'p' && parts[1]) return { world: 'partner', partnerId: parts[1], panel: parts[2] ?? '', section: parts[3] ?? '' };
  if (parts[0] !== 'c' || !parts[1]) return { world: 'entry' };
  const tenantId = parts[1];
  const area = (['inicio', 'morador', 'portaria', 'zeladoria', 'gestao', 'implantacao'] as const).find((item) => item === parts[2]) ?? '';
  if (area === 'morador') return { world: 'condo', tenantId, area, ...(parts[3] ? { unitId: parts[3] } : {}), section: parts[4] ?? 'inicio' };
  const defaults: Record<string, string> = { portaria: 'encomendas', zeladoria: 'pedidos', gestao: 'pendencias', implantacao: 'roteiro', inicio: '' };
  return { world: 'condo', tenantId, area, section: parts[3] ?? defaults[area] ?? '' };
}
