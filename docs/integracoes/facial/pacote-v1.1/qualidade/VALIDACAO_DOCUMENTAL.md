# Validação documental — CondPulse Acesso Facial v1.1

**Data: 30/10/2026. Escopo: arquivos desta entrega.**

## Resultado

Pacote revisado quanto a coerência documental, integridade dos arquivos estruturados e apresentação dos documentos. Foram concluídas 1.139 verificações estruturais, sem falhas remanescentes no fechamento. Essa contagem é de verificações dos artefatos; não corresponde à execução dos 100 testes de homologação do produto.

| Conjunto | Resultado conferido |
|---|---|
| Manual | 45 páginas; 33 capítulos; DOCX, PDF e Markdown |
| Dossiê | 19 páginas; modelo editável por condomínio; exemplos identificados como sintéticos |
| Pesquisa | 27 registros de modelos/famílias; 69 referências cadastradas, sendo 68 fontes oficiais e o pacote de referência fornecido |
| Testes planejados | HAC-001 a HAC-100; 94 P0 e 6 P1; todos NÃO EXECUTADOS |
| Backlog | AC-001 a AC-035; dependências existentes e sem ciclos; todos PLANEJADOS |
| Dados | 20 entidades, 392 campos expandidos e 103 FKs propostas |
| API proposta | OpenAPI 3.1; 27 caminhos; 32 operações; 62 schemas; 435 referências internas resolvidas |
| Exemplos | Dois JSONs sintéticos coerentes com os exemplos embutidos no contrato |
| Arquivos CSV | Colunas consistentes, UTF-8 com BOM e separador ponto e vírgula |

## Verificações realizadas

- Leitura dos JSONs/YAML, resolução das referências internas e unicidade dos identificadores.
- Correspondência dos 27 modelos entre catálogo e matriz, sem firmware, capacidade ou aprovação física preenchidos artificialmente.
- Rastreabilidade das fontes por ID e validade dos caminhos locais usados nos links Markdown.
- Sequência dos testes e consistência de estados; dependências e critérios do backlog.
- Correspondência entre as 20 entidades do manual e o dicionário; campos sem duplicatas; referências entre entidades de condomínio incluem tenant; FKs para entidades novas possuem chave candidata correspondente.
- Confronto entre manual, API e dados para resultados de decisão, modos de operação, contextos de pessoa, revisão física, publicação parcial e recibos de abertura.
- Inspeção visual de todas as páginas dos dois documentos, com nova inspeção das páginas alteradas. Conferidos sumários, tabelas continuadas, rótulos dos exemplos físicos, diagramas, legibilidade, margens e referências.
- Conferência de existência de texto e links nos PDFs; sumários paginados correspondem aos títulos exportados.

A revisão corrigiu diferenças de nomenclatura e semântica entre artefatos, inclusive a decisão de atendimento/indisponibilidade, o registro de decisão do gateway como telemetria, a revisão única do vínculo físico e a separação entre aceite inicial de publicação e confirmação nos destinos. O dossiê final associa corretamente ponto, origem/destino, leitor, controlador/canal e sensor.

## Alcance da auditoria do contrato

Foram verificados autenticação declarada, IDs de operações, parâmetros, referências, propriedades obrigatórias, schemas de objeto fechados, idempotência das mutações aplicáveis e os exemplos. Além dos dois arquivos, quatro verificações positivas adicionais passaram e 14 payloads negativos foram rejeitados por um verificador restrito aos recursos de JSON Schema utilizados.

Essa auditoria não equivale à certificação por um validador completo de OpenAPI/JSON Schema. Invariantes de negócio — por exemplo, limite máximo de idade maior ou igual ao mínimo, tenant autorizado, vigência, unicidade física e ausência de reenvio de abertura — precisam de implementação e testes no backend/gateway. Declarações no contrato não executam esses controles.

## O que permanece para a homologação real

Nenhum código de produção, migration ou configuração de dispositivo foi executado ou alterado. Não houve acesso a banco operacional, cadastro real de biometria, acionamento de relé, teste de sensores, carga física ou validação de uma instalação concreta.

Os modelos permanecem CANDIDATO_DOCUMENTADO/NAO_HOMOLOGADO. A aplicação das regras exige API/SDK aplicável, combinação técnica identificada, bancada, inventário e testes por ponto, além do aceite central e local previsto no manual. A revisão documental não preenche os campos de resultado observado ou de assinatura desses ensaios.

Os resultados resumidos estão em `validacao_estrutural.json` e `auditoria_contrato.json`. Os hashes dos entregáveis estão no manifesto do pacote.
