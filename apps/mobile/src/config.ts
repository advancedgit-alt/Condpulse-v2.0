/**
 * Configuração pública do aplicativo, embutida no build pelas variáveis EXPO_PUBLIC_* (nunca segredos: a chave do
 * Supabase aqui é a publicável). Valores fora do formato esperado impedem o app de abrir a tela de acesso.
 */
export interface AppConfig { supabaseUrl: string; publishableKey: string; apiUrl: string }

export function readConfig(env: Record<string, string | undefined>): AppConfig | null {
  const supabaseUrl = env.EXPO_PUBLIC_SUPABASE_URL ?? '';
  const publishableKey = env.EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY ?? '';
  const apiUrl = env.EXPO_PUBLIC_API_URL ?? '';
  if (!/^https:\/\/[a-z0-9]{20}\.supabase\.co$/.test(supabaseUrl)) return null;
  // Só a chave publicável; uma chave secreta ou de serviço nunca entra no aplicativo.
  if (!/^sb_publishable_[A-Za-z0-9_-]{16,}$/.test(publishableKey)) return null;
  try {
    const api = new URL(apiUrl);
    if (api.protocol !== 'https:' || api.username || api.password || api.search || api.hash || (api.pathname !== '/' && api.pathname !== '')) return null;
  } catch { return null; }
  return { supabaseUrl, publishableKey, apiUrl: apiUrl.replace(/\/$/, '') };
}
