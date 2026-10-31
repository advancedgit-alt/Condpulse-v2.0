import { useEffect, useRef, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from '@tanstack/react-query';
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate, type Location } from 'react-router-dom';
import { AuthBoundary, AuthProvider, LoginPage, useAuth } from './ui/Auth';
import { OperationsProvider, useOperations } from './ui/Operations';
import { ReservationsPage } from './ui/Residence';
import { DashboardPage } from './ui/Dashboard';
import { StaleDataNotice } from './ui/StaleData';
import { InvitationPage } from './ui/Invitation';
import { RecoveryPage } from './ui/Recovery';
import { PermitLinkPage } from './ui/PermitLinkPage';
import { ManagementPage, managementSections } from './ui/management/ManagementPage';
import { PlatformPage, upcomingCentralSections, usePlatformNavigation } from './ui/platform/PlatformPage';
import { ConciergePage, conciergeSections } from './ui/concierge/ConciergePage';
import { InstallationPage, useInstallationNavigation } from './ui/installation/InstallationPage';
import { ParcelsPage } from './ui/Parcels';
import { VisitsPage } from './ui/Visits';
import { UnitEvents } from './ui/UnitEvents';
import { OwnerRentalPage, rentalKey } from './ui/Rental';
import { UnitFinancePage, financeRoles } from './ui/Finance';
import { rental } from './api/rental';
import { EntryPage, usePlatformAccess } from './ui/shell/Entry';
import { CondoHome, CondoIndex, ResidentUnitPicker, areaPath, useCondo, useTechnicalAccess } from './ui/shell/Condo';
import { ENTRY_PATH, centralPath, condoAreas, condominiums, legacyTarget, placeOf, residentRoot, type AreaId } from './ui/shell/paths';
import { Brand, Icon, Loading, OfflineNotice, type IconName } from './ui/primitives';
import { BrandingProvider, ThemeProvider, ThemeToggle, useBrandingStyle } from './ui/theme/ThemeContext';
import type { TenantBrandingConfig } from './ui/theme/branding';
import './styles.css';
import './ui/shell/Shell.css';

export default function App() {
  const [queries] = useState(() => new QueryClient({ defaultOptions: {
    queries: { retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: true },
    mutations: { retry: false },
  } }));
  return <ThemeProvider><QueryClientProvider client={queries}><BrowserRouter><AuthProvider>
    <a href="#main-content" className="skip-link">Ir para o conteúdo</a><OfflineNotice />
    <Routes><Route path="/entrar" element={<LoginPage />} /><Route path="/convite" element={<InvitationPage />} /><Route path="/convite-gestor" element={<InvitationPage key="manager" kind="manager" />} /><Route path="/recuperar-acesso" element={<RecoveryPage />} /><Route path="/visita" element={<PermitLinkPage />} /><Route element={<ProtectedShell />}>
      <Route path="/" element={<Navigate to={ENTRY_PATH} replace />} />
      <Route path={ENTRY_PATH} element={<EntryPage />} />
      {/* App do condomínio: uma entrada, áreas por papel; o servidor confere o vínculo em cada chamada. */}
      <Route path="/c/:tenantId" element={<CondoIndex />} />
      <Route path="/c/:tenantId/inicio" element={<CondoHome />} />
      <Route path="/c/:tenantId/morador" element={<ResidentUnitPicker />} />
      <Route path="/c/:tenantId/morador/:unitId" element={<Navigate to="inicio" replace />} />
      <Route path="/c/:tenantId/morador/:unitId/inicio" element={<DashboardPage />} />
      <Route path="/c/:tenantId/morador/:unitId/reservas" element={<ReservationsPage />} />
      <Route path="/c/:tenantId/morador/:unitId/encomendas" element={<ParcelsPage />} />
      <Route path="/c/:tenantId/morador/:unitId/acessos" element={<VisitsPage />} />
      <Route path="/c/:tenantId/morador/:unitId/inquilinos" element={<OwnerRentalPage />} />
      <Route path="/c/:tenantId/morador/:unitId/financeiro" element={<UnitFinancePage />} />
      <Route path="/c/:tenantId/portaria" element={<Navigate to="encomendas" replace />} />
      <Route path="/c/:tenantId/portaria/encomendas" element={<ConciergePage section="encomendas" />} />
      <Route path="/c/:tenantId/portaria/visitas" element={<ConciergePage section="visitas" />} />
      <Route path="/c/:tenantId/portaria/ocorrencias" element={<ConciergePage section="ocorrencias" />} />
      <Route path="/c/:tenantId/gestao" element={<ManagementPage />} />
      <Route path="/c/:tenantId/gestao/:section" element={<ManagementPage />} />
      <Route path="/c/:tenantId/implantacao" element={<InstallationPage />} />
      <Route path="/c/:tenantId/implantacao/:section" element={<InstallationPage />} />
      <Route path="/c/:tenantId/*" element={<CondoIndex />} />
      {/* Central administradora da plataforma. */}
      <Route path="/central" element={<PlatformPage />} />
      <Route path="/central/:section" element={<PlatformPage />} />
      <Route path="/central/condominios/:tenantId" element={<PlatformPage />} />
      {/* Rotas antigas (/contextos, /morador, /portaria, /gestao, /implantacao, /master) e desconhecidas. */}
      <Route path="*" element={<LegacyRedirect />} />
    </Route></Routes>
  </AuthProvider></BrowserRouter></QueryClientProvider></ThemeProvider>;
}

