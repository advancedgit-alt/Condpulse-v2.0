/**
 * Ponto de extensão de white label no frontend (ADR-0010, fase 1). Recebe um objeto de configuração por
 * condomínio, valida o override contra o contrato docs/identidade/kit-v1.0/handoff/tenant-branding.schema.json
 * e devolve os tokens que podem mudar, com fallback automático para o padrão CondPulse quando o contraste
 * mínimo não é atingido. Ainda não há fonte de dados: o backend (perfis, revisões, upload, publicação e
 * restauração) vem depois da trilha facial. O servidor continuará sendo quem valida e autoriza.
 *
 * Regras fixas: cores de estado, foco e o logotipo CondPulse nunca são recoloridos pela marca do cliente; o tema
 * não concede permissão. Só chaves conhecidas são aceitas; nada é aplicado como CSS ou HTML livre.
 */
import { contrastRatio, isHexColor, MIN_NON_TEXT, MIN_TEXT, mix, passes } from './contrast';
import type { ResolvedTheme } from './theme';

const IDENTIFIER = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;
// Sem <, > nem caracteres de controle U+0000-U+001F, como no schema.
const DISPLAY = /^[^<>\u0000-\u001F]+$/;

export const OWNER_TYPES = ['organization', 'condominium'] as const;
export const CO_BRANDING = ['condpulse_signature', 'client_primary'] as const;
export const MODES = ['light', 'dark', 'system'] as const;
export const DENSITIES = ['comfortable', 'compact'] as const;
export const ASSET_KEYS = ['horizontalLight', 'horizontalDark', 'symbolLight', 'symbolDark', 'monoPositive', 'monoNegative', 'onBrand', 'favicon'] as const;
export const TOP_LEVEL_KEYS = ['schemaVersion', 'owner', 'inherits', 'identity', 'brand', 'assets', 'appearance'] as const;

type AssetKey = typeof ASSET_KEYS[number];

export interface TenantBrandingOverride {
  schemaVersion: 1;
  owner: { type: typeof OWNER_TYPES[number]; id: string };
  inherits: { profileId: string; revisionId: string };
  identity?: { displayName?: string; shortName?: string; coBranding?: typeof CO_BRANDING[number] };
  brand?: { primary?: string; accent?: string; fontFamilyId?: 'lato' };
  assets?: Partial<Record<AssetKey, string>>;
  appearance?: {
    defaultMode?: typeof MODES[number]; allowedModes?: typeof MODES[number][];
    defaultDensity?: typeof DENSITIES[number]; allowedDensities?: typeof DENSITIES[number][];
  };
}

