import type { PlatformTenant } from '../../api/platform';
import { displayText } from '../../api/text';
import { formatWhen } from '../dateTime';

/** Mesmo limite do servidor e do manifesto do onboarding. */
export const MAX_UNITS = 250;

/**
 * Uma unidade por linha: espaços repetidos viram um só, linhas vazias são ignoradas, repetição (sem diferenciar
 * maiúsculas) e identificação com mais de 40 caracteres ou invisível são recusadas. O servidor confere de novo.
 */
export function parseUnitLines(text: string): { units: string[] } | { error: string } {
  const units: string[] = []; const seen = new Set<string>();
  for (const [index, line] of text.split(/\r?\n/).entries()) {
    if (!line.trim()) continue;
    const label = displayText(line, 40);
    if (label === null) return { error: `Linha ${index + 1}: use de 1 a 40 caracteres visíveis por unidade.` };
    const key = label.toLocaleLowerCase('pt-BR');
    if (seen.has(key)) return { error: `A unidade "${label}" aparece mais de uma vez.` };
    seen.add(key); units.push(label);
  }
  if (units.length === 0) return { error: 'Informe ao menos uma unidade, uma por linha.' };
  if (units.length > MAX_UNITS) return { error: `Informe até ${MAX_UNITS} unidades por cadastro.` };
  return { units };
}

/** Fuso IANA reconhecido pelo navegador; o servidor confere de novo no Java e no PostgreSQL. */
export function validTimeZone(zone: string): boolean {
  const value = zone.trim();
  if (!/^[A-Za-z_]+(\/[A-Za-z0-9_+-]+)*$/.test(value)) return false;
  try { new Intl.DateTimeFormat('pt-BR', { timeZone: value }); return true; } catch { return false; }
}

const plural = (value: number, one: string, many: string) => `${value} ${value === 1 ? one : many}`;

/** Resumo de uso para a lista e a ficha; API anterior a V034 não informa o uso. */
export function usageText(tenant: PlatformTenant, zone: string): string {
  const usage = tenant.usage;
  if (!usage) return 'Uso não informado por esta versão da API.';
  return `${plural(usage.activeUnits, 'unidade ativa', 'unidades ativas')} · ${plural(usage.activeMemberships, 'vínculo vigente', 'vínculos vigentes')} · `
    + `${usage.activeManagers === 0 ? 'sem gestor vigente' : plural(usage.activeManagers, 'gestor', 'gestores')} · última atividade em ${formatWhen(usage.lastActivityAt, zone)}`;
}

/** Situação da assinatura CondPulse, quando o financeiro está ligado; vencida não bloqueia o acesso. */
export function billingText(tenant: PlatformTenant): string | null {
  const billing = tenant.billing;
  if (!billing) return null;
  const subscription = billing.activeSubscription ? 'Assinatura ativa' : 'Sem assinatura ativa';
  if (billing.openCharges === 0) return `${subscription} · nenhuma cobrança em aberto`;
  return `${subscription} · ${plural(billing.openCharges, 'cobrança em aberto', 'cobranças em aberto')}`
    + (billing.overdueCharges > 0 ? ` (${plural(billing.overdueCharges, 'vencida', 'vencidas')})` : '');
}

export function originText(origin: PlatformTenant['origin']): string {
  return origin === 'PLATFORM' ? 'Criado no painel master' : origin === 'ONBOARDING' ? 'Criado pelo onboarding operacional' : 'Origem não registrada';
}