/** Rota antiga vai para o endereço novo com a mesma busca e o mesmo fragmento; o resto vai para a entrada. */
function LegacyRedirect() {
  const location: Location = useLocation();
  return <Navigate to={legacyTarget(location.pathname, location.search, location.hash) ?? ENTRY_PATH} replace />;
}

function ProtectedShell() {
  const { identity } = useAuth();
  return <AuthBoundary><OperationsProvider key={identity?.accountId}><Shell /></OperationsProvider></AuthBoundary>;
}

interface NavItem { id: string; label: string; icon: IconName; to: string | null; current: boolean }
const areaIcons: Record<AreaId, IconName> = { inicio: 'grid', morador: 'home', portaria: 'door', zeladoria: 'truck', gestao: 'building', implantacao: 'key' };
const areaTitles: Record<AreaId | '', string> = { '': 'Condomínio', inicio: 'Condomínio', morador: 'Portal do morador', portaria: 'Portaria',
  zeladoria: 'Zeladoria', gestao: 'Gestão do condomínio', implantacao: 'Implantação técnica' };
const areaFooters: Record<AreaId | '', string> = { '': 'Condomínio · Cada área segue o seu papel', inicio: 'Condomínio · Cada área segue o seu papel',
  morador: 'Portal do morador · Seu acesso é pessoal', portaria: 'Portaria · Ações exigem segundo fator', zeladoria: 'Zeladoria',
  gestao: 'Gestão do condomínio · Ações exigem segundo fator', implantacao: 'Implantação técnica · Ações exigem segundo fator e concessão vigente' };

