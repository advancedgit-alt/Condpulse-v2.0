# Arquivos privados (CP-017) — anexos de pedido

Primeiro uso do módulo de arquivos: foto ou PDF anexado a um pedido da unidade (parte de anexo do CP-033). Fica atrás
de `CP_FILES_ENABLED=false`. Ligar em homologação depende de uma chave de serviço do Storage na Vercel
(`CP_STORAGE_SERVICE_KEY`), que só o responsável pode autorizar e cadastrar — ver "Bloqueios" em `docs/STATUS.md`.

## Fluxo

1. `POST /v1/tenants/{t}/uploads/intents` com `purpose=REQUEST_ATTACHMENT`, pedido, nome, tipo e tamanho declarados.
   A API confere o pedido (visível para quem pede e não encerrado), grava a linha `PENDING` em `cp.attachments` e pede
   ao Storage uma URL assinada de envio para o caminho `{condomínio}/{anexo}` no bucket privado `cp-upload-quarantine`.
   O nome enviado pelo usuário nunca vira caminho.
2. O navegador envia o arquivo direto ao Storage (`PUT`), sem cookie nem cabeçalho do CondPulse. A CSP da Vercel libera
   `connect-src` só para `https://rpnrqfrfwywglvyykgrg.supabase.co` (homologação); nenhum curinga.
3. `POST /attachments/{id}/complete`: a API lê o objeto no Storage com a chave de serviço (só no servidor), confere
   tamanho, assinatura do conteúdo (PNG, JPEG ou `%PDF-`) e calcula o SHA-256. Válido vira `AVAILABLE`; tamanho
   diferente vira `REJECTED/SIZE_MISMATCH`; conteúdo de outro tipo vira `REJECTED/TYPE_MISMATCH`; objeto ausente depois
   do prazo vira `REJECTED/UPLOAD_EXPIRED`. Repetir não muda nada.
4. `POST /attachments/{id}/download`: revalida o acesso ao pedido, grava trilha `ATTACHMENT_DOWNLOADED` e devolve link de
   60 segundos. Não existe URL permanente.
5. `GET /requests/{id}/attachments`: liberados do pedido; pendentes e recusados só para quem enviou.

As chamadas ao Storage nunca ficam dentro da transação do banco.

## Regras no banco (V019)

- RLS com `FORCE`; `cp_api` só tem `SELECT`, `INSERT` das colunas declaradas e `UPDATE(state,rejection,sha256)`. Sem
  `DELETE`.
- Leitura exige o pedido visível pelas políticas do próprio pedido (unidade com vínculo vigente ou gestão com
  `requests.manage` e AAL2). Inserção só em nome da própria conta e em pedido não encerrado. Conclusão só por quem enviou.
- Gatilho: nasce `PENDING` com prazo de 15 minutos dado pelo banco; chave do objeto derivada do condomínio e do anexo;
  no máximo 10 anexos liberados ou ainda concluíveis por pedido, em série pela trava do pedido (pendente vencido não ocupa
  vaga); depois só `PENDING -> AVAILABLE` (com hash, até 5 minutos após o prazo) ou `PENDING -> REJECTED` (com motivo).
- Trilha `ATTACHMENT_%` só em nome da própria conta e de anexo visível.

Limites: JPEG/PNG até 10 MB, PDF até 20 MB (o bucket também limita a 20 MB e aos três tipos).

## Testes

- `tests/database/run.py` (`attachment_checks`): grants, RLS, transições, prazo, limite, trilha.
- `AttachmentJdbcTest`: fluxo com PostgreSQL real e Storage em memória (arquivo disfarçado, tamanho diferente, outra
  unidade, outro condomínio, gestão sem AAL2, vínculo revogado, pedido encerrado, limite, Storage fora).
- `SupabaseAttachmentStorageTest`: caminhos, cabeçalhos e URLs assinadas aceitas/recusadas contra servidor local.
- `tests/runtime/files-flag-smoke.sh`: API sobe com `CP_FILES_ENABLED=true` e rotas de anexo exigem sessão.
- Web: `src/api/attachments.test.ts` e `e2e/attachments.spec.ts` (enviar, recusar tipo/tamanho, falha no Storage,
  conferir envio, limite, baixar, gestão, pedido encerrado, módulo desligado).

## Pendências

- Ligar em homologação: cadastrar `CP_STORAGE_SERVICE_KEY` na Vercel `condpulse` (OK do responsável) e `CP_FILES_ENABLED`.
- Antivírus/varredura de conteúdo não existe; a quarentena é só conferência de tipo e tamanho.
- Produção: CSP e chave próprias; nada configurado.
- Anexo de importação (arquivo original do CP-016) e de encomenda ainda não usam este módulo.
