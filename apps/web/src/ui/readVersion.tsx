import { useState } from 'react';
import { Notice } from './primitives';

/**
 * Formulário de edição versionado: congela a versão do registro que originou o rascunho. Uma nova consulta da lista muda
 * o registro exibido, mas não o rascunho; o envio usa sempre a versão de origem (o servidor recusa se mudou) e, quando a
 * versão no servidor muda, a tela exige revisão explícita: `reload` refaz o rascunho a partir do registro atual.
 */
export function useVersionedDraft(current: number, resetDraft: () => void) {
  const [version, setVersion] = useState(current);
  return {
    version,
    stale: version !== current,
    reload: () => { resetDraft(); setVersion(current); },
  };
}

export function StaleNotice({ onReload }: { onReload(): void }) {
  return <Notice tone="warning">Este cadastro foi alterado por outra pessoa enquanto o formulário estava aberto. <button className="text-button" type="button"
    onClick={onReload}>Recarregar os dados atuais</button> e confira antes de enviar.</Notice>;
}
