import { AppState } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import { File, Paths } from 'expo-file-system';
import { createClient } from '@supabase/supabase-js';
import type { AppConfig } from '../config';
import { createVault, type SecureKeyValue } from './vault';
import { ensureFreshInstall } from './install';
import { SessionManager, type AuthPort } from './session';

/** Itens só acessíveis com o aparelho desbloqueado e nunca copiados para outro aparelho por backup. */
const secureOptions = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY };
const secureStore: SecureKeyValue = {
  getItemAsync: (key) => SecureStore.getItemAsync(key, secureOptions),
  setItemAsync: (key, value) => SecureStore.setItemAsync(key, value, secureOptions),
  deleteItemAsync: (key) => SecureStore.deleteItemAsync(key, secureOptions),
};

/** Monta Auth, cofre e sessão; apaga sessão herdada de uma instalação anterior antes de qualquer leitura. */
export async function createRuntime(config: AppConfig, clearData: () => void): Promise<SessionManager> {
  const vault = createVault(secureStore);
  const marker = new File(Paths.document, 'condpulse-install');
  await ensureFreshInstall({ exists: () => marker.exists, create: () => { marker.create(); } }, vault);
  const supabase = createClient(config.supabaseUrl, config.publishableKey, {
    auth: { storage: vault, storageKey: 'cp-auth', autoRefreshToken: true, persistSession: true, detectSessionInUrl: false, flowType: 'pkce' },
  });
  // Renovação automática só com o app em primeiro plano, como recomenda o Supabase para React Native.
  AppState.addEventListener('change', (state) => {
    if (state === 'active') void supabase.auth.startAutoRefresh();
    else void supabase.auth.stopAutoRefresh();
  });
  const manager = new SessionManager(supabase.auth as unknown as AuthPort, vault, clearData);
  await manager.start();
  return manager;
}