export type BrandingValidation = { ok: true; value: TenantBrandingOverride } | { ok: false; errors: string[] };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Validação equivalente ao JSON Schema 2020-12 do kit (additionalProperties false em todos os níveis). */
export function validateTenantBranding(input: unknown): BrandingValidation {
  const errors: string[] = [];
  const object = (value: unknown, path: string, allowed: readonly string[], required: readonly string[] = [], minProperties = 0) => {
    if (!isObject(value)) { errors.push(`${path}: deve ser objeto`); return undefined; }
    for (const key of Object.keys(value)) if (!allowed.includes(key)) errors.push(`${path}.${key}: propriedade não permitida`);
    for (const key of required) if (!(key in value)) errors.push(`${path}.${key}: obrigatório`);
    if (Object.keys(value).length < minProperties) errors.push(`${path}: precisa de ao menos ${minProperties} propriedade`);
    return value;
  };
  const text = (value: unknown, path: string, pattern: RegExp, max: number) => {
    if (typeof value !== 'string' || value.length < 1 || value.length > max || !pattern.test(value)) errors.push(`${path}: valor inválido`);
  };
  const oneOf = (value: unknown, path: string, options: readonly string[]) => {
    if (typeof value !== 'string' || !options.includes(value)) errors.push(`${path}: valor fora da lista`);
  };
  const list = (value: unknown, path: string, options: readonly string[], max: number) => {
    if (!Array.isArray(value) || value.length < 1 || value.length > max) { errors.push(`${path}: lista inválida`); return; }
    value.forEach((item, index) => oneOf(item, `${path}[${index}]`, options));
    if (new Set(value).size !== value.length) errors.push(`${path}: itens repetidos`);
  };

  const root = object(input, '$', TOP_LEVEL_KEYS, ['schemaVersion', 'owner', 'inherits']);
  if (!root) return { ok: false, errors };
  if (root.schemaVersion !== 1) errors.push('$.schemaVersion: deve ser 1');
  const owner = 'owner' in root ? object(root.owner, '$.owner', ['type', 'id'], ['type', 'id']) : undefined;
  if (owner) { if ('type' in owner) oneOf(owner.type, '$.owner.type', OWNER_TYPES); if ('id' in owner) text(owner.id, '$.owner.id', IDENTIFIER, 128); }
  const inherits = 'inherits' in root ? object(root.inherits, '$.inherits', ['profileId', 'revisionId'], ['profileId', 'revisionId']) : undefined;
  if (inherits) for (const key of ['profileId', 'revisionId'] as const) if (key in inherits) text(inherits[key], `$.inherits.${key}`, IDENTIFIER, 128);
  if ('identity' in root) {
    const identity = object(root.identity, '$.identity', ['displayName', 'shortName', 'coBranding'], [], 1);
    if (identity) {
      if ('displayName' in identity) text(identity.displayName, '$.identity.displayName', DISPLAY, 64);
      if ('shortName' in identity) text(identity.shortName, '$.identity.shortName', DISPLAY, 24);
      if ('coBranding' in identity) oneOf(identity.coBranding, '$.identity.coBranding', CO_BRANDING);
    }
  }
  if ('brand' in root) {
    const brand = object(root.brand, '$.brand', ['primary', 'accent', 'fontFamilyId'], [], 1);
    if (brand) {
      for (const key of ['primary', 'accent'] as const) if (key in brand && !isHexColor(brand[key])) errors.push(`$.brand.${key}: use #RRGGBB`);
      if ('fontFamilyId' in brand && brand.fontFamilyId !== 'lato') errors.push('$.brand.fontFamilyId: só lato');
    }
  }
  if ('assets' in root) {
    const assets = object(root.assets, '$.assets', ASSET_KEYS, [], 1);
    if (assets) for (const [key, value] of Object.entries(assets)) if (ASSET_KEYS.includes(key as AssetKey)) text(value, `$.assets.${key}`, IDENTIFIER, 128);
  }
  if ('appearance' in root) {
    const appearance = object(root.appearance, '$.appearance', ['defaultMode', 'allowedModes', 'defaultDensity', 'allowedDensities'], [], 1);
    if (appearance) {
      if ('defaultMode' in appearance) oneOf(appearance.defaultMode, '$.appearance.defaultMode', MODES);
      if ('allowedModes' in appearance) list(appearance.allowedModes, '$.appearance.allowedModes', MODES, 3);
      if ('defaultDensity' in appearance) oneOf(appearance.defaultDensity, '$.appearance.defaultDensity', DENSITIES);
      if ('allowedDensities' in appearance) list(appearance.allowedDensities, '$.appearance.allowedDensities', DENSITIES, 2);
    }
  }
  return errors.length ? { ok: false, errors } : { ok: true, value: input as TenantBrandingOverride };
}

/** Configuração entregue pelo servidor para o condomínio ativo: override validável e URLs dos assets já processados. */
export interface TenantBrandingConfig {
  override: unknown;
  /** asset_id → caminho da mesma origem (ex.: /brand-assets/<hash>.svg). URLs externas são ignoradas (CSP img-src 'self'). */
  assetUrls?: Readonly<Record<string, string>>;
}

export interface ResolvedBranding {
  displayName?: string;
  /** Logo para superfície clara e para superfície escura/inversa; ausente usa a marca CondPulse. */
  logo: { light?: string; dark?: string };
  /** Tokens CSS sobrescritos (nome → valor). Vazio quando vale o padrão. */
  tokens: Record<string, string>;
  /** Tokens que caíram no padrão por contraste ou entrada inválida, para diagnóstico. */
  fallbacks: string[];
  errors: string[];
}

/** Valores padrão (tokens.css) usados como fundo nas medições. */
export const DEFAULTS = {
  light: { canvas: '#F4F7FA', panel: '#FFFFFF', inverse: '#102A43', onAction: '#FFFFFF', link: '#00666D', icon: '#007F86' },
  dark: { canvas: '#0D1724', panel: '#152334', inverse: '#0A1320', onAction: '#0D1724', link: '#5DD6C7', icon: '#5DD6C7' },
} as const;

