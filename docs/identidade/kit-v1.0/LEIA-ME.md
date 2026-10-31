# CondPulse - Identidade, sistema e white label

**Entrega v1.0 | 31 de outubro de 2026**

## Comece por aqui

1. **CondPulse_Manual_Identidade_Marca_Sistema_v1.0.pdf**: 36 páginas A4 com identidade da marca, aplicações, componentes, tipografia, cores, integração ao sistema existente e especificação completa de personalização por cliente. O Markdown de mesmo nome preserva o texto editável integral.
2. **CondPulse_Caderno_Referencias_Prototipos_v1.0.pdf**: 32 páginas em formato horizontal amplo para leitura digital. Reúne referências oficiais comentadas, 16 pranchas do protótipo, direções visuais e o referencial textual integral. Amplie o PDF para examinar detalhes das telas.
3. **Protótipo navegável privado**: https://condpulse-visual-lab-20261031.tisindicoadvanced.chatgpt.site . O acesso pede a conta ChatGPT autorizada. A publicação foi confirmada; as interações foram verificadas no preview da mesma versão.

## O que já existe e o que foi proposto

A aplicação web auditada usa **React, TypeScript, Vite e CSS próprio**, com **Lato 400/700**. Não foi encontrado uso de Tailwind, Bootstrap ou biblioteca visual equivalente no escopo auditado. O mobile usa React Native/Expo com StyleSheet. A auditoria trata do repositório `advancedgit-alt/Condpulse-v2.0`, branch `ccr-5e725112-3x8hyg`, commit `7130b52769ad2acaac420fd2896b32b1ad7484ad`; não identifica o SHA efetivamente publicado em produção.

Os manuais recomendam manter essa base e consolidar tokens, componentes e identidade. As novas variantes de marca, o tema escuro, as telas e a arquitetura white label são propostas para adoção e validação. Nenhuma alteração foi aplicada ao repositório CondPulse de produção por esta entrega.

O protótipo é uma demonstração estática independente em HTML/CSS/JavaScript. Sua finalidade é permitir avaliar desenho e alguns comportamentos. Não é uma migração da aplicação React. O código está preservado no repositório do próprio Site; não é duplicado neste kit de documentos.

## Conteúdo do kit

| Pasta | Conteúdo e finalidade |
|---|---|
| `brand-assets/svg` | 12 arquivos vetoriais: assinatura horizontal, símbolo e app icon, nas versões positiva, negativa, mono branca e mono navy. |
| `brand-assets/png` | 24 exportações transparentes ou com placa intencional do app icon, em duas resoluções. |
| `brand-assets/favicons` | Ícones claros e escuros em 32, 180 e 512 px, além de favicon ICO. |
| `brand-assets/reference` | Dois vetores de origem preservados para rastreabilidade. |
| `media-templates` | Feed 1080×1350, story 1080×1920, anúncio 1200×628 e assinatura de e-mail 600×180, em SVG editável e PNG. |
| `fonts` | Lato Regular/Bold em TTF e WOFF, com licença OFL. |
| `handoff` | Tokens JSON/CSS, schema e exemplo de branding, mapeamento para os arquivos atuais, relatório de contraste, validação reproduzível, QA e textos editáveis. |
| `research` | Auditoria do frontend, estudo de referências e origens verificáveis. |
| `prototype-captures` | 16 capturas originais do protótipo. Dados e pessoas são demonstrativos. |

As imagens de produtos concorrentes aparecem apenas no caderno de referências, com crédito e contexto de estudo. Elas não fazem parte dos logos ou templates de divulgação CondPulse. Não há concessão de licença de marca de terceiros neste kit.

## Como explorar o protótipo

- Na **Visão geral**, abra as pendências, navegue para Portaria e consulte os dados do gráfico.
- Em **Portaria**, use “Simular liberação”. O comando pode ser aceito sem o sensor confirmar abertura. Nenhum equipamento é acionado.
- Em **Áreas e acessos**, edite uma regra demonstrativa ou simule a academia aos 17 e 18 anos. Público, idade, visitantes e reserva têm condições distintas.
- Em **Pessoas**, teste a busca e os filtros de vínculo.
- Em **Reservas**, revise e aprove o item escolhido da fila fictícia.
- Em **Identidade do cliente**, troque de condomínio demonstrativo, nome, cor e logo; confira os modos claro e escuro e publique uma revisão demonstrativa. A memória da página é temporária.
- No seletor superior, explore **Gestão, Portaria, Morador e MASTER**. Esses seletores ilustram navegação; não implementam autenticação ou RBAC.
- Na URL do protótipo, a página `mobile-preview.html` reúne três quadros responsivos de 374 CSS px. Ela usa a própria aplicação, sem criar imagens fictícias de interface.

Algumas ações secundárias abrem diálogos explicativos. Não há banco conectado, envio de mensagens, cobrança, biometria, credenciais de equipamento ou abertura física.

## Decisões principais para implementação

**Identidade.** Consolidar o símbolo C com três edifícios, já encontrado no material vetorial do projeto, e suas variantes. Hoje foram encontradas três manifestações diferentes: marca CSS na web, símbolo no material de design e ícone mobile de template. Escolher a versão canônica antes de distribuir o padrão definitivo.

**Cores.** Manter navy `#102A43`, teal `#007F86`, base `#F4F7FA` e painéis brancos. Usar `#00666D` para texto de link sobre a base clara: teal original sobre esse fundo mede 4,459:1, abaixo de 4,5:1. No escuro, resolver tokens específicos e usar texto escuro sobre a ação mint.

**White label.** Personalização institucional é uma configuração versionada. Tema, regras de acesso, permissões, módulos e segredos são domínios separados. O MASTER CondPulse governa o padrão; a edição local depende de escopo e delegação. O gestor não recebe controles técnicos capazes de interromper integrações.

**Migração.** Aplicar primeiro tokens e componentes comuns, depois o shell e os fluxos de portaria/áreas. Não substituir a stack apenas para obter uma aparência diferente. Integração real e testes de servidor devem preceder qualquer alegação de isolamento multi-tenant.

## Verificação e limites

- 61 asserções sobre funções reais do protótipo passaram, cobrindo regras, contraste, snapshots por condomínio e aprovação do item correto.
- 96 verificações obrigatórias de pares de contraste dos tokens passaram nos usos previstos. O relatório possui 128 medições ao todo, incluindo decoração, foco e diagnósticos de uso inadequado. Não é uma certificação WCAG da aplicação.
- As 11 telas, os modos claro/escuro e três quadros de celular foram inspecionados em navegador. Os fluxos críticos demonstrativos foram exercitados.
- Os dois PDFs foram renderizados e revisados. O manual principal preserva o texto integral e o anexo técnico; o caderno inclui o referencial textual completo.
- O schema e seu exemplo tiveram sintaxe JSON revisada; não foi executado um validador completo JSON Schema neste ambiente. Autorização, contraste, propriedade de assets e publicação não são garantidos apenas pelo schema.

Layouts, tamanhos mínimos de marca e regras propostas devem passar pela aprovação de identidade e por validação em dispositivos e ambientes reais. A entrega não declara homologação de produção, de hardware ou conformidade integral de acessibilidade.

## Versão da demonstração publicada

- Site: `appgprj_6ae5ba205a248191b771aed05f921714`
- Commit do protótipo: `7d959a41e03a478c2d57f1a95875c3587167e834`
- Publicação: 31/10/2026, status confirmado `succeeded`.
- Público: privado, com autenticação ChatGPT. Não houve mudança para acesso público.

