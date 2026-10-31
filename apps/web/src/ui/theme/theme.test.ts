/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { contrastRatio, MIN_NON_TEXT, MIN_TEXT } from './contrast';
import { nextThemePreference, readThemePreference, resolveTheme, saveThemePreference, THEME_STORAGE_KEY } from './theme';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');
const SHEETS: Record<string, string> = Object.fromEntries(['../../styles.css', '../Dashboard.css', '../Availability.css', '../management/Management.css']
  .map((sheet) => [sheet, read(sheet)]));

function tokens(css: string, selector: string): Record<string, string> {
  const start = css.indexOf(selector);
  const block = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start));
  return Object.fromEntries([...block.matchAll(/(--cp-[a-z0-9-]+):\s*(#[0-9A-Fa-f]{6})\b/g)].map((m) => [m[1]!, m[2]!]));
}

describe('preferência de tema', () => {
  it('segue o sistema por padrão e respeita a escolha manual', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });

  it('salva só claro/escuro, ignora valor estranho e sobrevive a armazenamento bloqueado', () => {
    const store = new Map<string, string>();
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => { store.set(k, v); }, removeItem: (k: string) => { store.delete(k); } };
    saveThemePreference(storage, 'dark');
    expect(store.get(THEME_STORAGE_KEY)).toBe('dark');
    expect(readThemePreference(storage)).toBe('dark');
    saveThemePreference(storage, 'system');
    expect(store.has(THEME_STORAGE_KEY)).toBe(false);
    store.set(THEME_STORAGE_KEY, 'url(javascript:1)');
    expect(readThemePreference(storage)).toBe('system');
    const blocked = { getItem: () => { throw new Error('bloqueado'); }, setItem: () => { throw new Error('bloqueado'); }, removeItem: () => { throw new Error('bloqueado'); } };
    expect(readThemePreference(blocked)).toBe('system');
    expect(() => saveThemePreference(blocked, 'light')).not.toThrow();
  });

  it('alterna sistema → claro → escuro → sistema', () => {
    expect(nextThemePreference('system')).toBe('light');
    expect(nextThemePreference('light')).toBe('dark');
    expect(nextThemePreference('dark')).toBe('system');
  });
});

describe('tokens de cor (tokens.css)', () => {
  const css = read('../../styles/tokens.css');
  const themes = { light: tokens(css, ':root, [data-cp-theme="light"]'), dark: tokens(css, '[data-cp-theme="dark"]') };

  it('usa a paleta do kit no tema claro', () => {
    expect(themes.light).toMatchObject({
      '--cp-color-text-primary': '#102A43', '--cp-color-action-primary-background': '#007F86', '--cp-color-surface-canvas': '#F4F7FA',
      '--cp-color-surface-panel': '#FFFFFF', '--cp-color-text-secondary': '#52667A', '--cp-color-text-link': '#00666D',
      '--cp-color-status-danger-foreground': '#B42318', '--cp-color-text-inverse-link': '#5DD6C7',
    });
  });

  it('define as mesmas chaves de cor nos dois temas', () => {
    expect(Object.keys(themes.dark).sort()).toEqual(Object.keys(themes.light).sort());
  });

  for (const [name, t] of Object.entries(themes)) {
    const v = (key: string) => t[`--cp-${key}`]!;
    const text: [string, string][] = [
      ['color-text-primary', 'color-surface-canvas'], ['color-text-primary', 'color-surface-panel'], ['color-text-primary', 'color-surface-muted'],
      ['color-text-secondary', 'color-surface-canvas'], ['color-text-secondary', 'color-surface-panel'], ['color-text-secondary', 'color-surface-muted'],
      ['color-text-secondary', 'color-surface-disabled'], ['color-text-link', 'color-surface-canvas'], ['color-text-link', 'color-surface-panel'],
      ['color-text-link', 'color-surface-accent-subtle'], ['color-text-inverse', 'color-surface-inverse'], ['color-text-inverse-secondary', 'color-surface-inverse'],
      ['color-text-inverse-link', 'color-surface-inverse'], ['color-action-primary-foreground', 'color-action-primary-background'],
      ['color-action-primary-foreground', 'color-action-primary-hover'], ['color-action-danger-foreground', 'color-action-danger-background'],
      ['color-action-danger-foreground', 'color-action-danger-hover'], ['color-status-danger-foreground', 'color-surface-panel'],
      ...(['success', 'warning', 'danger', 'info', 'neutral'] as const).map((s) => [`color-status-${s}-foreground`, `color-status-${s}-background`] as [string, string]),
    ];
    it(`texto comum ≥ 4,5:1 no tema ${name}`, () => {
      const failures = text.filter(([fg, bg]) => contrastRatio(v(fg), v(bg)) < MIN_TEXT).map(([fg, bg]) => `${fg}/${bg}`);
      expect(failures).toEqual([]);
    });
    const nonText: [string, string][] = [
      ['color-border-control', 'color-surface-panel'], ['color-border-control', 'color-surface-canvas'], ['color-icon-accent', 'color-surface-canvas'],
      ['color-icon-accent', 'color-surface-accent-subtle'], ['color-focus-ring', 'color-surface-canvas'], ['color-focus-ring', 'color-surface-panel'],
      ['color-focus-inverse', 'color-surface-inverse'],
    ];
    it(`componentes e ícones ≥ 3:1 no tema ${name}`, () => {
      const failures = nonText.filter(([fg, bg]) => contrastRatio(v(fg), v(bg)) < MIN_NON_TEXT).map(([fg, bg]) => `${fg}/${bg}`);
      expect(failures).toEqual([]);
    });
  }

  it('não arredonda: teal sobre o fundo claro falha como texto (motivo do link #00666D)', () => {
    expect(contrastRatio('#007F86', '#F4F7FA')).toBeLessThan(MIN_TEXT);
    expect(contrastRatio('#00666D', '#F4F7FA')).toBeGreaterThan(MIN_TEXT);
  });
});

describe('folhas de estilo consomem tokens', () => {
  const sheets = Object.keys(SHEETS);

  it('não têm cor HEX fixa fora de tokens.css (exceção: fundo branco do QR do MFA)', () => {
    const found = sheets.flatMap((sheet) => [...SHEETS[sheet]!.matchAll(/([^{}]*)\{[^{}]*?(#[0-9a-fA-F]{3,8})\b/g)].map((m) => `${sheet}: ${m[1]!.trim()} ${m[2]}`));
    expect(found).toEqual(['../management/Management.css: .mfa-qr #fff']);
  });

  it('só pedem os pesos carregados da Lato (400 e 700)', () => {
    const weights = sheets.flatMap((sheet) => [...SHEETS[sheet]!.matchAll(/font-weight:\s*([^;}]+)/g)].map((m) => m[1]!.trim()));
    expect(weights.filter((w) => !['var(--cp-font-weight-regular)', 'var(--cp-font-weight-strong)', 'inherit', '400', '700'].includes(w))).toEqual([]);
  });
});