const SAME_ORIGIN_PATH = /^\/(?!\/)[A-Za-z0-9._~\-/]+$/;

function assetUrl(config: TenantBrandingConfig, id: string | undefined): string | undefined {
  if (!id) return undefined;
  const url = config.assetUrls?.[id];
  return typeof url === 'string' && SAME_ORIGIN_PATH.test(url) && !url.includes('..') ? url : undefined;
}

/**
 * Tom do primário para o tema escuro: o próprio ou um clareamento progressivo (20% a 80% de branco) até cumprir
 * o contraste pedido. No claro o primário é usado como veio; sem contraste, vale o padrão.
 */
function darkTone(primary: string, ok: (candidate: string) => boolean): string | undefined {
  for (const weight of [0, 0.2, 0.4, 0.6, 0.8]) {
    const candidate = weight === 0 ? primary.toUpperCase() : mix(primary, '#FFFFFF', weight);
    if (ok(candidate)) return candidate;
  }
  return undefined;
}

export function resolveBranding(config: TenantBrandingConfig | undefined, theme: ResolvedTheme): ResolvedBranding {
  const empty: ResolvedBranding = { logo: {}, tokens: {}, fallbacks: [], errors: [] };
  if (!config) return empty;
  const checked = validateTenantBranding(config.override);
  if (!checked.ok) return { ...empty, errors: checked.errors };
  const override = checked.value;
  const base = DEFAULTS[theme];
  const tokens: Record<string, string> = {};
  const fallbacks: string[] = [];

  const primary = override.brand?.primary;
  if (primary) {
    const tone = (ok: (candidate: string) => boolean) => theme === 'light' ? (ok(primary.toUpperCase()) ? primary.toUpperCase() : undefined) : darkTone(primary, ok);
    const action = tone((c) => contrastRatio(base.onAction, c) >= MIN_TEXT);
    if (action) {
      const hover = theme === 'light' ? mix(action, '#000000', 0.18) : mix(action, '#FFFFFF', 0.2);
      tokens['--cp-color-action-primary-background'] = action;
      tokens['--cp-color-action-primary-hover'] = contrastRatio(base.onAction, hover) >= MIN_TEXT ? hover : action;
    } else fallbacks.push('--cp-color-action-primary-background', '--cp-color-action-primary-hover');
    const link = tone((c) => passes(c, [base.canvas, base.panel], MIN_TEXT));
    if (link) tokens['--cp-color-text-link'] = link; else fallbacks.push('--cp-color-text-link');
    const icon = tone((c) => passes(c, [base.canvas, base.panel], MIN_NON_TEXT));
    if (icon) { tokens['--cp-color-icon-accent'] = icon; tokens['--cp-color-border-accent'] = icon; }
    else fallbacks.push('--cp-color-icon-accent', '--cp-color-border-accent');
    // Fundo sutil (aba atual, ícones em placa): só muda se o link e o ícone efetivos continuarem legíveis sobre ele.
    const subtle = mix(base.panel, icon ?? action ?? primary.toUpperCase(), theme === 'light' ? 0.1 : 0.2);
    const effectiveLink = link ?? base.link; const effectiveIcon = icon ?? base.icon;
    if (contrastRatio(effectiveLink, subtle) >= MIN_TEXT && contrastRatio(effectiveIcon, subtle) >= MIN_NON_TEXT) tokens['--cp-color-surface-accent-subtle'] = subtle;
    else fallbacks.push('--cp-color-surface-accent-subtle');
  }
  const accent = override.brand?.accent;
  if (accent) {
    if (contrastRatio(accent, base.inverse) >= MIN_TEXT) tokens['--cp-color-text-inverse-link'] = accent.toUpperCase();
    else fallbacks.push('--cp-color-text-inverse-link');
  }
  const assets = override.assets ?? {};
  return {
    displayName: override.identity?.displayName,
    logo: { light: assetUrl(config, assets.horizontalLight), dark: assetUrl(config, assets.horizontalDark) },
    tokens, fallbacks, errors: [],
  };
}
