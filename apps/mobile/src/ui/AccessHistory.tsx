import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { ApiError, type CondPulseApi } from '../api/client';
import type { AccessRecord } from '../api/accessHistory';
import { formatWhen } from '../time';
import { colors, touch } from './theme';

/**
 * Entradas e saídas da unidade no celular (CP-028): os últimos 7 dias do que a portaria registrou manualmente (nunca
 * "passagem pelo equipamento"). Sem registro de saída não quer dizer que a pessoa ainda esteja no condomínio.
 * Dependente sem permissão de decidir visitas, função desligada ou vínculo que mudou: a seção some.
 */
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const hidden = (error: unknown) => error instanceof ApiError && (['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED', 'VISIT_ACCESS_DENIED'].includes(error.code)
  || (error.status === 404 && error.code === 'HTTP_ERROR'));

function Button({ label, onPress, disabled }: { label: string; onPress(): void; disabled?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, (disabled || pressed) && styles.dimmed]}>
    <Text style={styles.buttonText}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}
function status(item: AccessRecord): string {
  if (item.correction) return 'Registro corrigido';
  return item.exit !== null && item.exit.correction === null ? 'Saída registrada' : 'Sem registro de saída';
}

export function AccessHistory({ api, accountId, tenantId, unitId, timeZone }: { api: CondPulseApi; accountId: string; tenantId: string; unitId: string; timeZone: string }) {
  // Dentro do escopo de visitas: o aviso de chegada do canal da unidade e o "Atualizar acessos" também atualizam o histórico.
  const records = useQuery({ queryKey: ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'visits', 'access'],
    queryFn: ({ signal }) => api.unitAccessRecords(tenantId, unitId, signal), retry: (count, error) => !hidden(error) && count < 1 });
  if (hidden(records.error)) return null;
  return <View accessibilityLabel="Entradas e saídas" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>Entradas e saídas</Text>
    <Text style={styles.body}>Registros manuais da portaria nos últimos 7 dias. Sem registro de saída não quer dizer que a pessoa ainda esteja no condomínio.</Text>
    <Button label="Atualizar entradas e saídas" disabled={records.isFetching} onPress={() => void records.refetch()} />
    {records.isPending ? <ActivityIndicator accessibilityLabel="Consultando entradas e saídas" />
      : records.isError && !records.data ? <View style={styles.gap}><Notice tone="error">{message(records.error)}</Notice>
        <Button label="Tentar consultar entradas de novo" onPress={() => void records.refetch()} /></View>
        : !records.data || records.data.records.length === 0 ? <Text style={styles.body}>Nenhum registro. Entradas e saídas de visitantes registradas pela portaria aparecem aqui por 7 dias.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Entradas e saídas registradas" style={styles.gap}>{records.data.records.map((item) =>
            <View key={item.entryId} style={styles.card} accessibilityLabel={`${item.visitorName}, ${status(item)}`}>
              <Text style={styles.cardTitle}>{`${item.visitorName} · ${status(item)}`}</Text>
              <Text style={styles.body}>{`Entrada registrada pela portaria em ${formatWhen(item.enteredAt, timeZone)} · ${item.viaArrival ? 'chegada autorizada pela unidade' : 'convite da unidade'}`}</Text>
              {item.correction ? <Text style={styles.body}>{`Corrigido pela portaria: ${item.correction.reason}`}</Text> : null}
              {item.exit ? <Text style={styles.body}>{`Saída registrada pela portaria em ${formatWhen(item.exit.exitedAt, timeZone)}${item.exit.correction ? ` (corrigida: ${item.exit.correction.reason})` : ''}`}</Text> : null}
            </View>)}</View>}
    {records.data && records.isError ? <Notice tone="error">{`${message(records.error)} A lista mostra a última consulta.`}</Notice> : null}
    {records.data?.truncated ? <Notice tone="info">O histórico mostra as 100 entradas mais recentes.</Notice> : null}
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
