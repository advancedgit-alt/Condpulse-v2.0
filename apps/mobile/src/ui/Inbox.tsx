import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, type CondPulseApi } from '../api/client';
import type { InboxNotification } from '../api/notifications';
import { formatWhen } from '../time';
import { colors, touch } from './theme';

/**
 * Caixa da unidade no celular (parte do CP-035): avisos gerados pelo worker para quem pode agir na unidade. Aparecer
 * aqui não é leitura; só "Abrir" registra a abertura, com horário do servidor. O aviso não traz o nome do visitante nem
 * a descrição: depois de abrir, a pessoa vê o detalhe na seção de visitas, de encomendas ou na fila de espera, com as próprias permissões.
 * Sem notificação no aparelho neste recorte.
 */
const kindText: Record<InboxNotification['kind'], string> = {
  ARRIVAL_WAITING: 'Chegada aguardando resposta da unidade', PARCEL_RECEIVED: 'Encomenda recebida na portaria',
  WAITLIST_OFFERED: 'Vaga da fila de espera',
};
const resourceTexts: Record<InboxNotification['kind'], Record<string, string>> = {
  ARRIVAL_WAITING: {
    WAITING: 'Situação atual: aguardando resposta.', APPROVED: 'Situação atual: entrada autorizada pela unidade.',
    DENIED: 'Situação atual: entrada recusada pela unidade.', EXPIRED: 'Situação atual: prazo de resposta encerrado; nada foi liberado.',
    CANCELLED: 'Situação atual: chegada encerrada pela portaria.',
  },
  PARCEL_RECEIVED: { RECEIVED: 'Situação atual: aguardando retirada.', DELIVERED: 'Situação atual: retirada registrada.' },
  WAITLIST_OFFERED: {
    OFFERED: 'Situação atual: oferta aguardando o seu aceite, com prazo.', ACCEPTED: 'Situação atual: oferta aceita.',
    LEFT: 'Situação atual: você saiu da fila ou recusou a oferta.', EXPIRED: 'Situação atual: prazo da oferta encerrado; a vaga seguiu na fila.',
    CLOSED: 'Situação atual: o período começou e a entrada foi encerrada.',
  },
};
const opened: Record<InboxNotification['kind'], string> = {
  ARRIVAL_WAITING: 'Aviso aberto. A chegada aparece em “Visitas”, nesta tela, para responder.',
  PARCEL_RECEIVED: 'Aviso aberto. A encomenda aparece em “Encomendas”, nesta tela.',
  WAITLIST_OFFERED: 'Aviso aberto. A oferta aparece em “Fila de espera”, nas reservas desta tela, para aceitar no prazo.',
};
const section: Record<InboxNotification['kind'], string> = { ARRIVAL_WAITING: 'visits', PARCEL_RECEIVED: 'parcels', WAITLIST_OFFERED: 'waitlist' };
const message = (error: unknown) => error instanceof ApiError ? error.message : 'Não foi possível concluir agora. Tente novamente.';
const uncertain = (error: unknown) => error instanceof ApiError && error.uncertain;
/** Recurso desligado ou vínculo que mudou: a caixa some sem erro técnico. */
const hidden = (error: unknown) => error instanceof ApiError && (['UNIT_ACCESS_DENIED', 'TENANT_ACCESS_DENIED'].includes(error.code)
  || (error.status === 404 && error.code === 'HTTP_ERROR'));

function Button({ label, onPress, disabled, secondary }: { label: string; onPress(): void; disabled?: boolean; secondary?: boolean }) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: !!disabled }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, secondary && styles.secondary, (disabled || pressed) && styles.dimmed]}>
    <Text style={[styles.buttonText, secondary && styles.secondaryText]}>{label}</Text>
  </Pressable>;
}
function Notice({ tone, children }: { tone: 'error' | 'info' | 'success'; children: string }) {
  return <Text accessibilityRole="alert" style={[styles.notice, styles[tone]]}>{children}</Text>;
}

