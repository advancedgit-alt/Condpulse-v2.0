import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { ApiError, type CondPulseApi } from '../api/client';
import { describeEvent, type Credential, type CredentialStatus } from '../api/access';
import { formatWhen } from '../time';
import { colors, touch } from './theme';

/**
 * Credenciais e histórico dos equipamentos no celular (CP-046/CP-049). A unidade vê só as próprias credenciais e o que
 * os equipamentos informaram nos últimos 7 dias; revogação só aparece como confirmada quando todos os equipamentos
 * confirmarem, e "sem registro de saída" nunca vira presença.
 */
const statusLabels: Record<CredentialStatus, string> = {
  ACTIVE: 'Ativa em todos os equipamentos', SYNCING: 'Enviando aos equipamentos', PARTIAL: 'Ativa em parte dos equipamentos',
  FAILED: 'Recusada por equipamento', EXPIRED: 'Vínculo vencido', REVOKING: 'Revogação pendente nos equipamentos',
  REVOKED: 'Revogada em todos os equipamentos', NO_DEVICES: 'Sem equipamento na integração',
};
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
/** Recurso desligado ou vínculo que mudou: a seção some sem erro técnico. */
const hidden = (error: unknown) => error instanceof ApiError && (error.status === 404 || ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code));
const deviceText = (item: Credential, state: 'CONFIRMED' | 'PENDING' | 'FAILED') =>
  state === 'CONFIRMED' ? (item.desiredState === 'REVOKED' ? 'revogação confirmada' : 'ativa')
    : state === 'FAILED' ? 'recusada pelo equipamento' : (item.desiredState === 'REVOKED' ? 'revogação pendente' : 'aguardando');

function Button({ label, onPress, disabled }: { label: string; onPress(): void; disabled?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, (disabled || pressed) && styles.dimmed]}>
    <Text style={styles.buttonText}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}

export function DeviceAccess({ api, accountId, tenantId, unitId, timeZone }: { api: CondPulseApi; accountId: string; tenantId: string; unitId: string; timeZone: string }) {
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'device-access'] as const;
  const own = useQuery({ queryKey: [...scope, 'credentials'], queryFn: ({ signal }) => api.unitCredentials(tenantId, unitId, signal),
    retry: (count, error) => !hidden(error) && count < 1 });
  const events = useQuery({ queryKey: [...scope, 'events'], queryFn: ({ signal }) => api.unitDeviceEvents(tenantId, unitId, signal), enabled: !hidden(own.error),
    retry: (count, error) => !hidden(error) && count < 1 });
  if (hidden(own.error) || hidden(events.error)) return null;
  const stale = (own.isError && own.data !== undefined && !own.isFetching) || (events.isError && events.data !== undefined && !events.isFetching);
  return <View accessibilityLabel="Meu acesso pelos equipamentos" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>Meu acesso pelos equipamentos</Text>
    <Text style={styles.body}>O que os equipamentos informaram nos últimos 7 dias. Sem registro de saída não significa que você continua no condomínio.</Text>
    <Button label="Atualizar equipamentos" disabled={own.isFetching || events.isFetching} onPress={() => { void own.refetch(); void events.refetch(); }} />
    {stale ? <Notice tone="info">Não foi possível atualizar. Os dados abaixo são da última consulta.</Notice> : null}
    {own.isPending ? <ActivityIndicator accessibilityLabel="Consultando suas credenciais" />
      : own.isError && !own.data ? <View style={styles.gap}><Notice tone="error">{message(own.error)}</Notice><Button label="Tentar de novo" onPress={() => void own.refetch()} /></View>
        : own.data.credentials.length === 0 ? <Text style={styles.body}>A gestão ainda não cadastrou cartão ou QR para você.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Minhas credenciais" style={styles.gap}>{own.data.credentials.map((item) =>
            <View key={item.credentialId} style={styles.card} accessibilityLabel={`${item.kind === 'CARD' ? 'Cartão' : 'QR'} ${item.label}, ${statusLabels[item.status]}`}>
              <Text style={styles.cardTitle}>{`${item.kind === 'CARD' ? 'Cartão' : 'QR'} · ${item.label}`}</Text>
              <Text style={styles.body}>{statusLabels[item.status]}</Text>
              {item.validUntil && item.desiredState === 'ACTIVE' ? <Text style={styles.body}>{`Válida até ${formatWhen(item.validUntil, timeZone)} (validade do vínculo)`}</Text> : null}
              {item.devices.map((entry) => <Text key={entry.deviceId} style={styles.body}>{`${entry.deviceLabel}: ${deviceText(item, entry.state)}`}</Text>)}
            </View>)}</View>}
    {events.isPending && !own.isError ? <ActivityIndicator accessibilityLabel="Consultando eventos dos equipamentos" />
      : events.isError && !events.data ? <View style={styles.gap}><Notice tone="error">{message(events.error)}</Notice><Button label="Tentar consultar eventos de novo" onPress={() => void events.refetch()} /></View>
        : !events.data || events.data.events.length === 0 ? <Text style={styles.body}>Nenhum evento dos equipamentos nos últimos 7 dias.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Eventos dos equipamentos" style={styles.gap}>{events.data.events.map((event) =>
            <View key={event.eventId} style={styles.card}>
              <Text style={styles.cardTitle}>{describeEvent(event)}</Text>
              {event.exitRecorded === false ? <Text style={styles.body}>Sem registro de saída</Text> : null}
              <Text style={styles.body}>{[formatWhen(event.occurredAt, timeZone), event.deviceLabel, event.credentialLabel].filter(Boolean).join(' · ')}</Text>
            </View>)}</View>}
    {events.data?.truncated ? <Notice tone="info">A lista mostra os 100 eventos mais recentes.</Notice> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.muted },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  dimmed: { opacity: 0.6 },
  buttonText: { color: colors.navy, fontSize: 16, fontWeight: '700' },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
});
