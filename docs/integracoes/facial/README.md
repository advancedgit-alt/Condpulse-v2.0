# Integração facial — acesso físico por condomínio, área, passagem e sentido

**Estado em 31/10/2026: documentação recebida; nada implementado nem homologado.**

O responsável entregou em 31/10/2026 o pacote documental *CondPulse — Acesso Facial v1.1* (datado de 30/10/2026),
que cobre Hikvision, Control iD, Dahua e Intelbras. A cópia está em [`pacote-v1.1/`](pacote-v1.1/), na estrutura
original. A auditoria contra o repositório (item AC-001 do backlog do pacote) está em
[`AUDITORIA_AC001.md`](AUDITORIA_AC001.md).

> **Aviso.** O pacote é pesquisa e especificação **proposta**. Nenhum equipamento, firmware, adaptador, modo de
> operação ou condomínio está homologado. Os 27 modelos do catálogo são candidatos documentados; os 100 casos de
> homologação começam como NÃO EXECUTADOS; o OpenAPI e o modelo de dados são propostas, não contratos publicados nem
> migrations. Facial e abertura remota continuam desligados (AGENTS.md). Nenhum endpoint de fabricante será
> inventado: cada adaptador segue só a documentação oficial da marca, e a homologação exige a combinação real
> (SKU, hardware, firmware) em bancada.

## Onde começar

1. [`pacote-v1.1/00_LEIA_PRIMEIRO.md`](pacote-v1.1/00_LEIA_PRIMEIRO.md) — visão geral, estrutura e responsabilidades.
2. [`pacote-v1.1/HANDOFF_CLAUDE_CODE.md`](pacote-v1.1/HANDOFF_CLAUDE_CODE.md) — regras de implementação incremental.
3. [`AUDITORIA_AC001.md`](AUDITORIA_AC001.md) — o que já existe no repositório, o que falta e a ordem proposta.
4. Manual: [`pacote-v1.1/documentacao/CondPulse_Acesso_Facial_v1.1.pdf`](pacote-v1.1/documentacao/CondPulse_Acesso_Facial_v1.1.pdf)
   (45 páginas) ou a fonte [Markdown](pacote-v1.1/documentacao/CondPulse_Acesso_Facial_v1.1.md). Capítulos 1–18 e
   25–32 são os que orientam o desenvolvimento.
5. Backlog AC-001 a AC-035: [`pacote-v1.1/planejamento/backlog.json`](pacote-v1.1/planejamento/backlog.json).
6. Modelo de dados proposto (20 entidades, sem SQL): [`pacote-v1.1/dados/Extensao_Modelo_Dados.md`](pacote-v1.1/dados/Extensao_Modelo_Dados.md).
7. Delta OpenAPI proposto (32 operações): [`pacote-v1.1/contratos/openapi_acesso_proposto.yaml`](pacote-v1.1/contratos/openapi_acesso_proposto.yaml).
8. Para cada implantação: dossiê em [`pacote-v1.1/implantacao/`](pacote-v1.1/implantacao/) (Markdown, PDF e CSVs).
9. Para homologar: [`pacote-v1.1/homologacao/`](pacote-v1.1/homologacao/) (casos HAC-001 a HAC-100 e termo técnico).

## Papéis

| Papel | O que faz | O que não faz |
|---|---|---|
| **MASTER da plataforma (CondPulse)** | Homologa a combinação fabricante/SKU/hardware/região/firmware/API-SDK/adaptador/modo/topologia; administra cofre e identidades de serviço; concede o MASTER de implantação | Não ganha acesso irrestrito a dados pessoais nem abertura física automática |
| **MASTER de implantação do condomínio** | Papel técnico com escopo de um condomínio, concedido pelo MASTER da plataforma: cadastra equipamentos, vincula canais a pontos, instala o conector e comissiona com perfis aprovados | Não lê senhas brutas nem edita firmware/configuração por editor genérico |
| **Gestor do condomínio** | Áreas lógicas, horários, categorias, idade, convites, reservas e exceções dentro das capacidades liberadas; aprova as regras locais | Não lê segredos, não troca vínculo físico, não atualiza firmware, não concede MASTER |

