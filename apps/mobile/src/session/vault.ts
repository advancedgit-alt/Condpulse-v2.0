/**
 * Cofre do aparelho para a sessão do Auth (CP-012). Guarda os valores no armazenamento seguro do sistema (Keychain/
 * Keystore pelo expo-secure-store), dividido em partes porque cada item tem limite de tamanho. Mantém o índice das
 * chaves gravadas para que a saída, a troca de conta e a reinstalação apaguem tudo, sem depender de saber o nome que
 * a biblioteca de Auth usou. Nada daqui vai para log.
 */
export interface SecureKeyValue {
  getItemAsync(key: string): Promise<string | null>;
  setItemAsync(key: string, value: string): Promise<void>;
  deleteItemAsync(key: string): Promise<void>;
}
export interface Vault {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
  /** Apaga todas as chaves que este cofre já gravou (e o próprio índice). */
  wipe(): Promise<void>;
  /**
   * Fecha o cofre na saída: apaga tudo e, até `open()`, descarta gravações e não devolve leituras. Uma renovação da
   * sessão antiga que termine depois da saída não regrava o token.
   */
  seal(): Promise<void>;
  /** Reabre para um novo login. */
  open(): void;
}

/** Parte abaixo do limite de 2048 bytes do SecureStore, mesmo com caracteres de 3 bytes. */
export const CHUNK = 600;
const INDEX = 'cp.vault.index';
const MAX_PARTS = 64;

/** Chaves do SecureStore aceitam só letras, números, ponto, hífen e sublinhado. */
export function safeKey(key: string): string {
  return `cp.v.${key.replace(/[^A-Za-z0-9._-]/g, '_')}`;
}

export function createVault(store: SecureKeyValue): Vault {
  // Operações em série: gravação e apagamento simultâneos não deixam parte órfã nem índice desatualizado.
  let tail: Promise<unknown> = Promise.resolve();
  let sealed = false;
  const serial = <T>(operation: () => Promise<T>): Promise<T> => {
    const next = tail.then(operation, operation);
    tail = next.then(() => undefined, () => undefined);
    return next;
  };
  async function readIndex(): Promise<string[]> {
    const raw = await store.getItemAsync(INDEX);
    if (!raw) return [];
    try {
      const parsed: unknown = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : [];
    } catch { return []; }
  }
  async function writeIndex(keys: string[]): Promise<void> {
    if (keys.length === 0) await store.deleteItemAsync(INDEX);
    else await store.setItemAsync(INDEX, JSON.stringify([...new Set(keys)].sort()));
  }
  /**
   * Apaga todas as partes possíveis, não só as contadas em `.n`: uma gravação interrompida antes do contador não deixa
   * fragmento de token para trás. O índice já registra a chave antes da primeira parte gravada.
   */
  async function removeParts(base: string): Promise<void> {
    await store.deleteItemAsync(`${base}.n`);
    for (let index = 0; index < MAX_PARTS; index += 1) await store.deleteItemAsync(`${base}.${index}`);
  }
  async function wipeAll(): Promise<void> {
    for (const base of await readIndex()) await removeParts(base);
    await store.deleteItemAsync(INDEX);
  }
  return {
    getItem: (key) => serial(async () => {
      if (sealed) return null;
      const base = safeKey(key);
      const count = Number(await store.getItemAsync(`${base}.n`));
      if (!Number.isInteger(count) || count < 1 || count > MAX_PARTS) return null;
      const parts: string[] = [];
      for (let index = 0; index < count; index += 1) {
        const part = await store.getItemAsync(`${base}.${index}`);
        // Parte faltando: valor corrompido não é devolvido pela metade.
        if (part === null) return null;
        parts.push(part);
      }
      return parts.join('');
    }),
    setItem: (key, value) => {
      if (sealed) return Promise.resolve();
      return serial(async () => {
        if (sealed) return;
        const base = safeKey(key);
        const parts = Math.max(1, Math.ceil(value.length / CHUNK));
        if (parts > MAX_PARTS) throw new Error('VAULT_VALUE_TOO_LARGE');
        // Índice antes da primeira parte: uma gravação interrompida ainda é apagada pela saída ou pela reinstalação.
        await writeIndex([...(await readIndex()), base]);
        const previous = Number(await store.getItemAsync(`${base}.n`));
        await store.deleteItemAsync(`${base}.n`);
        for (let index = 0; index < parts; index += 1) await store.setItemAsync(`${base}.${index}`, value.slice(index * CHUNK, (index + 1) * CHUNK));
        const stale = Number.isInteger(previous) && previous > parts && previous <= MAX_PARTS ? previous : parts;
        for (let index = parts; index < stale; index += 1) await store.deleteItemAsync(`${base}.${index}`);
        await store.setItemAsync(`${base}.n`, String(parts));
      });
    },
    removeItem: (key) => serial(async () => {
      const base = safeKey(key);
      await removeParts(base);
      await writeIndex((await readIndex()).filter((item) => item !== base));
    }),
    wipe: () => serial(wipeAll),
    seal: () => { sealed = true; return serial(wipeAll); },
    open: () => { sealed = false; },
  };
}
