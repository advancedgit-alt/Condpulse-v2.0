# Avisos do condomínio (CP-032, sem anexos nem documentos)

Flag: `CP_ANNOUNCEMENTS_ENABLED` (padrão `false`; ligada na homologação em 29/10/2026). V016 aplicada na homologação
(`rpnrqfrfwywglvyykgrg`) em 29/10/2026; produção ainda sem migrations. Ver [STATUS.md](STATUS.md).
Documentos, anexos privados e upload (CP-017) ficam para tickets próprios.

## Quem faz o quê

| Pessoa | Pode | Não pode |
| --- | --- | --- |
| Gestão (`MANAGER`, capacidade `announcements.publish` com AAL2) | Criar rascunho (chave `Idempotency-Key`), editar o rascunho pela versão lida, publicar para o condomínio inteiro, blocos ou unidades, arquivar o publicado, descartar o rascunho, ver a contagem de aberturas | Alterar texto ou público depois de publicado (arquivar e publicar outro), apagar aviso ou abertura, escolher datas, autoria de publicação ou horário de abertura |
| Unidade (morador, responsável, dependente ou proprietário com vínculo vigente em unidade ativa) | Ver os avisos publicados para o próprio público (lista sem o texto) e abrir cada um (texto e registro da primeira abertura) | Ver rascunho ou arquivado, mesmo pelo ID (AC-029); registrar abertura em nome de outra pessoa ou de aviso fora do próprio público |

O proprietário está no público pelo banco e pela API; a tela inicial ainda atende somente vínculos residenciais.

## Rotas

| Operação | Rota |
| --- | --- |
| Avisos da gestão (até 100, com contagem de aberturas) | `GET /v1/tenants/{tenantId}/management/announcements` |
| Criar rascunho | `POST /v1/tenants/{tenantId}/management/announcements` |
| Editar rascunho | `PUT /v1/tenants/{tenantId}/management/announcements/{announcementId}` |
| Publicar | `POST /v1/tenants/{tenantId}/management/announcements/{announcementId}/publish` |
| Arquivar ou descartar | `POST /v1/tenants/{tenantId}/management/announcements/{announcementId}/archive` |
| Avisos da unidade (sem o texto) | `GET /v1/tenants/{tenantId}/units/{unitId}/announcements` |
| Abrir (texto e registro da abertura) | `POST /v1/tenants/{tenantId}/announcements/{announcementId}/open` |

## Regras

- **Texto simples**: título de 3 a 160 caracteres e texto de até 5.000, quebras de linha mantidas (no máximo uma linha em
  branco seguida) e caracteres invisíveis recusados, com a mesma regra no Java e na web. A tela exibe como texto (nunca
  interpreta marcação) e orienta a não publicar inadimplência, documentos pessoais ou dados sensíveis.
- **Público**: condomínio inteiro, ou de 1 a 200 blocos ou unidades do próprio condomínio, sem repetição; conferido no
  serviço e no gatilho do banco. Vínculo encerrado, conta inativa ou unidade inativa deixam de ver o aviso.
- **Estados**: `DRAFT` → `PUBLISHED` (autoria e horário pelo banco) → `ARCHIVED`; rascunho pode ser descartado
  (`ARCHIVED` sem publicação). Publicado não volta a rascunho e não muda de texto nem de público. Toda alteração usa a
  versão lida (+1).
- **Abertura não é ciência jurídica**: a lista da unidade não traz o texto; a abertura autenticada devolve o texto e
  grava uma linha por conta e aviso, com horário do banco (repetir não muda o horário). A gestão vê somente a contagem.
- **Banco (V016)**: `cp.announcements` e `cp.announcement_reads` com RLS (gestão por capacidade; unidade pelo público e
  pelo vínculo vigente, em `cp.announcement_audience_member`), grants por coluna, gatilho de transições e alvos, eventos
  `ANNOUNCEMENT_PUBLISHED`/`ANNOUNCEMENT_ARCHIVED` no outbox e trilha `ANNOUNCEMENT_*` somente pela gestão, em nome da
  própria conta. O worker trata esses eventos como sem consumidor (`SKIPPED`) neste recorte. A guarda de inicialização
  recusa runtime com `DELETE` em avisos ou aberturas ou com grant nas colunas preenchidas pelo banco.

## Telas web

- Gestão, seção Avisos: lista com estado, público, horários e aberturas registradas; rascunho novo com envio incerto
  preso à mesma chave mesmo ao fechar e reabrir; edição do rascunho; publicação com confirmação do público;
  arquivamento e descarte com motivo opcional na auditoria.
- Morador, Início: seção "Avisos" com os publicados para a unidade, "Não aberto"/"Aberto", e o texto só depois de abrir.

## Pendências

- Ensaio com Auth real e gestão nomeada; aplicação na produção pelo executor próprio.
- Anexos e documentos (CP-017), aviso na caixa interna e push (CP-035), tempo real (CP-036).
- Tela do proprietário, paginação além dos 100 mais recentes e retenção dos avisos e aberturas.
