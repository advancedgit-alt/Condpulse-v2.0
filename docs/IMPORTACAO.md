# Importação de unidades em staging — CP-016

Recorte entregue: **blocos e unidades**. Pessoas, vínculos e permissões não são
importados; continuam nascendo de convite revisado (PRD, seções 9 e 27). Fica atrás
de `CP_MANAGEMENT_ENABLED`, com a capacidade `units.manage` e AAL2 no serviço e no banco.

## Fluxo

1. A gestão envia um CSV (até 256 KB e 2.000 linhas) pela tela **Gestão → Importação**
   ou por `POST /v1/tenants/{tenantId}/management/imports` com `{fileName, content}`.
2. O servidor calcula o SHA-256 do conteúdo, aplica o mapa de colunas versão 1 e grava
   só a prévia: lote (`cp.import_batches`) e resultado de cada linha (`cp.import_rows`).
   Nenhuma unidade ou bloco é criado nesse passo.
3. A prévia mostra cada linha: nova unidade, já existente (não será criada), com erro
   (motivo) ou repetida no próprio arquivo.
4. **Aprovar** cria, numa transação, só os blocos e unidades que faltam (comparando sem
   diferenciar caixa e espaços), liga cada linha válida à unidade, grava as contagens e
   a trilha (`BLOCK_CREATED`, `UNIT_CREATED` com o lote no motivo e
   `IMPORT_BATCH_APPROVED`/`IMPORT_BATCH_APPROVED_PARTIAL`).
5. Lote com linha inválida ou repetida só é aprovado com `acceptPartial=true`
   (caixa "Aprovar só as linhas válidas"): não existe parcial silencioso.
6. **Descartar** encerra a prévia sem cadastrar nada; o histórico continua visível.

## Mapa de colunas versão 1

Cabeçalho obrigatório na primeira linha, separado por `;` ou `,` (o do cabeçalho vale
para o arquivo), aspas duplas com aspas dobradas, BOM UTF-8 aceito:

| Coluna | Obrigatória | Limite |
| --- | --- | --- |
| `unidade` | sim | 40 caracteres |
| `bloco` | não | 60 caracteres; vazio = sem bloco |
| `codigo_externo` | não | 80 caracteres; guardado na linha, não na unidade |

Erros por linha: `UNIT_LABEL_REQUIRED`, `UNIT_LABEL_TOO_LONG`, `BLOCK_NAME_TOO_LONG`,
`EXTERNAL_ID_TOO_LONG`, `IMPORT_ROW_COLUMNS_INVALID`, `IMPORT_ROW_QUOTES_INVALID`,
`IMPORT_ROW_CONTROL_CHARACTER`. Erros do arquivo inteiro (nada é gravado):
`IMPORT_FILE_EMPTY`, `IMPORT_FILE_TOO_LARGE`, `IMPORT_TOO_MANY_ROWS`,
`IMPORT_HEADER_INVALID`, `IMPORT_FILE_NAME_INVALID`.

## Garantias (V018)

- Mesmo conteúdo, mesmo lote: índice único parcial por condomínio e hash enquanto o
  lote não é descartado. Reenviar devolve o lote existente (200, `Import-Batch-Created:
  false`). Repetir a aprovação devolve o lote aprovado sem criar nada
  (`Import-Decision-Applied: false`). Arquivo novo com as mesmas unidades aprova com
  todas como "já existia".
- Aprovações simultâneas: a primeira trava o lote (`FOR UPDATE`); as outras encontram
  o lote aprovado.
- O banco confere, sob a trava, que as linhas gravadas batem com os totais e que toda
  linha válida foi aplicada; lote decidido não muda nem recebe linha; nada é apagado.
- Autoria, horários, estado inicial e versão vêm do banco. Só `units.manage` lê ou
  altera lotes; outro condomínio não vê o lote nem pelo ID. `DatabaseRoleGuard` recusa
  grants a mais nas duas tabelas.

## Limites conhecidos

- O arquivo original não é guardado: falta o Storage privado (CP-017). Ficam o hash,
  o nome e os campos normalizados de cada linha.
- Unidade existente inativa conta como "já existia" e não é reativada pela importação.
- Paginação além de 2.000 linhas por lote e dos 50 lotes recentes não existe.
- Homologação: V018 ainda não aplicada; aplicar pelo executor protegido antes de usar.
