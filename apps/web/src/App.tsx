import { useEffect, useRef, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from '@tanstack/react-query';
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AuthBoundary, AuthProvider, LoginPage, useAuth } from './ui/Auth';
import { OperationsProvider, useOperations } from './ui/Operations';
import { ContextPage, ReservationsPage } from './ui/Residence';
import { DashboardPage } from './ui/Dashboard';
import { StaleDataNotice } from './ui/StaleData';
import { InvitationPage } from './ui/Invitation';
import { RecoveryPage } from './ui/Recovery';
import { PermitLinkPage } from './ui/PermitLinkPage';
import { ManagementPage, managementSections } from './ui/management/ManagementPage';
import { PlatformPage, usePlatformNavigation } from './ui/platform/PlatformPage';
import { ConciergePage, conciergeSections } from './ui/concierge/ConciergePage';
import { ParcelsPage } from './ui/Parcels';
import { VisitsPage } from './ui/Visits';
import { UnitEvents } from './ui/UnitEvents';
import { OwnerRentalPage, rentalKey } from './ui/Rental';
import { UnitFinancePage, financeRoles } from './ui/Finance';
import { rental } from './api/rental';
import { Brand, Icon, Loading, OfflineNotice, type IconName } from './ui/primitives';
import './styles.css';

export default function App() {
  const [queries] = useState(() => new QueryClient({ defaultOptions: {
    queries: { retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: true },
    mutations: { retry: false },
  } }));
  return <QueryClientProvider client={queries}><BrowserRouter><AuthProvider>
    <a href="#main-content" className="skip-link">Ir para o conteúdo</a><OfflineNotice />
    <Routes><Route path="/entrar" element={<LoginPage />} /><Route path="/convite" element={<InvitationPage />} /><Route path="/convite-gestor" element={<InvitationPage key="manager" kind="manager" />} /><Route path="/recuperar-acesso" element={<RecoveryPage />} /><Route path="/visita" element={<PermitLinkPage />} /><Route element={<ProtectedShell />}>
      <Route path="/contextos" element={<ContextPage />} />
      <Route path="/morador/:tenantId/:unitId/inicio" element={<DashboardPage />} />
      <Route path="/morador/:tenantId/:unitId/reservas" element={<ReservationsPage />} />
      <Route path="/morador/:tenantId/:unitId/encomendas" element={<ParcelsPage />} />
      <Route path="/morador/:tenantId/:unitId/acessos" element={<VisitsPage />} />
      <Route path="/morador/:tenantId/:unitId/inquilinos" element={<OwnerRentalPage />} />
      <Route path="/morador/:tenantId/:unitId/financeiro" element={<UnitFinancePage />} />
      <Route path="/portaria/:tenantId" element={<Navigate to="encomendas" replace />} />
      <Route path="/portaria/:tenantId/encomendas" element={<ConciergePage section="encomendas" />} />
      <Route path="/portaria/:tenantId/visitas" element={<ConciergePage section="visitas" />} />
      <Route path="/portaria/:tenantId/ocorrencias" element={<ConciergePage section="ocorrencias" />} />
      <Route path="/gestao/:tenantId" element={<ManagementPage />} />
      <Route path="/gestao/:tenantId/:section" element={<ManagementPage />} />
      <Route path="/master" element={<PlatformPage />} />
      <Route path="/master/:section" element={<PlatformPage />} />
      <Route path="/master/condominios/:tenantId" element={<PlatformPage />} />
      <Route path="*" element={<Navigate to="/contextos" replace />} />
    </Route></Routes>
  </AuthProvider></BrowserRouter></QueryClientProvider>;
}