function Shell() {
  const auth = useAuth();
  const operations = useOperations();
  const navigate = useNavigate();
  const location = useLocation();
  const queries = useQueryClient();
  const [switching, setSwitching] = useState(false);
  // Abaixo de 1024 px a navegação lateral vira gaveta: fecha ao navegar, com Esc ou tocando fora.
  const [menuOpen, setMenuOpen] = useState(false);
  const menuToggle = useRef<HTMLButtonElement>(null);
  const menuClose = useRef<HTMLButtonElement>(null);
  const restoreFocus = useRef(false);
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);
  useEffect(() => {
    if (!menuOpen) {
      // Depois que o conteúdo deixa de ser inerte, o foco volta ao botão que abriu o menu.
      if (restoreFocus.current) { restoreFocus.current = false; menuToggle.current?.focus(); }
      return;
    }
    menuClose.current?.focus();
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') { restoreFocus.current = true; setMenuOpen(false); } };
    const narrow = window.matchMedia('(min-width: 1024px)');
    const onWide = () => { if (narrow.matches) setMenuOpen(false); };
    window.addEventListener('keydown', onKey); narrow.addEventListener('change', onWide);
    return () => { window.removeEventListener('keydown', onKey); narrow.removeEventListener('change', onWide); };
  }, [menuOpen]);
  function closeMenu() { restoreFocus.current = true; setMenuOpen(false); }
  const accountId = auth.identity?.accountId;
  const memberships = auth.identity?.memberships ?? [];
  const place = placeOf(location.pathname);
  const condos = condominiums(memberships);
  const platformAccess = usePlatformAccess(accountId, false);
  const condoWorld = place.world === 'condo';
  const tenantId = place.world === 'condo' ? place.tenantId : undefined;
  const area = place.world === 'condo' ? place.area : '';
  const section = place.world === 'entry' ? '' : place.section;
  const condo = useCondo(tenantId ?? '');
  const installing = area === 'implantacao';
  const technical = useTechnicalAccess(condoWorld ? accountId : undefined, tenantId, installing);
  const areas = condoWorld ? condoAreas(condo, technical) : [];
  const unitId = place.world === 'condo' && area === 'morador' ? place.unitId : undefined;
  const managing = area === 'gestao';
  const concierging = area === 'portaria';
  const portaria = concierging ? memberships.find(membership => membership.tenantId === tenantId && membership.role === 'CONCIERGE') : undefined;
  const mastering = place.world === 'central';
  const masterNavigation = usePlatformNavigation(mastering ? accountId : undefined, mastering ? section : 'condominios');
  const installNavigation = useInstallationNavigation(installing ? accountId : undefined, installing ? tenantId : undefined, section || 'roteiro');
  const managed = managing ? memberships.find(membership => membership.tenantId === tenantId && membership.role === 'MANAGER') : undefined;
  const tenant = unitId ? memberships.find(membership => membership.tenantId === tenantId && membership.unitId === unitId) : undefined;
  const contextSelected = area === 'morador' && Boolean(tenant);
  const residence = `/c/${tenantId}/morador/${unitId}`;
  const residentSection = section || 'inicio';
  // Encomendas e acessos só para vínculo residencial (proprietário não residente não os vê; dependente depende de
  // permissão no servidor).
  const residential = Boolean(memberships.some(membership => membership.tenantId === tenantId && membership.unitId === unitId
    && ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT'].includes(membership.role)));
  // Proprietário (OWNER) sem vínculo de ocupante: área de inquilinos; com a unidade alugada, é a única área (V034).
  const owner = !residential && Boolean(memberships.some(membership => membership.tenantId === tenantId
    && membership.unitId === unitId && membership.role === 'OWNER'));
  const ownerRental = useQuery({ queryKey: rentalKey(accountId ?? '', tenantId ?? '', unitId ?? ''),
    queryFn: ({ signal }) => rental.view(tenantId!, unitId!, signal), enabled: contextSelected && owner });
  const rented = owner && ownerRental.data?.rented === true;
  const ownerItem = { id: 'inquilinos', label: 'Inquilinos', icon: 'users' as const };
  // Financeiro (ADR-0008): morador, responsável ou proprietário; dependente não vê. Unidade alugada: o proprietário não vê.
  // Fica no menu principal; a barra de abas da unidade (até quatro, sem rolagem) só o mostra quando aberto.
  const financeMember = Boolean(memberships.some(membership => membership.tenantId === tenantId && membership.unitId === unitId
    && (financeRoles as readonly string[]).includes(membership.role)));
  const residentItems = rented ? [ownerItem] : [{ id: 'inicio', label: 'Início', icon: 'home' as const },
    ...(residential ? [{ id: 'acessos', label: 'Acessos', icon: 'door' as const }] : []),
    { id: 'reservas', label: 'Reservas', icon: 'calendar' as const },
    ...(residential ? [{ id: 'encomendas', label: 'Encomendas', icon: 'box' as const }] : []),
    ...(financeMember ? [{ id: 'financeiro', label: 'Financeiro', icon: 'card' as const }] : []),
    ...(owner ? [ownerItem] : [])];
  // Troca de unidade ou de condomínio: os dados privados do contexto anterior saem do cache (a decisão da entrada fica).
  async function changeContext(to: string) {
    if (switching || operations.locked) return;
    setSwitching(true);
    const predicate = (query: { queryKey: readonly unknown[] }) => query.queryKey[0] === 'private' && query.queryKey[2] !== 'entry';
    await queries.cancelQueries({ predicate }); queries.removeQueries({ predicate });
    navigate(to); setSwitching(false);
  }
  const sectionItems: NavItem[] = installing && tenantId
    ? installNavigation.items.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `/c/${tenantId}/implantacao/${item.id}`, current: item.id === installNavigation.current }))
    : mastering ? [...masterNavigation.items.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: centralPath(item.id), current: item.id === masterNavigation.current })),
      ...upcomingCentralSections.filter((item) => !masterNavigation.items.some((visible) => visible.id === item.id)).map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: null, current: false }))]
    : managed ? managementSections.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `/c/${tenantId}/gestao/${item.id}`, current: item.id === (section || 'pendencias') }))
    : portaria ? conciergeSections.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `/c/${tenantId}/portaria/${item.id}`, current: item.id === (section || 'encomendas') }))
    : contextSelected ? residentItems.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `${residence}/${item.id}`, current: item.id === residentSection }))
    : [];
  const areaItems: NavItem[] = tenantId ? areas.map((item) => ({ id: item.id, label: item.label, icon: areaIcons[item.id],
    to: item.disabled ? null : areaPath(tenantId, item.id), current: item.id === area })) : [];
  // No início do condomínio (ou sem seções na área) as áreas ocupam a navegação principal; nas demais, ficam no seletor de área.
  const areasAsMain = condoWorld && sectionItems.length === 0;
  const navItems: NavItem[] = condoWorld ? (areasAsMain ? areaItems : sectionItems)
    : mastering ? sectionItems : [{ id: 'entrada', label: 'Meus acessos', icon: 'home', to: ENTRY_PATH, current: true }];
  const showAreaSwitch = condoWorld && !areasAsMain && areaItems.length > 1;
  const multiUnit = (condo?.unitIds.length ?? 0) > 1;
  const switchTarget = condoWorld && contextSelected && multiUnit && tenantId ? { label: 'Trocar unidade', to: residentRoot(tenantId) }
    : condoWorld && (condos.length > 1 || platformAccess === 'yes' || !condo) ? { label: 'Trocar condomínio', to: ENTRY_PATH }
      : mastering && condos.length > 0 ? { label: 'Meus condomínios', to: ENTRY_PATH } : null;
  const sidebarLabel = mastering ? 'CENTRAL ADMINISTRADORA' : !condoWorld ? 'SEUS ACESSOS' : areasAsMain ? 'ÁREAS DO CONDOMÍNIO' : areaTitles[area].toUpperCase();
  const breadcrumb = mastering ? 'Administração da plataforma' : condoWorld ? areaTitles[area] : 'Seus acessos';
  const breadcrumbName = mastering ? 'Central administradora' : condoWorld ? (contextSelected ? tenant?.tenantName : condo?.tenantName ?? managed?.tenantName ?? portaria?.tenantName) ?? (installing ? 'Instalação física' : 'Condomínio') : 'Meus condomínios';
  const footer = mastering ? 'Central administradora · Ações exigem segundo fator' : condoWorld ? areaFooters[area] : 'CondPulse · Seu acesso é pessoal';
  // White label (ADR-0010, fase 1): ainda sem fonte no servidor; com configuração, os tokens de ação/acento e o logo
  // do condomínio valem só dentro do shell, com fallback por contraste para o padrão CondPulse.
  const brandingConfig: TenantBrandingConfig | undefined = undefined;
  const { branding, style: brandingStyle } = useBrandingStyle(brandingConfig);
  const renderItem = (item: NavItem, className: string) => item.to === null
    ? <span key={item.id} className={`${className} nav-item-disabled`} aria-disabled="true"><Icon name={item.icon} /><span>{item.label}</span><small className="nav-soon">em breve</small></span>
    : <Link key={item.id} className={`${className}${item.current ? ' active' : ''}`} aria-current={item.current ? 'page' : undefined} to={item.to}><Icon name={item.icon} /><span>{item.label}</span>{item.current && className === 'nav-item' ? <span className="nav-dot" /> : null}</Link>;
  return <BrandingProvider value={branding}><div className={`app-frame${menuOpen ? ' menu-open' : ''}`} style={brandingStyle}>
    <aside className="sidebar" id="app-sidebar"><div className="sidebar-top"><Link className="brand-link" to={ENTRY_PATH} aria-label="CondPulse, meus acessos"><Brand light /></Link>
      <button ref={menuClose} type="button" className="menu-close" onClick={closeMenu} aria-label="Fechar menu"><Icon name="close" /></button></div>
      {showAreaSwitch ? <><div className="sidebar-label sidebar-label-condo"><bdi>{condo?.tenantName ?? 'Condomínio'}</bdi></div>
        <nav className="area-switch" aria-label="Áreas do condomínio">{areaItems.map((item) => renderItem(item, 'area-chip'))}</nav></> : null}
      <div className="sidebar-label">{sidebarLabel}</div>
      <nav className="sidebar-nav" aria-label="Navegação principal">{navItems.filter((item) => item.to !== null || !mastering).map((item) => renderItem(item, 'nav-item'))}
        {mastering ? <><span className="nav-group-label">EM BREVE</span>{navItems.filter((item) => item.to === null).map((item) => renderItem(item, 'nav-item'))}</> : null}</nav>
      <div className="sidebar-bottom"><div className="sidebar-line" /><span className="sidebar-caption">Um condomínio.<br />Muitas boas conexões.</span><p>CondPulse · Vida em comunidade</p></div></aside>
    <div className="sidebar-backdrop" aria-hidden="true" onClick={closeMenu} />
    <div className="app-body" inert={menuOpen || undefined}><header className="app-header"><div className="header-start"><button ref={menuToggle} type="button" className="menu-toggle" aria-label="Abrir menu" aria-expanded={menuOpen} aria-controls="app-sidebar" onClick={() => setMenuOpen(true)}><Icon name="menu" /></button><div className="header-mobile-brand"><Brand compact /></div><span className="header-breadcrumb">{breadcrumb}<span aria-hidden="true"> / </span><strong><bdi>{breadcrumbName}</bdi></strong></span></div><div className="header-actions">{switchTarget ? <button className="button button-secondary button-compact" onClick={() => void changeContext(switchTarget.to)} disabled={switching || operations.locked}><Icon name="home" /><span>{switching ? 'Trocando…' : switchTarget.label}</span></button> : null}<ThemeToggle /><button className="logout-button" disabled={operations.locked || switching} onClick={() => void auth.logout()}><Icon name="exit" /><span>Sair</span></button></div></header>{contextSelected ? <nav className="resident-tabs" aria-label="Navegação da unidade">{residentItems.filter((item) => item.id !== 'financeiro' || residentSection === 'financeiro').map((item) => <Link key={item.id} to={`${residence}/${item.id}`} aria-current={item.id === residentSection ? 'page' : undefined}><Icon name={item.icon} />{item.label}</Link>)}</nav> : null}<main id="main-content" className="app-main">{contextSelected && residential && accountId && tenantId && unitId ? <UnitEvents accountId={accountId} tenantId={tenantId} unitId={unitId} /> : null}<StaleDataNotice />{contextSelected && owner && ownerRental.isPending ? <Loading label="Conferindo a locação da unidade…" /> : rented && residentSection !== 'inquilinos' ? <Navigate to={`${residence}/inquilinos`} replace /> : <Outlet />}</main><footer className="app-footer"><span>CondPulse</span><span>{footer}</span></footer></div></div></BrandingProvider>;
}
