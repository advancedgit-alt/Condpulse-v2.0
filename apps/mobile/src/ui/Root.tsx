import { useEffect, useState, useSyncExternalStore } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import type { CondPulseApi, Membership } from '../api/client';
import { SessionError, type SessionManager } from '../session/session';
import { colors, touch } from './theme';

const roleLabels: Record<Membership['role'], string> = {
  RESIDENT: 'Morador', RESPONSIBLE: 'Responsável pela unidade', DEPENDENT: 'Dependente', OWNER: 'Proprietário',
  CONCIERGE: 'Portaria', MANAGER: 'Gestão', SUPPORT: 'Suporte',
};
const message = (error: unknown) => error instanceof Error && error.name !== 'Error' ? error.message : 'Não foi possível concluir agora. Tente novamente.';

export function Root({ manager, api }: { manager: SessionManager; api: CondPulseApi }) {
  const state = useSyncExternalStore((listener) => manager.subscribe(listener), () => manager.getState());
  if (state.status === 'loading') return <ActivityIndicator accessibilityLabel="Abrindo" style={styles.spinner} />;
  if (state.status === 'signedOut') return <Login manager={manager} expired={state.reason === 'expired'} />;
  if (state.status === 'mfaRequired') return <Mfa manager={manager} />;
  return <Home key={state.accountId} manager={manager} api={api} accountId={state.accountId} />;
}

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, tone === 'error' ? styles.error : styles.info]}>{children}</Text>;
}

function Login({ manager, expired }: { manager: SessionManager; expired: boolean }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  async function submit() {
    if (busy) return;
    setBusy(true); setFailure(null);
    try { await manager.signIn(email, password); setPassword(''); }
    catch (error) { setFailure(error instanceof SessionError ? error.message : message(error)); }
    finally { setBusy(false); }
  }
  return <ScrollView contentContainerStyle={styles.screen} keyboardShouldPersistTaps="handled">
    <Text accessibilityRole="header" style={styles.title}>Entrar no CondPulse</Text>
    {expired ? <Notice tone="info">Sua sessão terminou. Entre novamente para continuar.</Notice> : null}
    <Text style={styles.label} nativeID="email-label">E-mail</Text>
    <TextInput accessibilityLabelledBy="email-label" accessibilityLabel="E-mail" style={styles.input} value={email} onChangeText={setEmail}
      autoCapitalize="none" autoComplete="email" keyboardType="email-address" textContentType="username" editable={!busy} />
    <Text style={styles.label} nativeID="password-label">Senha</Text>
    <TextInput accessibilityLabelledBy="password-label" accessibilityLabel="Senha" style={styles.input} value={password} onChangeText={setPassword}
      secureTextEntry autoComplete="current-password" textContentType="password" editable={!busy} onSubmitEditing={() => void submit()} />
    {failure ? <Notice tone="error">{failure}</Notice> : null}
    <Button label={busy ? 'Entrando…' : 'Entrar'} disabled={busy || !email || !password} onPress={() => void submit()} />
  </ScrollView>;
}

function Mfa({ manager }: { manager: SessionManager }) {
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  async function submit() {
    if (busy) return;
    setBusy(true); setFailure(null);
    try { await manager.verifyMfa(code); }
    catch (error) { setFailure(message(error)); setCode(''); }
    finally { setBusy(false); }
  }
  return <ScrollView contentContainerStyle={styles.screen} keyboardShouldPersistTaps="handled">
    <Text accessibilityRole="header" style={styles.title}>Segundo fator</Text>
    <Text style={styles.body}>Informe o código de 6 dígitos do seu aplicativo autenticador.</Text>
    <TextInput accessibilityLabel="Código do autenticador" style={styles.input} value={code} onChangeText={(value) => setCode(value.replace(/\D/g, '').slice(0, 6))}
      keyboardType="number-pad" textContentType="oneTimeCode" autoComplete="one-time-code" maxLength={6} editable={!busy} />
    {failure ? <Notice tone="error">{failure}</Notice> : null}
    <Button label={busy ? 'Conferindo…' : 'Confirmar'} disabled={busy || code.length !== 6} onPress={() => void submit()} />
    <Button label="Sair" secondary disabled={busy} onPress={() => void manager.signOut()} />
  </ScrollView>;
}

function Home({ manager, api, accountId }: { manager: SessionManager; api: CondPulseApi; accountId: string }) {
  const identity = useQuery({ queryKey: ['private', accountId, 'identity'], queryFn: ({ signal }) => api.me(signal) });
  const [selected, setSelected] = useState<string | null>(null);
  const [leaving, setLeaving] = useState(false);
  // A resposta precisa ser da mesma conta da sessão; outra conta encerra tudo.
  const mismatch = identity.data !== undefined && identity.data.accountId !== accountId;
  useEffect(() => { if (mismatch) void manager.expire(); }, [mismatch, manager]);
  const active = identity.data?.memberships.find((item) => item.membershipId === selected) ?? null;
  return <ScrollView contentContainerStyle={styles.screen}>
    <Text accessibilityRole="header" style={styles.title}>Seus condomínios</Text>
    {identity.isPending || mismatch ? <ActivityIndicator accessibilityLabel="Carregando seus vínculos" />
      : identity.isError ? <View><Notice tone="error">{message(identity.error)}</Notice>
        <Button label="Tentar novamente" secondary onPress={() => void identity.refetch()} /></View>
        : identity.data.memberships.length === 0 ? <Notice tone="info">Nenhum vínculo ativo. Procure a gestão do seu condomínio.</Notice>
          : <View accessibilityRole="list" accessibilityLabel="Vínculos">{identity.data.memberships.map((item) =>
            <Pressable key={item.membershipId} accessibilityRole="button" accessibilityState={{ selected: item.membershipId === selected }}
              accessibilityLabel={`${item.tenantName}, ${roleLabels[item.role]}`} onPress={() => setSelected(item.membershipId)}
              style={[styles.card, item.membershipId === selected && styles.cardSelected]}>
              <Text style={styles.cardTitle}>{item.tenantName}</Text>
              <Text style={styles.body}>{roleLabels[item.role]}{item.unitId ? ' · unidade vinculada' : ''}</Text>
            </Pressable>)}</View>}
    {active ? <Notice tone="info">{`Contexto: ${active.tenantName} (${roleLabels[active.role]}). As funções do aplicativo chegam nas próximas entregas.`}</Notice> : null}
    <Button label={leaving ? 'Saindo…' : 'Sair'} secondary disabled={leaving}
      onPress={() => { setLeaving(true); void manager.signOut().finally(() => setLeaving(false)); }} />
  </ScrollView>;
}

const styles = StyleSheet.create({
  spinner: { marginTop: 48 },
  screen: { padding: 24, gap: 12 },
  title: { fontSize: 26, fontWeight: '700', color: colors.navy, marginBottom: 8 },
  body: { fontSize: 15, color: colors.muted },
  label: { fontSize: 14, fontWeight: '600', color: colors.text },
  input: { minHeight: touch, borderWidth: 1, borderColor: colors.line, borderRadius: 10, paddingHorizontal: 14, fontSize: 16, backgroundColor: colors.surface, color: colors.text },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  card: { minHeight: touch, padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, marginBottom: 10 },
  cardSelected: { borderColor: colors.teal, borderWidth: 2 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
});