export function Inbox({ api, accountId, tenantId, unitId, timeZone }: { api: CondPulseApi; accountId: string; tenantId: string; unitId: string; timeZone: string }) {
  const queries = useQueryClient();
  const scope = ['private', accountId, 'tenant', tenantId, 'unit', unitId] as const;
  const key = [...scope, 'inbox'] as const;
  const inbox = useQuery({ queryKey: key, queryFn: ({ signal }) => api.unitInbox(tenantId, unitId, signal), retry: (count, error) => !hidden(error) && count < 1,
    refetchInterval: (query) => hidden(query.state.error) ? false : 30_000 });
  const [opening, setOpening] = useState<string | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [outcome, setOutcome] = useState<string | null>(null);
  if (hidden(inbox.error)) return null;
  async function open(item: InboxNotification) {
    if (opening) return;
    setOpening(item.notificationId); setFailure(null); setOutcome(null);
    try {
      await api.openNotification(tenantId, item.notificationId);
      // O detalhe fica na seção da própria unidade, consultado de novo com as permissões de quem abriu.
      void queries.invalidateQueries({ queryKey: [...scope, section[item.kind]] });
      setOutcome(opened[item.kind]);
    } catch (error) { setFailure(error); }
    finally { setOpening(null); void queries.invalidateQueries({ queryKey: key }); }
  }
  const unread = inbox.data?.unread ?? 0;
  return <View accessibilityLabel="Caixa da unidade" style={styles.section}>
    <Text accessibilityRole="header" style={styles.title}>{`Caixa da unidade${unread > 0 ? ` · ${unread} ${unread === 1 ? 'novo' : 'novos'}` : ''}`}</Text>
    <Text style={styles.body}>Avisos da portaria para quem pode agir nesta unidade. Aparecer aqui não quer dizer que alguém leu: só abrir o aviso registra a leitura. Ainda não há notificação no aparelho.</Text>
    <Button label="Atualizar caixa" secondary disabled={inbox.isFetching} onPress={() => void inbox.refetch()} />
    {outcome ? <Notice tone="success">{outcome}</Notice> : null}
    {failure ? <Notice tone="error">{`${message(failure)}${uncertain(failure) ? ' Atualize e confira antes de repetir.' : ''}`}</Notice> : null}
    {inbox.isPending ? <ActivityIndicator accessibilityLabel="Consultando os avisos da unidade" />
      : inbox.isError && !inbox.data ? <View style={styles.gap}><Notice tone="error">{message(inbox.error)}</Notice>
        <Button label="Tentar consultar a caixa de novo" secondary onPress={() => void inbox.refetch()} /></View>
        : !inbox.data || inbox.data.items.length === 0 ? <Text style={styles.body}>Nenhum aviso. Chegadas que pedem a sua resposta e encomendas recebidas pela portaria aparecem aqui.</Text>
          : <View accessibilityRole="list" accessibilityLabel="Avisos da caixa" style={styles.gap}>{inbox.data.items.map((item) =>
            <View key={item.notificationId} style={styles.card} accessibilityLabel={`${kindText[item.kind]}, ${item.state === 'AVAILABLE' ? 'não aberto' : 'aberto'}`}>
              <Text style={styles.cardTitle}>{`${kindText[item.kind]} · ${item.state === 'AVAILABLE' ? 'Não aberto' : 'Aberto'}`}</Text>
              <Text style={styles.body}>{`Gerado em ${formatWhen(item.createdAt, timeZone)}${item.readAt ? ` · aberto por você em ${formatWhen(item.readAt, timeZone)}` : ''}`}</Text>
              <Text style={styles.body}>{item.resourceState ? resourceTexts[item.kind][item.resourceState]! : 'Este registro não está mais disponível para o seu vínculo.'}</Text>
              <Button label={opening === item.notificationId ? 'Abrindo…' : `${item.state === 'AVAILABLE' ? 'Abrir' : 'Abrir de novo'}: ${kindText[item.kind]}, ${formatWhen(item.createdAt, timeZone)}`}
                disabled={opening !== null} onPress={() => void open(item)} />
            </View>)}</View>}
    {inbox.data && inbox.isError ? <Notice tone="error">{`${message(inbox.error)} A caixa mostra a última consulta.`}</Notice> : null}
  </View>;
}

const styles = StyleSheet.create({
  section: { gap: 12, marginTop: 16 },
  gap: { gap: 10 },
  title: { fontSize: 20, fontWeight: '700', color: colors.navy },
  body: { fontSize: 15, color: colors.muted },
  button: { minHeight: touch, borderRadius: 10, backgroundColor: colors.teal, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  secondary: { backgroundColor: colors.surface, borderWidth: 1, borderColor: colors.line },
  dimmed: { opacity: 0.6 },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700', textAlign: 'center' },
  secondaryText: { color: colors.navy },
  notice: { padding: 12, borderRadius: 10, fontSize: 15 },
  error: { backgroundColor: colors.errorBg, color: colors.error },
  info: { backgroundColor: colors.infoBg, color: colors.text },
  success: { backgroundColor: colors.infoBg, color: colors.navy },
  card: { padding: 16, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.surface, gap: 6 },
  cardTitle: { fontSize: 17, fontWeight: '700', color: colors.navy },
});
