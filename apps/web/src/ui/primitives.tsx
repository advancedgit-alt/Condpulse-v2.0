import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from '../api/client';

export function Brand({ light = false }: { light?: boolean }) {
  return <span className={`brand${light ? ' brand-light' : ''}`} aria-label="CondPulse">
    <span className="brand-mark" aria-hidden="true"><span /><span /><span /></span>
    <span>Cond<span className="brand-accent">Pulse</span></span>
  </span>;
}

export function Icon({ name }: { name: 'home' | 'calendar' | 'arrow' | 'shield' | 'check' | 'exit' | 'clock' }) {
  const paths = {
    home: <><path d="m3 10 9-7 9 7v10H3z" /><path d="M9 20v-7h6v7" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="3" /><path d="M7 3v4m10-4v4M3 11h18m-13 5h3m3 0h2" /></>,
    arrow: <><path d="M4 12h16m-6-6 6 6-6 6" /></>,
    shield: <><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z" /><path d="m8 12 3 3 5-6" /></>,
    check: <path d="m5 12 4 4L19 6" />,
    exit: <><path d="M9 4H4v16h5m5-4 4-4-4-4m-6 4h12" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
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
  return <div className="error-state"><Notice tone="error">{errorMessage(error)}</Notice>
    {retry && <button className="button button-secondary" type="button" onClick={retry}>Tentar novamente</button>}
  </div>;
}

export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <div className="empty-state"><span className="empty-icon"><Icon name="calendar" /></span><h3>{title}</h3><p>{children}</p></div>;
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
