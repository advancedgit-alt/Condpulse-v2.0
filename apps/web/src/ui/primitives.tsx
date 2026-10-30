import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useCooldown } from './cooldown';

export function Brand({ light = false }: { light?: boolean }) {
  return <span className={`brand${light ? ' brand-light' : ''}`} aria-label="CondPulse">
    <span className="brand-mark" aria-hidden="true"><span /><span /><span /></span>
    <span>Cond<span className="brand-accent">Pulse</span></span>
  </span>;
}

export type IconName = 'home' | 'calendar' | 'arrow' | 'shield' | 'check' | 'exit' | 'clock' | 'box' | 'door' | 'bell' | 'grid' | 'building'
  | 'upload' | 'users' | 'chat' | 'truck' | 'key' | 'alert' | 'list' | 'card' | 'menu' | 'close';

export function Icon({ name }: { name: IconName }) {
  const paths = {
    home: <><path d="m3 10 9-7 9 7v10H3z" /><path d="M9 20v-7h6v7" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M7 3v4m10-4v4M3 11h18m-13 5h3m3 0h2" /></>,
    arrow: <><path d="M4 12h16m-6-6 6 6-6 6" /></>,
    shield: <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z" /><path d="m8 12 3 3 5-6" /></>,
    check: <path d="m5 12 4 4L19 6" />,
    exit: <><path d="M9 4H4v16h5m5-4 4-4-4-4m-6 4h12" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    box: <><path d="m3 7 9-4 9 4v10l-9 4-9-4z" /><path d="m3 7 9 4 9-4M12 11v10" /></>,
    door: <><path d="M6 21V4.5A1.5 1.5 0 0 1 7.5 3h9A1.5 1.5 0 0 1 18 4.5V21" /><path d="M3 21h18M14.5 12.5v.01" /></>,
    bell: <><path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" /><path d="M10 20a2 2 0 0 0 4 0" /></>,
    grid: <><rect x="3.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="3.5" y="13.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="13.5" width="7" height="7" rx="1.5" /></>,
    building: <><path d="M5 21V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v16" /><path d="M16 9h2a1 1 0 0 1 1 1v11M3 21h18M9 7h3m-3 4h3m-3 4h3" /></>,
    upload: <><path d="M12 15V4M7.5 8.5 12 4l4.5 4.5" /><path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></>,
    users: <><circle cx="9" cy="8" r="3.5" /><path d="M2.5 20a6.5 6.5 0 0 1 13 0M15.5 4.7a3.5 3.5 0 0 1 0 6.6M18 14.4a6.5 6.5 0 0 1 3.5 5.6" /></>,
    chat: <><path d="M4 5h16v11H9.5L5 20v-4H4z" /><path d="M8 9h8m-8 3.5h5" /></>,
    truck: <><path d="M2.5 6h11v10h-11zM13.5 9.5h4l3 3.5v3h-7" /><circle cx="6.5" cy="17.5" r="2" /><circle cx="17" cy="17.5" r="2" /></>,
    key: <><circle cx="8" cy="15" r="4" /><path d="m11 12 9-9m-4 4 2.5 2.5M13.5 9.5 15.5 11.5" /></>,
    alert: <><path d="M10.3 4.2 2.6 18a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 4.2a2 2 0 0 0-3.4 0z" /><path d="M12 9.5v4m0 3.5v.01" /></>,
    list: <path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01" />,
    card: <><rect x="3" y="5.5" width="18" height="13" rx="2.5" /><path d="M3 10h18M7 15h3" /></>,
    menu: <path d="M4 7h16M4 12h16M4 17h16" />,
    close: <path d="m6 6 12 12M18 6 6 18" />,
  };
  return <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name]}</svg>;
}

export function Notice({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'error' | 'success' | 'warning' }) {
  return <div className={`notice notice-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>{children}</div>;
}

export function Loading({ label = 'Carregando seus dados…' }: { label?: string }) {
  return <div className="loading" role="status"><span className="spinner" aria-hidden="true" /><span>{label}</span></div>;
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return 'Não foi possível concluir a operação. Tente novamente.';
}

export function ErrorState({ error, retry }: { error: unknown; retry?: () => void }) {
  const remaining = useCooldown(error);
  return <div className="error-state">{error instanceof ApiError && error.status === 429
    ? <RateLimitNotice error={error} remaining={remaining} /> : <Notice tone="error">{errorMessage(error)}</Notice>}
    {retry && <button className="button button-secondary" type="button" disabled={remaining > 0} onClick={() => { if (remaining === 0) retry(); }}>Tentar novamente</button>}
  </div>;
}

export function RateLimitNotice({ error, remaining }: { error: ApiError; remaining: number }) {
  return <Notice tone="warning"><p className="rate-limit-copy"><Icon name="clock" />{errorMessage(error)}</p>
    <p className="rate-limit-copy" aria-hidden="true">{remaining > 0 ? `Nova tentativa em ${remaining} s.` : 'Você já pode tentar novamente.'}</p>
    <span className="sr-only">{remaining > 0
      ? `Aguarde até ${error.retryAfterSeconds ?? 60} segundos antes de tentar novamente. Nenhuma tentativa será enviada automaticamente.`
      : 'A espera terminou. Você pode tentar novamente.'}</span>
  </Notice>;
}

export function Empty({ title, children, icon = 'calendar' }: { title: string; children: ReactNode; icon?: IconName }) {
  return <div className="empty-state"><span className="empty-icon"><Icon name={icon} /></span><h3>{title}</h3><p>{children}</p></div>;
}

export function OfflineNotice() {
  const [offline, setOffline] = useState(() => !navigator.onLine);
  useEffect(() => {
    const update = () => setOffline(!navigator.onLine);
    window.addEventListener('online', update); window.addEventListener('offline', update);
    return () => { window.removeEventListener('online', update); window.removeEventListener('offline', update); };
  }, []);
  return offline ? <div className="offline-bar" role="status">Você está sem conexão. Reservas só são confirmadas pelo servidor.</div> : null;
}

export interface SectionTab { id: string; label: string; to: string }

/**
 * Abas de seção compactas: uma linha só, com rolagem horizontal quando não cabem. A aba atual é trazida para a área
 * visível sem rolar a página verticalmente.
 */
export function SectionTabs({ label, items, current }: { label: string; items: readonly SectionTab[]; current: string }) {
  const nav = useRef<HTMLElement>(null);
  useEffect(() => {
    const container = nav.current;
    const active = container?.querySelector<HTMLElement>('[aria-current="page"]');
    if (!container || !active) return;
    const box = container.getBoundingClientRect();
    const tab = active.getBoundingClientRect();
    if (tab.left < box.left || tab.right > box.right) container.scrollLeft += tab.left - box.left - (box.width - tab.width) / 2;
  }, [current, items.length]);
  return <nav ref={nav} className="management-tabs" aria-label={label}>{items.map((item) =>
    <Link key={item.id} to={item.to} aria-current={item.id === current ? 'page' : undefined}>{item.label}</Link>)}</nav>;
}
