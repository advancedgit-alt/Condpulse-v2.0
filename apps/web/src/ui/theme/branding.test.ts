/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { contrastRatio } from './contrast';
import { ASSET_KEYS, CO_BRANDING, DEFAULTS, DENSITIES, MODES, OWNER_TYPES, resolveBranding, TOP_LEVEL_KEYS, validateTenantBranding } from './branding';

// Contrato do kit em docs/identidade (lido em tempo de teste; a imagem web só faz o typecheck).
const kit = (file: string) => JSON.parse(readFileSync(new URL(`../../../../../docs/identidade/kit-v1.0/handoff/${file}`, import.meta.url), 'utf8')) as Record<string, any>;
const schema = kit('tenant-branding.schema.json');
const example = kit('tenant-branding.example.json');
const base = { schemaVersion: 1, owner: { type: 'condominium', id: 'condominio_demo' }, inherits: { profileId: 'p1', revisionId: 'r1' } };

describe('contrato de white label', () => {
  it('acompanha as chaves e enums do schema do kit', () => {
    const props = schema.properties;
    expect([...TOP_LEVEL_KEYS].sort()).toEqual(Object.keys(props).sort());
    expect(schema.required).toEqual(['schemaVersion', 'owner', 'inherits']);
    expect([...ASSET_KEYS].sort()).toEqual(Object.keys(props.assets.properties).sort());
    expect([...OWNER_TYPES]).toEqual(props.owner.properties.type.enum);
    expect([...CO_BRANDING]).toEqual(props.identity.properties.coBranding.enum);
    expect([...MODES]).toEqual(props.appearance.properties.defaultMode.enum);
    expect([...DENSITIES]).toEqual(props.appearance.properties.defaultDensity.enum);
  });

  it('aceita o exemplo do kit', () => {
    expect(validateTenantBranding(example)).toEqual({ ok: true, value: example });
  });

  it.each([
    ['propriedade desconhecida', { ...base, css: 'body{}' }],
    ['status de publicação enviado pelo cliente', { ...base, status: 'published' }],
    ['cor com CSS', { ...base, brand: { primary: 'red;background:url(x)' } }],
    ['cor curta', { ...base, brand: { primary: '#fff' } }],
    ['URL no lugar do id de asset', { ...base, assets: { horizontalLight: 'https://example.invalid/logo.svg' } }],
    ['HTML no nome', { ...base, identity: { displayName: '<img src=x>' } }],
    ['fonte fora da lista', { ...base, brand: { fontFamilyId: 'comic' } }],
    ['sem herança fixada', { schemaVersion: 1, owner: base.owner }],
    ['versão errada', { ...base, schemaVersion: 2 }],
    ['modos repetidos', { ...base, appearance: { allowedModes: ['dark', 'dark'] } }],
    ['grupo vazio', { ...base, brand: {} }],
  ])('rejeita %s', (_label, input) => {
    expect(validateTenantBranding(input).ok).toBe(false);
  });
});

describe('resolução da marca do condomínio', () => {
  it('sem configuração vale o padrão CondPulse', () => {
    expect(resolveBranding(undefined, 'light')).toEqual({ logo: {}, tokens: {}, fallbacks: [], errors: [] });
  });

  it('configuração inválida não aplica nada', () => {
    const result = resolveBranding({ override: { ...base, brand: { primary: 'javascript:1' } } }, 'light');
    expect(result.tokens['--cp-color-action-primary-background']).toBeUndefined();
    expect(result.tokens['--cp-color-text-link']).toBeUndefined();
    expect(result.tokens['--cp-color-icon-accent']).toBeUndefined();
    // O fundo sutil pode receber o tom do cliente, mas só se o link padrão continuar legível sobre ele.
    const subtle = result.tokens['--cp-color-surface-accent-subtle'];
    if (subtle) expect(contrastRatio(DEFAULTS.light.link, subtle)).toBeGreaterThanOrEqual(4.5);
    expect(result.errors.length).toBeGreaterThan(0);
  });

  it('acento escuro suficiente vira ação, link e ícone, com contraste conferido', () => {
    const result = resolveBranding({ override: { ...base, brand: { primary: '#5B2A86' } } }, 'light');
    expect(result.tokens['--cp-color-action-primary-background']).toBe('#5B2A86');
    expect(result.tokens['--cp-color-text-link']).toBe('#5B2A86');
    expect(result.fallbacks).toEqual([]);
    expect(contrastRatio('#FFFFFF', result.tokens['--cp-color-action-primary-hover']!)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(result.tokens['--cp-color-text-link']!, result.tokens['--cp-color-surface-accent-subtle']!)).toBeGreaterThanOrEqual(4.5);
  });

  it('acento claro demais cai no padrão automaticamente (WL-03)', () => {
    const result = resolveBranding({ override: { ...base, brand: { primary: '#FFD400' } } }, 'light');
    expect(result.tokens['--cp-color-action-primary-background']).toBeUndefined();
    expect(result.tokens['--cp-color-text-link']).toBeUndefined();
    expect(result.tokens['--cp-color-icon-accent']).toBeUndefined();
    // O fundo sutil pode receber o tom do cliente, mas só se o link padrão continuar legível sobre ele.
    const subtle = result.tokens['--cp-color-surface-accent-subtle'];
    if (subtle) expect(contrastRatio(DEFAULTS.light.link, subtle)).toBeGreaterThanOrEqual(4.5);
    expect(result.fallbacks).toContain('--cp-color-action-primary-background');
    expect(result.fallbacks).toContain('--cp-color-text-link');
  });

  it('no escuro clareia o acento até ler texto escuro por cima ou cai no padrão', () => {
    const result = resolveBranding({ override: { ...base, brand: { primary: '#5B2A86' } } }, 'dark');
    const action = result.tokens['--cp-color-action-primary-background'];
    expect(action).toBeDefined();
    expect(contrastRatio(DEFAULTS.dark.onAction, action!)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(result.tokens['--cp-color-text-link']!, DEFAULTS.dark.panel)).toBeGreaterThanOrEqual(4.5);
  });

  it('nunca toca em estado, foco ou marca CondPulse', () => {
    const result = resolveBranding({ override: { ...base, brand: { primary: '#5B2A86', accent: '#F2C14E' } } }, 'light');
    expect(Object.keys(result.tokens).filter((key) => /status|focus|brand-/.test(key))).toEqual([]);
    expect(result.tokens['--cp-color-text-inverse-link']).toBe('#F2C14E');
  });

  it('acento sobre a barra lateral sem contraste cai no padrão', () => {
    const result = resolveBranding({ override: { ...base, brand: { accent: '#102A43' } } }, 'light');
    expect(result.tokens['--cp-color-text-inverse-link']).toBeUndefined();
    expect(result.fallbacks).toEqual(['--cp-color-text-inverse-link']);
  });

  it('logo só de caminho da mesma origem e do asset declarado', () => {
    const override = { ...base, identity: { displayName: 'Residencial Horizonte' }, assets: { horizontalLight: 'logo_a', horizontalDark: 'logo_b' } };
    const ok = resolveBranding({ override, assetUrls: { logo_a: '/brand-assets/a1b2.svg', logo_b: 'https://example.invalid/b.svg' } }, 'light');
    expect(ok.logo).toEqual({ light: '/brand-assets/a1b2.svg', dark: undefined });
    expect(ok.displayName).toBe('Residencial Horizonte');
    const traversal = resolveBranding({ override, assetUrls: { logo_a: '/../segredo', logo_b: '//example.invalid/x' } }, 'light');
    expect(traversal.logo).toEqual({ light: undefined, dark: undefined });
  });
});
