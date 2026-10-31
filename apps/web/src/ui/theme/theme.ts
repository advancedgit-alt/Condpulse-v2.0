/**
 * Tema claro/escuro (ADR-0010). Padrão: segue o sistema (prefers-color-scheme). O usuário pode fixar claro ou
 * escuro; a escolha fica só neste navegador (localStorage), sem dado pessoal nem envio ao servidor.
 * O tema resolvido vai para data-cp-theme no elemento raiz; tokens.css define as duas matrizes.
 */
export type ThemePreference = 'system' | 'light' | 'dark';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'condpulse.theme';
export const DARK_QUERY = '(prefers-color-scheme: dark)';
const PREFERENCES: readonly ThemePreference[] = ['system', 'light', 'dark'];

interface StorageLike { getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }

export function isThemePreference(value: unknown): value is ThemePreference {
  return typeof value === 'string' && (PREFERENCES as readonly string[]).includes(value);
}

/** Lê a preferência salva; armazenamento bloqueado ou valor estranho valem "sistema". */
export function readThemePreference(storage: StorageLike | undefined): ThemePreference {
  try {
    const value = storage?.getItem(THEME_STORAGE_KEY);
    return isThemePreference(value) ? value : 'system';
  } catch { return 'system'; }
}

export function saveThemePreference(storage: StorageLike | undefined, preference: ThemePreference): void {
  try {
    if (preference === 'system') storage?.removeItem(THEME_STORAGE_KEY);
    else storage?.setItem(THEME_STORAGE_KEY, preference);
  } catch { /* Sem armazenamento: a escolha vale só até recarregar a página. */ }
}

export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  return preference === 'system' ? (systemDark ? 'dark' : 'light') : preference;
}

/** Próxima opção do botão de tema: sistema → claro → escuro → sistema. */
export function nextThemePreference(preference: ThemePreference): ThemePreference {
  return preference === 'system' ? 'light' : preference === 'light' ? 'dark' : 'system';
}

export const THEME_LABELS: Record<ThemePreference, string> = { system: 'Sistema', light: 'Claro', dark: 'Escuro' };

function safeStorage(): StorageLike | undefined {
  try { return window.localStorage; } catch { return undefined; }
}

export function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches;
}

export function applyTheme(theme: ResolvedTheme): void {
  document.documentElement.dataset.cpTheme = theme;
}

/** Chamado antes do primeiro render para não pintar o tema errado. */
export function initTheme(): ThemePreference {
  const preference = readThemePreference(safeStorage());
  applyTheme(resolveTheme(preference, systemPrefersDark()));
  return preference;
}

export { safeStorage };
