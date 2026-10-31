import { createContext, useContext, useEffect, useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { resolveBranding, type ResolvedBranding, type TenantBrandingConfig } from './branding';
import {
  applyTheme, DARK_QUERY, nextThemePreference, readThemePreference, resolveTheme, safeStorage, saveThemePreference, systemPrefersDark,
  THEME_LABELS, type ResolvedTheme, type ThemePreference,
} from './theme';

interface ThemeState { preference: ThemePreference; theme: ResolvedTheme; setPreference(preference: ThemePreference): void }

const ThemeContext = createContext<ThemeState>({ preference: 'system', theme: 'light', setPreference: () => undefined });

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(() => readThemePreference(safeStorage()));
  const [systemDark, setSystemDark] = useState(systemPrefersDark);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia(DARK_QUERY);
    const update = () => setSystemDark(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  const theme = resolveTheme(preference, systemDark);
  useEffect(() => { applyTheme(theme); }, [theme]);
  const value = useMemo<ThemeState>(() => ({ preference, theme, setPreference: (next) => {
    saveThemePreference(safeStorage(), next); setPreferenceState(next);
  } }), [preference, theme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme(): ThemeState { return useContext(ThemeContext); }

/** Botão de tema (48 px): alterna Sistema → Claro → Escuro e anuncia a escolha atual. */
export function ThemeToggle() {
  const { preference, setPreference } = useTheme();
  const next = nextThemePreference(preference);
  return <button type="button" className="theme-toggle" onClick={() => setPreference(next)}
    aria-label={`Tema: ${THEME_LABELS[preference]}. Mudar para ${THEME_LABELS[next]}`} title={`Tema: ${THEME_LABELS[preference]}`}>
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {preference === 'dark' ? <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" />
        : preference === 'light' ? <><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>
        : <><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8m-4-4v4" /></>}
    </svg><span className="theme-toggle-label">{THEME_LABELS[preference]}</span>
  </button>;
}

const BrandingContext = createContext<ResolvedBranding | undefined>(undefined);

/**
 * Marca do condomínio ativo (white label, fase 1). Sem `config`, vale o padrão CondPulse. Os tokens válidos são
 * aplicados como variáveis CSS no contêiner do shell (CSSOM, compatível com a CSP sem estilos inline).
 */
export function useBrandingStyle(config: TenantBrandingConfig | undefined): { branding: ResolvedBranding; style: CSSProperties | undefined } {
  const { theme } = useTheme();
  const branding = useMemo(() => resolveBranding(config, theme), [config, theme]);
  const style = Object.keys(branding.tokens).length ? branding.tokens as CSSProperties : undefined;
  return { branding, style };
}

export function BrandingProvider({ value, children }: { value: ResolvedBranding; children: ReactNode }) {
  return <BrandingContext.Provider value={value}>{children}</BrandingContext.Provider>;
}

export function useBranding(): ResolvedBranding | undefined { return useContext(BrandingContext); }