Portaria e moradores atuam só dentro do turno/escopo e do próprio vínculo. Hoje o repositório tem `PLATFORM_ADMIN`
(plataforma) e os papéis de condomínio `MANAGER`/`CONCIERGE`; o MASTER de implantação ainda não existe (ver auditoria).

## Gates de homologação (G0–G6)

| Gate | Exigência mínima |
|---|---|
| G0 Documentação | SKU, hardware, região, firmware, API/SDK, licença e inventário identificados |
| G1 Domínio | RBAC e isolamento com dois condomínios; cenários positivos e negativos |
| G2 Integração | Cadastro, aplicação, consulta, retirada e eventos com estados reais |
| G3 Bancada | Combinação física real: restrição antes do relé, timeout, offline, revogação e recuperação |
| G4 Instalação | Cada ponto/sentido/canal/sensor e saída comissionados no local |
| G5 Piloto | Operação monitorada, treinamento e contingência praticada |
| G6 Aceite | Aceite técnico do MASTER e aceite das regras pelo gestor (decisões complementares) |

Simulador e teste de contrato não substituem bancada; aprovar um modelo não se estende a outro sufixo, firmware,
controlador, modo ou topologia. **Nenhum gate foi cumprido até esta data.**

## Decisão do AC-002 e bloqueios (31/10/2026)

Decisão do responsável em 31/10/2026:

- Piloto: **Condomínio Teste 1**, no ambiente de homologação. A liberação para os demais condomínios vem depois de
  tudo funcionar lá.
- As **quatro marcas** (Control iD, Hikvision, Dahua, Intelbras) entram no escopo de implementação completa. Marca,
  equipamento, modo de operação e áreas são parametrizados **por condomínio**. O responsável escolhe depois qual
  equipamento testar fisicamente.

Limite que continua valendo: cada adaptador é implementado a partir da documentação oficial da API/SDK da marca e só
é testado contra simulador e contrato até haver hardware. Onde a documentação oficial exigir licença ou cadastro
(por exemplo, guias Hikvision por canal de parceiro ou SDK Dahua), essa operação fica pendente, sem endpoint
inventado.

**BLOQUEADO: bancada (G3), comissionamento (G4), piloto (G5) e aceites (G6) — responsável — equipamento físico
instalado no Condomínio Teste 1 (marca, SKU, firmware informados na escolha).**

## Cópia do pacote: o que entrou e o que ficou de fora

- Entraram todos os `.md`, `.json`, `.csv`, `.yaml`, `.svg` e os dois PDFs (manual e dossiê), byte a byte.
- Conferência em 31/10/2026 contra `pacote-v1.1/manifesto_arquivos.json`: 32 arquivos listados; **28 copiados com
  tamanho e SHA-256 idênticos, 0 divergentes**; o próprio manifesto também foi copiado (ele não se lista).
- **Omitidos (4), por decisão do responsável, para evitar binário duplicado** — o conteúdo está no PDF/Markdown ou no SVG:

  | Arquivo omitido | Bytes | Equivalente mantido |
  |---|---|---|
  | `documentacao/CondPulse_Acesso_Facial_v1.1.docx` | 333.364 | PDF e Markdown do manual |
  | `implantacao/Dossie_Condominio_Template.docx` | 109.189 | PDF e Markdown do dossiê |
  | `diagramas/arquitetura_acesso.png` | 117.500 | `diagramas/arquitetura_acesso.svg` |
  | `diagramas/mapa_areas.png` | 89.004 | `diagramas/mapa_areas.svg` |

- Os arquivos do pacote não foram editados (para manter o SHA-256). Por isso, alguns links internos apontam para os
  arquivos omitidos: `00_LEIA_PRIMEIRO.md` cita os `.docx`, e o manual em Markdown referencia os `.png` (use os `.svg`
  de mesmo nome). Para preencher um dossiê editável, duplique o `Dossie_Condominio_Template.md`.
- O pacote declara que não traz SDK, firmware, documentos de identidade, fotografias faciais nem segredos; os
  exemplos JSON e as linhas EXEMPLO dos CSVs são sintéticos.
