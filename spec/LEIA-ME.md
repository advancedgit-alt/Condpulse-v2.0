# spec/ — pacote de especificação CondPulse v1.0

Referência de produto recebida em `CondPulse_Pacote_Completo_v1.0.zip`
(27/10/2026). Leitura inicial: [README.md](README.md),
[PRD em Markdown](documentacao/CondPulse_PRD_Arquitetura_v1.0.md) e
[handoff](handoff/PROMPT_INICIAL.md).

## Integridade

- Os arquivos deste diretório são cópias byte a byte do pacote original.
  `SHA256SUMS_pacote_original.txt` é o manifesto do ZIP;
  `tests/spec-package.test.mjs` confere cada arquivo presente.
- Não incluídos no repositório: PDF e DOCX do PRD (mesmo texto do Markdown),
  `referencias/Pesquisa_Concorrentes_26-10-2026.pdf` e a proposta de origem
  marcada como superada. Permanecem no ZIP do responsável.
- Este diretório não é editado para registrar progresso.

## Onde está o estado real

| Assunto | Fonte viva |
| --- | --- |
| Tickets e evidências | `docs/backlog.json` |
| Estado verificável | `docs/STATUS.md` |
| Contrato HTTP implementado | `contracts/openapi.json` |
| Schema executado | `db/migrations` (Flyway) |
| Cenários de aceite | `tests/acceptance-catalog.json` |

`api/openapi.yaml`, `banco/*.sql` e `planejamento/backlog.json` daqui são a
especificação de referência. Não comprovam implementação nem substituem as
migrations revisadas.

## Validação documental

`python3 spec/qualidade/validar_estrutura.py` reescreve
`qualidade/RELATORIO_VALIDACAO.md`. O CI executa o script sobre uma cópia
temporária para manter o relatório original intacto. Requer PyYAML 6.
