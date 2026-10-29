/**
 * Convite pendente nesta aba. O token chega só pelo fragmento (nunca enviado ao servidor), fica no sessionStorage
 * desta aba e some ao aceitar, ao falhar de forma definitiva, ao sair da conta ou ao fechar a aba.
 */
const STORAGE_KEY = 'condpulse:invitation';
const TOKEN = /^[A-Za-z0-9_-]{43}$/;

export function storedInvitation(): string | null {
  try { const value = window.sessionStorage.getItem(STORAGE_KEY); return value && TOKEN.test(value) ? value : null; }
  catch { return null; }
}
export function forgetInvitation(): void {
  try { window.sessionStorage.removeItem(STORAGE_KEY); } catch { /* Armazenamento indisponível. */ }
}
/** Lê o token do fragmento, guarda só nesta aba e limpa a barra de endereço mesmo quando o formato é inválido. */
export function captureInvitationFromLocation(): string | null {
  const hash = window.location.hash;
  if (!hash.startsWith('#token=')) return storedInvitation();
  window.history.replaceState(window.history.state, '', window.location.pathname);
  const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(hash);
  if (!match) return storedInvitation();
  try { window.sessionStorage.setItem(STORAGE_KEY, match[1]!); } catch { /* Sem armazenamento: aceite nesta visita. */ }
  return match[1]!;
}
