import type { Vault } from './vault';

/**
 * No iOS o Keychain sobrevive à desinstalação do aplicativo. A marca fica nos arquivos do próprio app, que somem ao
 * desinstalar: sem a marca, é uma instalação nova e qualquer sessão deixada no cofre por uma instalação anterior é
 * apagada antes de o app mostrar qualquer coisa.
 */
export interface InstallMarker {
  exists(): boolean;
  create(): void;
}

export async function ensureFreshInstall(marker: InstallMarker, vault: Vault): Promise<'fresh' | 'existing'> {
  if (marker.exists()) return 'existing';
  await vault.wipe();
  marker.create();
  return 'fresh';
}
