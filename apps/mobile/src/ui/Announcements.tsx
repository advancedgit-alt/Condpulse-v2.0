import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi } from '../api/client';
import type { OpenedAnnouncement } from '../api/announcements';
import { formatWhen } from '../time';
import { colors, touch } from './theme';

/**
 * Avisos do condomínio no celular (CP-032). A lista não traz o texto; abrir mostra o texto como texto e registra a
 * primeira abertura com a conta. Abertura não é ciência jurídica nem confirmação de recebimento.
 */
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const hidden = (error: unknown) => error instanceof ApiError && (error.status === 404 && error.code !== 'ANNOUNCEMENT_NOT_FOUND'
  || ['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code));

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}

export function Announcements({ api, accountId, tenantId, unitId, timeZone }: { api: CondPulseApi; accountId: string; tenantId: string; unitId: string; timeZone: string }) {
  const queries = useQueryClient();
  const key = ['private', accountId, 'tenant', tenantId, 'unit', unitId, 'announcements'] as const;
  const list = useQuery({ queryKey: key, queryFn: ({ signal }) => api.unitAnnouncements(tenantId, unitId, signal), retry: (count, error) => !hidden(error) && count < 1,
    refetchInterval: (query) => hidden(query.state.error) ? false : 60_000 });
  const [opened, setOpened] = useState<Record<string, OpenedAnnouncement>>({});
  const [opening, setOpening] = useState<string | null>(null);
  const [failure, setFailure] = useState<{ id: string; error: unknown } | null>(null);
  if (hidden(list.error)) return null;
  async function open(id: string) {
    if (opening) return;
    setOpening(id); setFailure(null);
    try {
      const result = await api.openAnnouncement(tenantId, id);
      setOpened((previous) => ({ ...previous, [id]: result }));
    } catch (error) { setFailure({ id, error }); }
    finally { setOpening(null); void queries.invalidateQueries({ queryKey: key }); }
  }
  const items = list.data?.announcements ?? [];
  const unread = items.filter((item) => item.openedAt === null && !opened[item.announcementId]).length;
  return <View accessibilityLabel="Avisos do condomínio" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>{`Avisos${unread > 0 ? ` · ${unread} não ${unread === 1 ? 'aberto' : 'abertos'}` : ''}`}</Text>
    <Text style={styles.body}>Abrir mostra o texto e registra a abertura com a sua conta. Abertura não é ciência jurídica nem confirmação de recebimento.</Text>
    <Button label="Atualizar avisos" secondary disabled={list.isFetching} onPress={() => void list.refetch()} />
    {list.isError && list.data && !list.isFetching ? <Notice tone="info">Não foi possível atualizar os avisos. A lista é da última consulta.</Notice> : null}
    {list.isPending ? <ActivityIndicator accessibilityLabel="Consultando os avisos" />
      : list.isError && !list.data ? <View style={styles.gap}><Notice tone="error">{message(list.error)}</Notice>
        <Button label="Tentar consultar avisos de novo" secondary onPress={() => void list.refetch()} /></View>
        : items.length === 0 ? <Text style={styles.body}>Os avisos publicados pela gestão para esta unidade aparecem aqui.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Lista de avisos" style={styles.gap}>{items.map((item) => {
            const content = opened[item.announcementId];
            const openedAt = content?.openedAt ?? item.openedAt;
            return <View key={item.announcementId} style={styles.card}>
              <Text style={styles.cardTitle}>{item.title}</Text>
              <Text style={styles.body}>{`${openedAt ? 'Aberto' : 'Não aberto'} · publicado em ${formatWhen(item.publishedAt, timeZone)}${openedAt ? ` · aberto por você em ${formatWhen(openedAt, timeZone)}` : ''}`}</Text>
              {content ? <Text selectable style={styles.text}>{content.body}</Text> : null}
              {failure?.id === item.announcementId ? <Notice tone="error">{message(failure.error)}</Notice> : null}
              {content ? null : <Button label={opening === item.announcementId ? 'Abrindo…' : `Abrir aviso ${item.title}`} disabled={opening !== null}
                onPress={() => void open(item.announcementId)} />}
            </View>;
          })}</View>}
    {list.data?.truncated ? <Notice tone="info">A lista mostra os 100 avisos mais recentes.</Notice> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.muted },
  text: { fontSize: 16, color: colors.text, lineHeight: 22 },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
});
