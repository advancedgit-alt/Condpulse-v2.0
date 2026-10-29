import { useState } from 'react';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AuthBoundary, AuthProvider, LoginPage, useAuth } from './ui/Auth';
import { OperationsProvider, useOperations } from './ui/Operations';
import { ContextPage, ReservationsPage } from './ui/Residence';
import { DashboardPage } from './ui/Dashboard';
import { InvitationPage } from './ui/Invitation';
import { RecoveryPage } from './ui/Recovery';
import { ManagementPage, managementSections } from './ui/management/ManagementPage';
import { PlatformPage } from './ui/platform/PlatformPage';
import { ConciergePage } from './ui/concierge/ConciergePage';
import { ParcelsPage } from './ui/Parcels';
import { Brand, Icon, OfflineNotice } from './ui/primitives';
import './styles.css';

export default function App() {
  const [queries] = useState(() => new QueryClient({ defaultOptions: {
    queries: { retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: true },
    mutations: { retry: false },
  } }));
  return <QueryClientProvider client={queries}><BrowserRouter><AuthProvider>
    <a href="#main-content" className="skip-link">Ir para o conteúdo</a><OfflineNotice />
    <Routes><Route path="/entrar" element={<LoginPage />} /><Route path="/convite" element={<InvitationPage />} /><Route path="/recuperar-acesso" element={<RecoveryPage />} /><Route element={<ProtectedShell />}>
      <Route path="/contextos" element={<ContextPage />} />
      <Route path="/morador/:tenantId/:unitId/inicio" element={<DashboardPage />} />
      <Route path="/morador/:tenantId/:unitId/reservas" element={<ReservationsPage />} />
      <Route path="/morador/:tenantId/:unitId/encomendas" element={<ParcelsPage />} />
      <Route path="/portaria/:tenantId" element={<Navigate to="encomendas" replace />} />
      <Route path="/portaria/:tenantId/encomendas" element={<ConciergePage />} />
      <Route path="/gestao/:tenantId" element={<ManagementPage />} />
      <Route path="/gestao/:tenantId/:section" element={<ManagementPage />} />
      <Route path="/master" element={<PlatformPage />} />
      <Route path="/master/:section" element={<PlatformPage />} />
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
  const tenantId = location.pathname.split('/')[2];
  const unitId = location.pathname.split('/')[3];
  const managing = location.pathname.startsWith('/gestao/');
  const concierging = location.pathname.startsWith('/portaria/');
  const portaria = concierging ? auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.role === 'CONCIERGE') : undefined;
  const mastering = location.pathname === '/master' || location.pathname.startsWith('/master/');
  const masterSection = mastering ? (location.pathname.split('/')[2] ?? 'condominios') : '';
  const managed = managing ? auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.role === 'MANAGER') : undefined;
  const section = managing ? (location.pathname.split('/')[3] ?? 'pendencias') : '';
  const tenant = auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.unitId === unitId);
  const contextSelected = location.pathname.startsWith('/morador/') && Boolean(tenant);
  const residence = `/morador/${tenantId}/${unitId}`;
  const residentSection = location.pathname.split('/')[4] ?? 'inicio';
  const atDashboard = residentSection === 'inicio';
  const residentItems = [{ id: 'inicio', label: 'Início', icon: 'home' as const }, { id: 'reservas', label: 'Reservas', icon: 'calendar' as const },
    { id: 'encomendas', label: 'Encomendas', icon: 'box' as const }];
  async function changeContext() {
    if (switching || operations.locked) return;
    setSwitching(true);
    await queries.cancelQueries({ queryKey: ['private'] }); queries.removeQueries({ queryKey: ['private'] });
    navigate('/contextos'); setSwitching(false);
  }
  return <div className="app-frame"><aside className="sidebar"><Link className="brand-link" to="/contextos" aria-label="CondPulse, selecionar unidade"><Brand light /></Link><div className="sidebar-label">{managing ? 'GESTÃO DO CONDOMÍNIO' : mastering ? 'ADMINISTRAÇÃO DA PLATAFORMA' : concierging ? 'PORTARIA' : 'PORTAL DO MORADOR'}</div><nav aria-label="Navegação principal">{mastering ? [{ id: 'condominios', label: 'Condomínios' }, { id: 'auditoria', label: 'Auditoria' }].map((item) => <Link key={item.id} className={`nav-item${item.id === masterSection ? ' active' : ''}`} aria-current={item.id === masterSection ? 'page' : undefined} to={`/master/${item.id}`}><Icon name={item.id === 'auditoria' ? 'clock' : 'shield'} /><span>{item.label}</span>{item.id === masterSection ? <span className="nav-dot" /> : null}</Link>) : managed ? managementSections.map((item) => <Link key={item.id} className={`nav-item${item.id === section ? ' active' : ''}`} aria-current={item.id === section ? 'page' : undefined} to={`/gestao/${tenantId}/${item.id}`}><Icon name={item.id === 'reservas' ? 'calendar' : item.id === 'auditoria' ? 'clock' : item.id === 'pendencias' ? 'check' : 'shield'} /><span>{item.label}</span>{item.id === section ? <span className="nav-dot" /> : null}</Link>) : portaria ? <Link className="nav-item active" aria-current="page" to={`/portaria/${tenantId}/encomendas`}><Icon name="box" /><span>Encomendas</span><span className="nav-dot" /></Link> : contextSelected ? residentItems.map((item) => <Link key={item.id} className={`nav-item${item.id === residentSection ? ' active' : ''}`} aria-current={item.id === residentSection ? 'page' : undefined} to={`${residence}/${item.id}`}><Icon name={item.icon} /><span>{item.label}</span>{item.id === residentSection ? <span className="nav-dot" /> : null}</Link>) : <Link className="nav-item active" aria-current="page" to="/contextos"><Icon name="home" /><span>Seu contexto</span><span className="nav-dot" /></Link>}</nav><div className="sidebar-bottom"><div className="sidebar-line" /><span className="sidebar-caption">Um condomínio.<br />Muitas boas conexões.</span><p>CondPulse · Vida em comunidade</p></div></aside><div className="app-body"><header className="app-header"><div><div className="header-mobile-brand"><Brand /></div><span className="header-breadcrumb">{managing ? 'Gestão do condomínio' : mastering ? 'Administração da plataforma' : concierging ? 'Portaria' : 'Portal do morador'}<span aria-hidden="true"> / </span><strong>{mastering ? 'Painel master' : (managing ? managed?.tenantName : concierging ? portaria?.tenantName : tenant?.tenantName) ?? 'Seu acesso'}</strong></span></div><div className="header-actions">{contextSelected || managing || mastering || concierging ? <button className="button button-secondary button-compact" onClick={() => void changeContext()} disabled={switching || operations.locked}><Icon name="home" /><span>{switching ? 'Trocando…' : managing || mastering || concierging ? 'Trocar acesso' : 'Trocar unidade'}</span></button> : null}<button className="logout-button" disabled={operations.locked || switching} onClick={() => void auth.logout()}><Icon name="exit" /><span>Sair</span></button></div></header>{contextSelected ? <nav className="resident-tabs" aria-label="Navegação da unidade">{residentItems.map((item) => <Link key={item.id} to={`${residence}/${item.id}`} aria-current={item.id === residentSection ? 'page' : undefined}><Icon name={item.icon} />{item.label}</Link>)}</nav> : null}<main id="main-content" className="app-main"><Outlet /></main><footer className="app-footer"><span>CondPulse</span><span>{managing ? 'Gestão do condomínio · Ações exigem segundo fator' : mastering ? 'Administração da plataforma · Ações exigem segundo fator' : concierging ? 'Portaria · Ações exigem segundo fator' : 'Portal do morador · Seu acesso é pessoal'}</span></footer></div></div>;
}
