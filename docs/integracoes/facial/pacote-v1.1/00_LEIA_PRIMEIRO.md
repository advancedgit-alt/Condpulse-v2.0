# CondPulse — integração e homologação de acesso facial

**Pacote documental v1.1 · 30/10/2026**

Esta entrega organiza a evolução do CondPulse para acesso facial e outros meios de identificação por condomínio, área, passagem e sentido. A pesquisa cobre Hikvision, Control iD, Dahua Technology e Intelbras, com fontes oficiais, diferenças de modelos e requisitos de validação em bancada e no local.

## Estado da entrega

| Frente | Situação |
|---|---|
| Pesquisa e especificação | Concluídas nesta versão documental |
| Catálogo | 27 registros de modelos/famílias candidatos; nenhum equipamento homologado por esta pesquisa |
| Integração com a base CondPulse | Extensão proposta do pacote v1.0 fornecido; comparar com o repositório e o schema atuais |
| Modelo de dados e API | Contratos propostos; nenhum banco ou serviço de produção alterado |
| Homologação | 100 casos planejados, todos inicialmente NÃO EXECUTADOS |
| Implantação | Dossiê reutilizável a preencher para cada condomínio real |
| Implementação | 35 itens de backlog planejados, com dependências e critérios de aceite |

**A documentação permite iniciar a implementação e os ensaios. Ela não comprova funcionamento físico de equipamentos, não autoriza marcar candidatos como homologados e não substitui o comissionamento de cada ponto de acesso.**

## Onde começar

1. Leia o [manual principal em PDF](documentacao/CondPulse_Acesso_Facial_v1.1.pdf). São 45 páginas com arquitetura, regras, responsabilidades, integração, pesquisa por fabricante e critérios de aceite.
2. Use o [manual editável em Word](documentacao/CondPulse_Acesso_Facial_v1.1.docx) ou a [fonte Markdown](documentacao/CondPulse_Acesso_Facial_v1.1.md) para revisões futuras.
3. Para desenvolvimento, siga o [handoff](HANDOFF_CLAUDE_CODE.md), o [backlog](planejamento/backlog.json), o [modelo de dados](dados/Extensao_Modelo_Dados.md) e o [OpenAPI proposto](contratos/openapi_acesso_proposto.yaml).
4. Para cada implantação, duplique o [dossiê editável](implantacao/Dossie_Condominio_Template.docx), use a [versão PDF](implantacao/Dossie_Condominio_Template.pdf) como referência e preencha os dois CSVs de levantamento.
5. Para homologar, execute os [casos de teste](homologacao/testes_homologacao.csv), anexe evidências por combinação técnica e preencha o [termo central](homologacao/Termo_Homologacao_Tecnica.md). Em seguida conclua o aceite local do dossiê.

## Estrutura do pacote

| Pasta ou arquivo | Uso |
|---|---|
| `documentacao/` | Manual principal em PDF, DOCX e Markdown |
| `catalogo/` | Modelos candidatos em JSON/CSV, matriz de homologação por modelo e fontes oficiais |
| `contratos/` | OpenAPI 3.1 proposto do CondPulse e exemplos sintéticos; não são APIs de fabricantes |
| `dados/` | Dicionário e modelo lógico de 20 entidades, relações, índices e invariantes; sem SQL executável |
| `homologacao/` | 100 testes em JSON/CSV e termo de aprovação técnica do MASTER da plataforma |
| `implantacao/` | Dossiê de 19 páginas em PDF/DOCX/Markdown, inventário técnico e matriz de áreas |
| `planejamento/` | 35 itens AC-001 a AC-035 em JSON/CSV |
| `diagramas/` | Arquitetura e mapa de áreas em PNG e SVG |
| `qualidade/` | Resultado da validação documental desta entrega |
| `HANDOFF_CLAUDE_CODE.md` | Instruções de implementação incremental sobre o projeto existente |
| `manifesto_arquivos.json` | Tamanho e SHA-256 dos arquivos do pacote, exceto o próprio manifesto |

## Responsabilidades definidas

O **MASTER CondPulse** homologa a combinação de fabricante, SKU, hardware, região, firmware, API/SDK, adaptador, modo e topologia. O **MASTER de implantação do condomínio** instala e comissiona os pontos daquele tenant usando perfis aprovados. O **gestor** administra pessoas, áreas e regras locais dentro das capacidades liberadas. A gestão de regras não concede leitura de senhas, acesso ao cofre, atualização de firmware ou alteração de canais e circuitos.

Cadastrar uma face não concede acesso geral. Uma pessoa precisa de autorização válida para o ponto, sentido e contexto da tentativa. A regra deve impedir a atuação antes do relé; um webhook recebido depois da abertura não cumpre essa função. Idade é apurada por evidência cadastral validada, nunca estimada pela imagem.

## Uso dos arquivos estruturados

- Os arquivos JSON usam UTF-8. O catálogo é uma base de candidatos documentados, com campos de homologação intencionalmente vazios.
- Os CSVs usam UTF-8 com BOM e separador ponto e vírgula. As planilhas de implantação contêm uma linha EXEMPLO, que deve ser removida antes do preenchimento real.
- Os exemplos de contrato são sintéticos. Não contêm credenciais reais nem concedem autorização de abertura.
- A numeração, os estados e os nomes do contrato precisam ser integrados ao padrão existente do repositório. Os mapeamentos entre API e modelo relacional estão documentados nos anexos.
- Os campos PENDENTE são pontos de levantamento e decisão local. Não preencher com valores presumidos para produzir um aceite artificial.
- Preservar o histórico de revogações, versões e evidências em toda migração ou retorno de versão.

## Fontes e atualização

As referências principais estão no capítulo 33 do manual e em `catalogo/fontes_oficiais.json`. O anexo de dados também identifica referências oficiais de banco. Capacidade, disponibilidade comercial, suporte, licenças e contratos variam por revisão; conflitos encontrados foram mantidos como pendências de confirmação. A seleção não é um ranking de vendas.

O pacote contém análise e referências; SDKs proprietários, firmware, documentos de identidade, fotografias faciais e segredos não são redistribuídos. A implantação exige o material oficial aplicável à combinação adquirida e a autorização do condomínio para seus próprios dados e regras.

## Critério de encerramento de uma homologação real

Concluir os gates de documentação, domínio, integração, bancada, comissionamento, piloto e aceite. Registrar o resultado observado, a evidência, o executor, a data e as versões. Um P0 falho ou sem evidência impede aprovação da função afetada. A assinatura técnica é do MASTER competente; a aprovação das regras é do gestor do condomínio. São decisões complementares.
