/**
 * Convite pendente nesta aba. O token chega só pelo fragmento (nunca enviado ao servidor), fica no sessionStorage
 * desta aba e some ao aceitar, ao falhar de forma definitiva, ao sair da conta ou ao fechar a aba. O convite de
 * primeiro gestor (painel master) usa uma chave própria, para não ser confundido com o convite da gestão.
 */
import type { InvitationKind } from '../api/client';

const STORAGE_KEYS: Record<InvitationKind, string> = { membership: 'condpulse:invitation', manager: 'condpulse:manager-invitation' };
const TOKEN = /^[A-Za-z0-9_-]{43}$/;

export function storedInvitation(kind: InvitationKind = 'membership'): string | null {
  try { const value = window.sessionStorage.getItem(STORAGE_KEYS[kind]); return value && TOKEN.test(value) ? value : null; }
  catch { return null; }
}
export function forgetInvitation(kind?: InvitationKind): void {
  // Sem tipo (saída da conta): os dois convites desta aba são descartados.
  for (const key of kind ? [STORAGE_KEYS[kind]] : Object.values(STORAGE_KEYS)) {
    try { window.sessionStorage.removeItem(key); } catch { /* Armazenamento indisponível. */ }
  }
}
/** Lê o token do fragmento, guarda só nesta aba e limpa a barra de endereço mesmo quando o formato é inválido. */
export function captureInvitationFromLocation(kind: InvitationKind = 'membership'): string | null {
  const hash = window.location.hash;
  if (!hash.startsWith('#token=')) return storedInvitation(kind);
  window.history.replaceState(window.history.state, '', window.location.pathname);
  const match = /^#token=([A-Za-z0-9_-]{43})$/.exec(hash);
  if (!match) return storedInvitation(kind);
  try { window.sessionStorage.setItem(STORAGE_KEYS[kind], match[1]!); } catch { /* Sem armazenamento: aceite nesta visita. */ }
  return match[1]!;
}
