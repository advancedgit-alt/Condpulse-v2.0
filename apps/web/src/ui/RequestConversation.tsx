import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { RequestMessage, RequestMessagePage } from '../api/requests';
import { ErrorState, Notice } from './primitives';

interface Props {
  messages: RequestMessage[];
  truncated: boolean;
  load(before: string): Promise<RequestMessagePage>;
  render(message: RequestMessage): ReactNode;
}

/**
 * Conversa do pedido: o detalhe traz as mensagens mais recentes; "Carregar mensagens anteriores" busca as páginas
 * anteriores pela mensagem mais antiga já exibida, sem repetir mensagem. Quando o detalhe muda de janela (mensagem nova
 * empurra a mais antiga), o histórico carregado recomeça.
 */
export function RequestConversation(props: Props) {
  return <ConversationHistory key={props.messages[0]?.messageId ?? 'vazia'} {...props} />;
}

function ConversationHistory({ messages, truncated, load, render }: Props) {
  const [older, setOlder] = useState<RequestMessage[]>([]);
  const [more, setMore] = useState(truncated);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const all = [...older, ...messages];
  async function loadMore() {
    const before = all[0]?.messageId;
    if (!before || busy) return;
    setBusy(true); setFailure(null);
    try {
      const page = await load(before);
      if (!mounted.current) return;
      setOlder((previous) => {
        const seen = new Set([...previous, ...messages].map((message) => message.messageId));
        return [...page.messages.filter((message) => !seen.has(message.messageId)), ...previous];
      });
      setMore(page.truncated);
    } catch (error) {
      if (mounted.current) setFailure(error);
    } finally {
      if (mounted.current) setBusy(false);
    }
  }
  return <>
    {more ? <div className="conversation-history">
      <Notice tone="info">{older.length ? 'Há mensagens ainda mais antigas.' : 'A conversa mostra as mensagens mais recentes.'}</Notice>
      {failure ? <ErrorState error={failure} retry={() => void loadMore()} />
        : <div className="button-row"><button className="button button-secondary button-compact" type="button" disabled={busy}
          onClick={() => void loadMore()}>{busy ? 'Carregando…' : 'Carregar mensagens anteriores'}</button></div>}
    </div> : null}
    {all.length === 0 ? <p className="field-help">Nenhuma mensagem ainda.</p>
      : <ol className="request-messages" aria-label="Conversa do pedido" tabIndex={0}>{all.map(render)}</ol>}
  </>;
}
