import { createContext, useContext, useEffect } from 'react';
import { ApiError } from '../../api/client';
import type { PlatformSession } from '../../api/platform';

/** Horários da plataforma no fuso de operação do projeto; cada condomínio mostra o próprio fuso no cadastro. */
export const PLATFORM_ZONE = 'America/Sao_Paulo';
export const platformKey = (accountId: string, ...parts: unknown[]) => ['private', accountId, 'platform', ...parts] as const;
/** Lista sem busca: também alimenta os nomes da auditoria e a escolha de condomínio no financeiro. */
export const tenantListKey = (accountId: string, search: string | null) => platformKey(accountId, 'tenants', search);

export interface PlatformValue { accountId: string; session: PlatformSession; requireMfa(): void; denied(error: unknown): void }
export const PlatformContext = createContext<PlatformValue | null>(null);
export function usePlatform(): PlatformValue {
  const value = useContext(PlatformContext);
  if (!value) throw new Error('Contexto da plataforma ausente.');
  return value;
}
function isAccessError(error: unknown): boolean {
  return error instanceof ApiError && ['MFA_REQUIRED', 'PLATFORM_ACCESS_DENIED'].includes(error.code);
}
/** Perda do papel ou falta do segundo fator em qualquer ação: a porta do painel reage (dados removidos ou MFA). */
export function react(error: unknown, scope: Pick<PlatformValue, 'requireMfa' | 'denied'>): void {
  if (!(error instanceof ApiError)) return;
  if (error.code === 'MFA_REQUIRED') scope.requireMfa();
  else if (error.code === 'PLATFORM_ACCESS_DENIED') scope.denied(error);
}
/** Erro de acesso de consulta: reage depois da renderização e esconde o conteúdo enquanto a porta muda de estado. */
export function useAccessGuard(error: unknown): boolean {
  const scope = usePlatform();
  const access = isAccessError(error);
  useEffect(() => { if (access) react(error, scope); }, [access, error, scope]);
  return access;
}
/** Resultado incerto: rede, resposta inválida ou erro do servidor depois de enviar; atualizar e conferir antes de repetir. */
export function isUncertain(error: unknown): boolean {
  return !(error instanceof ApiError) || error.uncertain || error.status >= 500;
}

export interface Outcome { text: string; tone: 'success' | 'warning' | 'info' }
export const short = (id: string) => id.slice(0, 8);
