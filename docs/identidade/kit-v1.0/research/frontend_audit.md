# CondPulse — auditoria de frontend e identidade existente

Consulta: **31/10/2026**. Escopo: somente leitura de `advancedgit-alt/Condpulse-v2.0`; nenhuma modificação no código, banco, deploy ou configurações.

- Branch padrão: `ccr-5e725112-3x8hyg`.
- Commit fixado: [`7130b52769ad2acaac420fd2896b32b1ad7484ad`](https://github.com/advancedgit-alt/Condpulse-v2.0/commit/7130b52769ad2acaac420fd2896b32b1ad7484ad).
- Data do commit: 31/10/2026, 04:06:01 UTC (01:06:01 America/Sao_Paulo).
- Todas as fontes abaixo apontam para esse commit imutável.

## 1. Resposta direta

**A web CondPulse usa React com TypeScript, Vite e CSS próprio. Não usa Tailwind nem Bootstrap no código auditado.** Os componentes e as classes visuais são autorais.

O manifesto e o lockfile não incluem Material UI, Ant Design, Radix/shadcn, Tailwind, Bootstrap, Lucide, FontAwesome, Recharts, Chart.js ou biblioteca equivalente de componentes/ícones/charts. Os imports dos **47 arquivos TSX web** foram examinados: apenas React, React DOM, Router, Query e Fontsource aparecem como dependências externas. Os ícones efetivamente usados são SVGs definidos localmente no componente Icon.

TypeScript é a linguagem de programação; React compõe a interface; CSS define a aparência; Vite faz o build. “Linguagem visual” e “linguagem de programação” devem ser descritas separadamente.

| Camada | Confirmado no código | Evidência |
| --- | --- | --- |
| Interface | React 19.3.1, React DOM 19.3.1 declarados | [package.json](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/package.json) |
| Linguagem | TypeScript 7.0.2 declarado; modo strict | [package.json](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/package.json) e [tsconfig](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/tsconfig.json) |
| Build | Vite 8.3.5 + plugin React 6.1.2 declarados; plugin usado | [vite.config.ts](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/vite.config.ts) |
| Navegação | React Router DOM 7.18.4 declarado e BrowserRouter/Routes usados | [App.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/App.tsx) |
| Cache/dados | TanStack Query 5.105.0 declarado; provider e hooks usados | [App.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/App.tsx) |
| Estilos | Quatro folhas CSS comuns; CSS global, imports por tela | [styles.css](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/styles.css), [Dashboard.css](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/Dashboard.css), [Management.css](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/management/Management.css), [Availability.css](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/Availability.css) |
| Fontes web | Lato, via @fontsource/lato 5.3.0; arquivos 400/700 importados | [main.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/main.tsx#L1-L5) |
| Ícones | SVG próprio 24×24, render 22×22, traço 1,7, currentColor | [primitives.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/primitives.tsx#L13-L44) |
| Componentes | Brand, Icon, Notice, Loading, ErrorState, Empty, OfflineNotice, SectionTabs próprios | [primitives.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/primitives.tsx) |
| Mobile | React Native 0.88.1 e Expo 58.0.21 declarados; StyleSheet.create | [package mobile](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/package.json) e [Root.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/src/ui/Root.tsx#L155-L173) |

As versões são as declaradas no repositório, sem avaliação de disponibilidade no registry ou vulnerabilidades. Os imports comprovam uso no código, **não qual revisão foi publicada**. As quatro folhas não são CSS Modules: possuem seletores globais normais.

## 2. Arquitetura de tela já existente

A web já apresenta estrutura de sistema: sidebar, cabeçalho, conteúdo e rodapé; rotas separadas para morador, portaria, gestor e MASTER.

- Morador: início, reservas, encomendas, acessos, inquilinos e financeiro conforme vínculo.
- Portaria: encomendas, visitas, ocorrências.
- Gestão: pendências, reservas, áreas, unidades, importação, pessoas, avisos, pedidos, mudanças, acesso, ocorrências, financeiro e auditoria.
- MASTER: administração da plataforma e condomínios.

Fontes: [rotas/Shell](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/App.tsx) e [seções de gestão](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/management/ManagementPage.tsx).

A sidebar tem 244 px no padrão, 215 px na regra até 1200 px e vira gaveta abaixo de 1024 px. O conteúdo tem max-width 1550 px. A gaveta fecha ao navegar, por Esc e pelo fundo; restaura foco e usa inert no conteúdo ao abrir. São padrões a preservar ao evoluir a aparência.

O dashboard residencial combina contexto, próximas reservas, caixa de mensagens, avisos, pedidos e mudanças. A visão inicial de gestão mostra reservas e vínculos aguardando decisão. Não há biblioteca de gráficos instalada/usada; gráficos em futuros protótipos serão proposta nova. Fontes: [Dashboard.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/Dashboard.tsx), [Overview da gestão](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/management/ManagementPage.tsx#L109-L120).

## 3. Paleta real e tipografia

| Papel web | Cor atual |
| --- | --- |
| Navy / texto raiz / sidebar | #102A43 |
| Teal / ações / marca | #007F86 |
| Fundo | #F4F7FA |
| Superfície | #FFFFFF |
| Texto secundário | #52667A |
| Linha/borda global | #DCE5EC |
| Erro | #B42318 |
| Links teal reforçados | #006B72 |
| Acento da marca sobre navy | #65D5CD |
| Foco | #009DA7 |
| Aviso de sucesso | texto #14532D, fundo #EDF8EF |
| Aviso de atenção | texto #714B10, fundo #FFF8E8 |

Fonte: [styles.css](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/styles.css).

Trecho fiel, apenas reformatado:

```css
:root {
  font-family: Lato, system-ui, -apple-system,
    BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: #102a43;
  background: #f4f7fa;
  font-size: 16px;
  line-height: 1.5;
  font-synthesis: none;
  --navy: #102a43;
  --teal: #007f86;
  --muted: #52667a;
  --line: #dce5ec;
  --background: #f4f7fa;
  --surface: #fff;
  --error: #b42318;
}
```

A web importa **Lato 400 e 700**. Há CSS pedindo 550/650/750/800, sem importar arquivos desses pesos. O manual deve definir a escala desejada e os arquivos correspondentes. A base é 16 px/1,5; controles gerais 50 px e compactos 48 px; cards raio 16 px; inputs/botões raio 10 px. Existem foco visível, skip link e prefers-reduced-motion. Isso não equivale a uma auditoria completa de acessibilidade.

Há legendas/rodapés entre 0,6 e 0,72 rem e correções locais para 0,875 rem no dashboard. Guest-form/share-link inputs usam altura 40 px e weekday-picker label, 32 px; essas dimensões divergem da meta interna 48 px e precisam de verificação do alvo real em navegador antes de afirmar falha normativa.

Dois JSONs de referência já existem: [packages/design-tokens/tokens.json](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/packages/design-tokens/tokens.json) e [spec/design/tokens.json](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/tokens.json). **Não foi constatado consumo/geração automática a partir desses JSONs nos componentes/estilos examinados.** Não descrevê-los como fonte única já implementada. Os breakpoints do JSON (360/768/1280) diferem dos efetivos do CSS; Management.css usa --ink/--surface-muted com fallback, sem definição no :root examinado.

### Web e mobile divergem

| Token | Web | Mobile |
| --- | --- | --- |
| Navy | #102A43 | #102A43 |
| Teal | #007F86 | #1F7A7A |
| Fundo | #F4F7FA | #F4F7F9 |
| Texto | #102A43 na raiz | #1D3446 |
| Secundário | #52667A | #5C7888 |
| Linha | #DCE5EC | #D8E3EB |
| Erro | #B42318 | #A12D2D |

Fonte: [mobile theme.ts](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/src/ui/theme.ts).
Lato está comprovada na web. App/Root mobile usam fontes nativas sem fontFamily nos estilos auditados; não presumir Lato no app sem carregamento específico. O manifesto mobile não declara fontsource ou expo-font.

## 4. Marca: três representações divergentes

### A. Web: três barras CSS e palavra CondPulse

Brand() não importa o SVG de identidade; produz spans com três barras. O parâmetro light aplica branco e #65D5CD. **Isso não constitui dark mode completo.**

```tsx
<span className="brand-mark" aria-hidden="true">
  <span /><span /><span />
</span>
<span>Cond<span className="brand-accent">Pulse</span></span>
```

Fontes: [Brand](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/primitives.tsx#L6-L11) e [CSS](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/styles.css).

### B. Referência: C envolvendo três edifícios

O repositório inclui:

| Asset | Característica verificada |
| --- | --- |
| [condpulse_horizontal.svg](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/condpulse_horizontal.svg) | 724×210; lettering convertido em paths, sem fonte externa |
| [condpulse_horizontal.png](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/condpulse_horizontal.png) | 2172×630, RGBA |
| [condpulse_app_icon.svg](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/condpulse_app_icon.svg) | 512×512, viewBox 220×220, placa branca arredondada |
| [condpulse_app_icon.png](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/condpulse_app_icon.png) | 1024×1024, RGBA |
| [logo_conceito_original.png](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/marca/logo_conceito_original.png) | 2172×724, RGBA |

O [GUIA_MARCA.md](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/GUIA_MARCA.md) os apresenta como proposta e reconstrução técnica do conceito, com aprovação visual ainda necessária. Não são registro de marca nem evidência de adoção uniforme. Cópias originais sem alteração: visual_research/repo_assets/.

### C. Mobile: ícone azul em forma de A

O [app.json](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/app.json) aponta icon para ./assets/icon.png. A inspeção visual desse [asset](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/assets/icon.png) mostrou símbolo azul em forma de A com linhas de construção; não é o símbolo C/edifícios CondPulse. Cópia: repo_assets/mobile_app_icon_atual.png. O app declara userInterfaceStyle light. Não se inspecionou uma build distribuída nas lojas.

**Recomendação:** aprovar um original vetorial e distribuir variantes controladas para web/mobile/mídias. Substituir a marca CSS e assets móveis divergentes somente na implantação posterior. O kit local não altera a aplicação.

## 5. White label: estado constatado

Há contexto de condomínio/unidade/papel para dados e navegação. **Não foi identificado um motor de identidade por tenant no frontend auditado**: cores globais, nome/marca fixos, Brand com único parâmetro light, título HTML “CondPulse — Área do morador” e theme-color #102A43 estáticos. Não se encontrou seletor de tema por condomínio ou identidade dinâmica nos 47 TSX/quatro CSS.

Isso é uma conclusão sobre o frontend consultado, não prova de inexistência de algum campo não utilizado no banco/serviços.

O manual deve apresentar como evolução: contrato de tokens semânticos; herança CondPulse → pacote autorizado → condomínio; logos light/dark/símbolo/favicon; metadados e login; preview/publicação versionada/reversão; cache de tema por tenant/versão; contraste validado; cores de estado semanticamente estáveis; permissões de aparência separadas de credenciais e homologação. O branding não decide autorização.

## 6. Matriz de gaps

| ID | Evidência / situação atual | Encaminhamento proposto |
| --- | --- | --- |
| VIS-01 | Brand CSS difere do SVG C/edifícios | Unificar original e componente de marca |
| VIS-02 | app.json aponta ícone azul em A | Substituir icon/adaptive/splash após validação por plataforma |
| VIS-03 | Dois tokens.json, CSS e theme mobile divergem | Fonte única semântica, saídas web/native |
| VIS-04 | Lato 400/700; CSS pede outros pesos | Definir e carregar pesos reais |
| VIS-05 | Microtextos e alturas variadas | Revisão de escala, densidade e alvos |
| VIS-06 | Sem dark mode observado; Brand light só muda marca | Contrato completo de tema e validação |
| VIS-07 | Sem branding por tenant observado | Modelo/API/preview/publicação e fallback |
| VIS-08 | Componentes e ícones próprios | Consolidar biblioteca interna; não impor migração sem avaliação |
| VIS-09 | Sem biblioteca de charts | Charts dos protótipos são novos requisitos |
| VIS-10 | README web diz gestão/portaria pendentes, mas rotas existem | Atualizar documentação a partir da implementação |
| VIS-11 | index.html e app.json fixam identidade/metadados | Incluir metadados/login/distribuição no contrato white label |
| VIS-12 | Shell e componentes já exibem contexto, offline e dado antigo | Preservar estados operacionais no redesign |

Evidências VIS-01–06: Brand/CSS/tokens/mobile listados acima. VIS-07/11: [App.tsx](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/App.tsx), [index.html](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/index.html), [app.json](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/app.json). VIS-08/09: manifesto, lock e imports dos 47 TSX. VIS-10: [README web](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/README.md) versus [rotas](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/App.tsx). VIS-12: [primitives](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/primitives.tsx) e [Dashboard](https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/Dashboard.tsx).

## 7. Método e limites

A árvore recursiva retornou **sem truncamento**, com 1.022 entradas. Foram examinados 47 arquivos TSX web, quatro CSS, manifesto/lockfile, configurações Vite/TypeScript, tokens, arquivos representativos mobile e assets originais. Evidência selecionada: frontend_evidence.json. A busca de bibliotecas ocorreu no manifesto, lock e imports reais, não só no nome do projeto.

Não foram lidos segredos nem dados pessoais reais. Não houve clone, commit, merge ou implantação. Não se executou build ou testes nesta auditoria, inspeção autenticada de deploy, conferência do SHA publicado, teste físico, auditoria completa de acessibilidade ou pesquisa de registro de marca. Toda conclusão técnica acima é limitada ao commit fixado. Propostas e kits posteriores não são homologação de produção.

