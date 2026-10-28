import { useState } from 'react';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { BrowserRouter, Link, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { AuthBoundary, AuthProvider, LoginPage, useAuth } from './ui/Auth';
import { OperationsProvider, useOperations } from './ui/Operations';
import { ContextPage, ReservationsPage } from './ui/Residence';
import { DashboardPage } from './ui/Dashboard';
import { Brand, Icon, OfflineNotice } from './ui/primitives';
import './styles.css';

export default function App() {
  const [queries] = useState(() => new QueryClient({ defaultOptions: {
    queries: { retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: true },
    mutations: { retry: false },
  } }));
  return <QueryClientProvider client={queries}><BrowserRouter><AuthProvider>
    <a href="#main-content" className="skip-link">Ir para o conteúdo</a><OfflineNotice />
    <Routes><Route path="/entrar" element={<LoginPage />} /><Route element={<ProtectedShell />}>
      <Route path="/contextos" element={<ContextPage />} />
      <Route path="/morador/:tenantId/:unitId/inicio" element={<DashboardPage />} />
      <Route path="/morador/:tenantId/:unitId/reservas" element={<ReservationsPage />} />
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
  const tenant = auth.identity?.memberships.find(membership => membership.tenantId === tenantId && membership.unitId === unitId);
  const contextSelected = location.pathname.startsWith('/morador/') && Boolean(tenant);
  const residence = `/morador/${tenantId}/${unitId}`;
  const atDashboard = location.pathname.endsWith('/inicio');
  async function changeContext() {
    if (switching || operations.locked) return;
    setSwitching(true);
    await queries.cancelQueries({ queryKey: ['private'] }); queries.removeQueries({ queryKey: ['private'] });
    navigate('/contextos'); setSwitching(false);
  }
  return <div className="app-frame"><aside className="sidebar"><Link className="brand-link" to="/contextos" aria-label="CondPulse, selecionar unidade"><Brand light /></Link><div className="sidebar-label">PORTAL DO MORADOR</div><nav aria-label="Navegação principal">{contextSelected ? <><Link className={`nav-item${atDashboard ? ' active' : ''}`} aria-current={atDashboard ? 'page' : undefined} to={`${residence}/inicio`}><Icon name="home" /><span>Início</span>{atDashboard ? <span className="nav-dot" /> : null}</Link><Link className={`nav-item${!atDashboard ? ' active' : ''}`} aria-current={!atDashboard ? 'page' : undefined} to={`${residence}/reservas`}><Icon name="calendar" /><span>Reservas</span>{!atDashboard ? <span className="nav-dot" /> : null}</Link></> : <Link className="nav-item active" aria-current="page" to="/contextos"><Icon name="home" /><span>Seu contexto</span><span className="nav-dot" /></Link>}</nav><div className="sidebar-bottom"><div className="sidebar-line" /><span className="sidebar-caption">Um condomínio.<br />Muitas boas conexões.</span><p>CondPulse · Vida em comunidade</p></div></aside><div className="app-body"><header className="app-header"><div><div className="header-mobile-brand"><Brand /></div><span className="header-breadcrumb">Portal do morador<span aria-hidden="true"> / </span><strong>{tenant?.tenantName ?? 'Seu acesso'}</strong></span></div><div className="header-actions">{contextSelected ? <button className="button button-secondary button-compact" onClick={() => void changeContext()} disabled={switching || operations.locked}><Icon name="home" /><span>{switching ? 'Trocando…' : 'Trocar unidade'}</span></button> : null}<button className="logout-button" disabled={operations.locked || switching} onClick={() => void auth.logout()}><Icon name="exit" /><span>Sair</span></button></div></header>{contextSelected ? <nav className="resident-tabs" aria-label="Navegação da unidade"><Link to={`${residence}/inicio`} aria-current={atDashboard ? 'page' : undefined}><Icon name="home" />Início</Link><Link to={`${residence}/reservas`} aria-current={!atDashboard ? 'page' : undefined}><Icon name="calendar" />Reservas</Link></nav> : null}<main id="main-content" className="app-main"><Outlet /></main><footer className="app-footer"><span>CondPulse</span><span>Portal do morador · Seu acesso é pessoal</span></footer></div></div>;
}
