# Identidade visual e white label — índice

Kit entregue pelo responsável em 31/10/2026: **CondPulse — Identidade, sistema e white label v1.0**.
Decisão de adoção: [ADR-0010](../adr/0010-identidade-visual-e-white-label.md).

O kit é uma **proposta para aprovação visual**. A cópia em `kit-v1.0/` preserva os arquivos como foram
entregues; nada aqui afirma homologação de produção, conformidade WCAG integral ou registro de marca.

## Por onde começar

| Ordem | Arquivo | Conteúdo |
|---|---|---|
| 1 | [kit-v1.0/LEIA-ME.md](kit-v1.0/LEIA-ME.md) | Visão geral da entrega, decisões principais e limites |
| 2 | [kit-v1.0/CondPulse_Manual_Identidade_Marca_Sistema_v1.0.md](kit-v1.0/CondPulse_Manual_Identidade_Marca_Sistema_v1.0.md) | Manual de 36 páginas (fonte editável) com o Anexo A de white label; PDF ao lado |
| 3 | [kit-v1.0/CondPulse_Caderno_Referencias_Prototipos_v1.0.md](kit-v1.0/CondPulse_Caderno_Referencias_Prototipos_v1.0.md) | Referências de mercado, três direções visuais e critérios de teste; PDF ao lado |
| 4 | [kit-v1.0/research/frontend_audit.md](kit-v1.0/research/frontend_audit.md) | Auditoria do frontend no commit `7130b52` (paleta real, pesos, três marcas divergentes) |
| 5 | [kit-v1.0/handoff/](kit-v1.0/handoff/) | `tokens.css`, `design-tokens.json`, `tenant-branding.schema.json` e exemplo, mapa token→código, relatório e script de contraste |
| 6 | [kit-v1.0/brand-assets/](kit-v1.0/brand-assets/) | SVG (horizontal, símbolo, app icon × positiva/negativa/mono), PNG 2×/4×, favicons, originais de referência |

Outros arquivos: `Manual_Identidade_CondPulse.md` (versão anterior do texto do manual, mantida como veio),
`manifest-entrega.json` (SHA-256 de todos os 105 arquivos da entrega original), `media-templates/` (feed, story,
anúncio e assinatura de e-mail em SVG editável, com Lato incorporada) e `fonts/OFL.txt` (licença da Lato).

## Decisões adotadas

- **Marca canônica:** símbolo "C envolvendo três edifícios" (`brand-assets/svg/condpulse-*`). Substitui as três
  barras em CSS da web e o ícone de template "A" do mobile. Aprovação visual final continua com o responsável.
- **Paleta:** navy `#102A43`, teal `#007F86`, fundo `#F4F7FA`, superfície `#FFFFFF`, texto secundário
  `#52667A`, link `#00666D` (teal puro sobre o fundo mede 4,459:1, abaixo de 4,5:1), erro `#B42318`, acento
  escuro `#5DD6C7`. Tema claro e escuro com tokens próprios; nada de inversão por filtro.
- **Tipografia:** Lato 400 e 700. Pesos 550/600/650/750/800 pedidos pelo CSS antigo são consolidados em 400/700.
- **Fonte de verdade:** tokens semânticos `--cp-*` (de `handoff/tokens.css`); componentes consomem significado,
  não HEX.
- **Direção visual padrão:** "A. Operacional CondPulse" do caderno.

## O que ficou fora desta cópia

Arquivos da entrega original (105 no total) que **não** foram versionados, com o motivo:

| Arquivo(s) | Motivo |
|---|---|
| `media-templates/*.png` (4: feed, story, anúncio, assinatura) | PNGs de mídia; o SVG editável de cada peça está na cópia |
| `brand-assets/condpulse-prancha-variantes.png` | Prancha de comparação das 12 variantes; reproduzível a partir dos SVG |
| `prototype-captures/*.jpg` (16) | Capturas do protótipo estático com dados demonstrativos; constam nas pranchas do caderno em PDF |
| `fonts/Lato-Regular.ttf`, `Lato-Bold.ttf`, `Lato-Regular.woff`, `Lato-Bold.woff` | Binários de fonte; a web já usa `@fontsource/lato` (400/700) e o mobile recebe seus próprios arquivos na fase do app. A licença (`OFL.txt`) foi mantida |

Os PNGs dos ícones e logos (`brand-assets/png/`, 24 arquivos) e os favicons (`brand-assets/favicons/`, 7
arquivos) foram mantidos. O `manifest-entrega.json` continua listando os hashes de todos os arquivos
originais, inclusive os omitidos.

O protótipo navegável citado no LEIA-ME é privado e externo ao repositório; não é dependência do produto.

## Plano de white label

O kit define o white label como **configuração versionada e governada** (Anexo A do manual). Fases:

1. **Web, agora (sem backend):** ponto de extensão no frontend. Os tokens aceitam override de cor de acento e
   logo por condomínio vindos de um objeto de configuração, validado contra `tenant-branding.schema.json`
   (subconjunto aplicável no cliente). Se o acento não atingir o contraste mínimo nos pares em que é usado
   (texto branco sobre o botão 4,5:1; link sobre o fundo 4,5:1; contraste não textual 3:1), o padrão
   CondPulse é aplicado automaticamente. Cores de estado (sucesso, atenção, erro, informação) e foco nunca são
   sobrescritos pela marca do cliente. Sem configuração, vale o padrão CondPulse.
2. **Backend, depois da trilha facial (V038+):** precisa de migration, por isso espera. Itens previstos:
   - tabela de perfis e revisões de marca por condomínio (revisão publicada imutável, herança
     plataforma → administradora → condomínio, ponteiro ativo trocado de forma atômica);
   - upload de logo com verificação de MIME e conteúdo, sanitização de SVG (sem script nem referência externa),
     limites de bytes e dimensões, hash e URL versionada;
   - validação no servidor (schema, contraste da composição nos dois modos, propriedade dos assets);
   - publicação e restauração (rollback) com auditoria e capacidades `brand.*` por escopo;
   - entrega do tema efetivo no contexto autenticado, com cache por condomínio + revisão + modo.
3. **Editor na gestão/MASTER:** rascunho, prévia com os componentes reais, publicação e restauração.
4. **Canais adicionais** (domínio, remetente de e-mail, app de loja): fases próprias, quando contratados.

A marca do cliente nunca concede permissão, muda regra de acesso ou dá acesso a credenciais técnicas.

## Implementação (fase 1)

| Superfície | Situação | Onde |
|---|---|---|
| Web | Tokens `--cp-*` claro/escuro, marca SVG canônica, favicons/manifest, links `#00666D`, Lato 400/700, ponto de extensão de white label | `apps/web/src/styles/tokens.css`, `apps/web/src/ui/theme/`, `apps/web/src/ui/brandLogo.ts` |
| Mobile | Ícone (iOS e adaptativo Android com monocromático), splash claro/escuro e cores do tema alinhados ao kit | `apps/mobile/app.json`, `apps/mobile/assets/`, `apps/mobile/src/ui/theme.ts` |
| Mobile, Lato | Pendente: o React Native não tem fonte global; exige `expo-font`, espera do carregamento e `fontFamily` em cada estilo de texto das 13 telas | — |
| Mobile, tema escuro | Pendente: `userInterfaceStyle` segue `light` até existir paleta escura validada no nativo | — |

Exceções conhecidas à meta de 12 px dos metadados: abas da unidade em telas de até 480 px (.72rem) e 340 px
(.66rem), exigidas pelo ensaio de fonte larga para manter as quatro abas inteiras em 320 px.