function ProtectedShell() {
  const { identity } = useAuth();
  return <AuthBoundary><OperationsProvider key={identity?.accountId}><Shell /></OperationsProvider></AuthBoundary>;
}

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
  const tenantId = location.pathname.split('/')[2];
  const unitId = location.pathname.split('/')[3];
  const managing = location.pathname.startsWith('/gestao/');
  const concierging = location.pathname.startsWith('/portaria/');
  const portaria = concierging ? auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.role === 'CONCIERGE') : undefined;
  const conciergeSection = concierging ? (location.pathname.split('/')[3] ?? 'encomendas') : '';
  const mastering = location.pathname === '/master' || location.pathname.startsWith('/master/');
  const masterNavigation = usePlatformNavigation(mastering ? auth.identity?.accountId : undefined, location.pathname.split('/')[2] ?? 'condominios');
  const masterSection = mastering ? masterNavigation.current : '';
  const managed = managing ? auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.role === 'MANAGER') : undefined;
  const section = managing ? (location.pathname.split('/')[3] ?? 'pendencias') : '';
  const tenant = auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.unitId === unitId);
  const contextSelected = location.pathname.startsWith('/morador/') && Boolean(tenant);
  const residence = `/morador/${tenantId}/${unitId}`;
  const residentSection = location.pathname.split('/')[4] ?? 'inicio';
  const atDashboard = residentSection === 'inicio';
  // Encomendas e acessos só para vínculo residencial (proprietário não residente não os vê; dependente depende de
  // permissão no servidor).
  const residential = Boolean(auth.identity?.memberships.some(membership => membership.tenantId === tenantId && membership.unitId === unitId
    && ['RESIDENT', 'RESPONSIBLE', 'DEPENDENT'].includes(membership.role)));
  // Proprietário (OWNER) sem vínculo de ocupante: área de inquilinos; com a unidade alugada, é a única área (V034).
  const owner = !residential && Boolean(auth.identity?.memberships.some(membership => membership.tenantId === tenantId
    && membership.unitId === unitId && membership.role === 'OWNER'));
  const ownerRental = useQuery({ queryKey: rentalKey(auth.identity?.accountId ?? '', tenantId ?? '', unitId ?? ''),
    queryFn: ({ signal }) => rental.view(tenantId!, unitId!, signal), enabled: contextSelected && owner });
  const rented = owner && ownerRental.data?.rented === true;
  const ownerItem = { id: 'inquilinos', label: 'Inquilinos', icon: 'users' as const };
  // Financeiro (ADR-0008): morador, responsável ou proprietário; dependente não vê. Unidade alugada: o proprietário não vê.
  // Fica no menu principal; a barra de abas da unidade (até quatro, sem rolagem) só o mostra quando aberto.
  const financeMember = Boolean(auth.identity?.memberships.some(membership => membership.tenantId === tenantId && membership.unitId === unitId
    && (financeRoles as readonly string[]).includes(membership.role)));
  const residentItems = rented ? [ownerItem] : [{ id: 'inicio', label: 'Início', icon: 'home' as const },
    ...(residential ? [{ id: 'acessos', label: 'Acessos', icon: 'door' as const }] : []),
    { id: 'reservas', label: 'Reservas', icon: 'calendar' as const },
    ...(residential ? [{ id: 'encomendas', label: 'Encomendas', icon: 'box' as const }] : []),
    ...(financeMember ? [{ id: 'financeiro', label: 'Financeiro', icon: 'card' as const }] : []),
    ...(owner ? [ownerItem] : [])];
  async function changeContext() {
    if (switching || operations.locked) return;
    setSwitching(true);
    await queries.cancelQueries({ queryKey: ['private'] }); queries.removeQueries({ queryKey: ['private'] });
    navigate('/contextos'); setSwitching(false);
  }
  const navItems: { id: string; label: string; icon: IconName; to: string; current: boolean }[] = mastering
    ? masterNavigation.items.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `/master/${item.id}`, current: item.id === masterSection }))
    : managed ? managementSections.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `/gestao/${tenantId}/${item.id}`, current: item.id === section }))
    : portaria ? conciergeSections.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `/portaria/${tenantId}/${item.id}`, current: item.id === conciergeSection }))
    : contextSelected ? residentItems.map((item) => ({ id: item.id, label: item.label, icon: item.icon, to: `${residence}/${item.id}`, current: item.id === residentSection }))
    : [{ id: 'contextos', label: 'Seu contexto', icon: 'home', to: '/contextos', current: true }];
  return <div className={`app-frame${menuOpen ? ' menu-open' : ''}`}>
    <aside className="sidebar" id="app-sidebar"><div className="sidebar-top"><Link className="brand-link" to="/contextos" aria-label="CondPulse, selecionar unidade"><Brand light /></Link>
      <button ref={menuClose} type="button" className="menu-close" onClick={closeMenu} aria-label="Fechar menu"><Icon name="close" /></button></div>
      <div className="sidebar-label">{managing ? 'GESTÃO DO CONDOMÍNIO' : mastering ? 'ADMINISTRAÇÃO DA PLATAFORMA' : concierging ? 'PORTARIA' : 'PORTAL DO MORADOR'}</div>
      <nav className="sidebar-nav" aria-label="Navegação principal">{navItems.map((item) => <Link key={item.id} className={`nav-item${item.current ? ' active' : ''}`} aria-current={item.current ? 'page' : undefined} to={item.to}><Icon name={item.icon} /><span>{item.label}</span>{item.current ? <span className="nav-dot" /> : null}</Link>)}</nav>
      <div className="sidebar-bottom"><div className="sidebar-line" /><span className="sidebar-caption">Um condomínio.<br />Muitas boas conexões.</span><p>CondPulse · Vida em comunidade</p></div></aside>
    <div className="sidebar-backdrop" aria-hidden="true" onClick={closeMenu} />
    <div className="app-body" inert={menuOpen || undefined}><header className="app-header"><div className="header-start"><button ref={menuToggle} type="button" className="menu-toggle" aria-label="Abrir menu" aria-expanded={menuOpen} aria-controls="app-sidebar" onClick={() => setMenuOpen(true)}><Icon name="menu" /></button><div className="header-mobile-brand"><Brand /></div><span className="header-breadcrumb">{managing ? 'Gestão do condomínio' : mastering ? 'Administração da plataforma' : concierging ? 'Portaria' : 'Portal do morador'}<span aria-hidden="true"> / </span><strong>{mastering ? 'Painel master' : (managing ? managed?.tenantName : concierging ? portaria?.tenantName : tenant?.tenantName) ?? 'Seu acesso'}</strong></span></div><div className="header-actions">{contextSelected || managing || mastering || concierging ? <button className="button button-secondary button-compact" onClick={() => void changeContext()} disabled={switching || operations.locked}><Icon name="home" /><span>{switching ? 'Trocando…' : managing || mastering || concierging ? 'Trocar acesso' : 'Trocar unidade'}</span></button> : null}<button className="logout-button" disabled={operations.locked || switching} onClick={() => void auth.logout()}><Icon name="exit" /><span>Sair</span></button></div></header>{contextSelected ? <nav className="resident-tabs" aria-label="Navegação da unidade">{residentItems.filter((item) => item.id !== 'financeiro' || residentSection === 'financeiro').map((item) => <Link key={item.id} to={`${residence}/${item.id}`} aria-current={item.id === residentSection ? 'page' : undefined}><Icon name={item.icon} />{item.label}</Link>)}</nav> : null}<main id="main-content" className="app-main">{contextSelected && residential && auth.identity && tenantId && unitId ? <UnitEvents accountId={auth.identity.accountId} tenantId={tenantId} unitId={unitId} /> : null}<StaleDataNotice />{contextSelected && owner && ownerRental.isPending ? <Loading label="Conferindo a locação da unidade…" /> : rented && residentSection !== 'inquilinos' ? <Navigate to={`${residence}/inquilinos`} replace /> : <Outlet />}</main><footer className="app-footer"><span>CondPulse</span><span>{managing ? 'Gestão do condomínio · Ações exigem segundo fator' : mastering ? 'Administração da plataforma · Ações exigem segundo fator' : concierging ? 'Portaria · Ações exigem segundo fator' : 'Portal do morador · Seu acesso é pessoal'}</span></footer></div></div>;
}
