# CondPulse — mapa de tokens para o código e contrato de marca

**Proposta de integração · 31/10/2026. Nenhum arquivo do aplicativo foi alterado.**

Base auditada: `advancedgit-alt/Condpulse-v2.0`, commit `7130b52769ad2acaac420fd2896b32b1ad7484ad`. A web usa React/TypeScript/Vite, CSS próprio e Lato 400/700. O mobile usa React Native/Expo, `StyleSheet` e fontes nativas nos arquivos examinados. A presença dos arquivos de referência de tokens no repositório não comprova que sejam consumidos automaticamente.

## 1. Conteúdo deste handoff

| Arquivo | Função |
|---|---|
| `design-tokens.json` | Candidato de fonte semântica para paleta, texto, espaço, raio, densidade e componentes. Formato JSON próprio; não declara conformidade DTCG |
| `tokens.css` | Exportação proposta de variáveis CSS para claro/escuro; exemplos de consumo permanecem comentados |
| `validate-contrast.py` | Cálculo reproduzível de contraste, somente biblioteca padrão Python |
| `contrast-report.json` | Resultado numérico, critérios, aplicação, limitações e hash dos tokens |
| `contrast-report.md` | Relatório legível para revisão |
| `tenant-branding.schema.json` | JSON Schema 2020-12 para o rascunho de overrides de marca |
| `tenant-branding.example.json` | Exemplo fictício coerente com o contrato; não contém segredo nem URL executável |

Este diretório não cria um terceiro arquivo oficial de tokens no projeto. É um pacote de proposta para harmonizar as duas referências que já existem e o CSS/mobile implementados.

## 2. Pontos de integração identificados

| Caminho auditado no repositório | Situação observada | Evolução proposta |
|---|---|---|
| `apps/web/src/styles.css` | `:root` tem navy, teal, muted, line, background, surface e error; cores também aparecem em regras | Importar tokens gerados antes dos componentes e substituir usos por significado |
| `apps/web/src/ui/Dashboard.css` | Estilos específicos do dashboard | Consumir tokens globais; reduzir exceções locais sem apagar a hierarquia de conteúdo |
| `apps/web/src/ui/management/Management.css` | Folha de gestão; alguns usos de `--ink` e `--surface-muted` têm fallback | Definir equivalentes semânticos explícitos; retirar fallback somente após resolver todos os consumidores |
| `apps/web/src/ui/Availability.css` | Estilos de disponibilidade/reservas | Aplicar cores de estado com texto/ícone; testar calendário, lista e formulário |
| `apps/web/src/main.tsx` | Importa `@fontsource/lato/400.css` e `700.css` | Manter arquivos reais de 400/700; alinhar os pedidos de peso do CSS |
| `apps/web/src/App.tsx` | Shell, contexto, rotas, sidebar e comportamento responsivo | Resolver tema por contexto autorizado; preservar navegação, foco, Esc, inert e estados operacionais |
| `apps/web/src/ui/primitives.tsx` | Brand em CSS, Icon SVG próprio e estados reutilizáveis | Brand consome variante institucional ou asset autorizado; Notice/Empty/Error/Loading/Offline consomem tokens |
| `apps/web/index.html` | Título e theme-color estáticos | Planejar metadados do tenant com fallback neutro; login usa identidade pública mínima |
| `packages/design-tokens/tokens.json` | Referência existente sem geração automática comprovada | Candidato a fonte canônica após revisão; gerar saídas web e native |
| `spec/design/tokens.json` | Outra referência existente, divergente em alguns valores | Transformar em documentação/snapshot gerado, evitando duas fontes editadas manualmente |
| `apps/mobile/src/ui/theme.ts` | Paleta própria difere da web | Consumir saída native do mesmo contrato sem passar variáveis CSS ao React Native |
| `apps/mobile/src/ui/Root.tsx` | `StyleSheet.create`, ações de 48 e fontes nativas | Preservar alvo de toque e gerar adaptador de cores; evitar opacidade global como único estado pressed |
| `apps/mobile/app.json` | Identidade e modo claro fixados; ícone atual diverge da marca de referência | Alterar em trabalho de implantação específico; ícone de loja não muda pelo favicon web |

Todos os caminhos são do commit auditado, não de um checkout modificado. Os caminhos de destino de saídas geradas abaixo são sugestões de organização.

## 3. Mapa semântico

| Token proposto | CSS exportado | Uso correto | Cuidado na migração |
|---|---|---|---|
| `surface.canvas` | `--cp-color-surface-canvas` | Fundo do aplicativo | Não usar como regra de cor do texto |
| `surface.panel` | `--cp-color-surface-panel` | Cards, diálogos, campos e tabelas | Substituir branco fixo considerando o modo escuro |
| `surface.alternate` | `--cp-color-surface-alternate` | Hover neutro e seções alternadas | Hover não substitui selected/pressed/focus |
| `surface.inverse` | `--cp-color-surface-inverse` | Sidebar ou cabeçalho escuro quando esse padrão for escolhido | Precisa do conjunto de texto/foco inversos |
| `text.primary` | `--cp-color-text-primary` | Texto principal | O antigo `--navy` mistura texto e fundo; não fazer substituição global cega |
| `text.secondary` | `--cp-color-text-secondary` | Descrição, metadado e placeholder informativo | Não usar opacidade sobre texto sem recalcular |
| `text.link` | `--cp-color-text-link` | Links sublinhados | Light usa #00666D, pois #007F86 falha no canvas como texto comum |
| `text.inverse` | `--cp-color-text-inverse` | Texto sobre superfície inversa | Não aplicar automaticamente a botão claro no modo escuro |
| `text.inverseSecondary` | `--cp-color-text-inverse-secondary` | Texto secundário em navy/escuro | Manter par do conjunto inverso |
| `text.inverseLink` | `--cp-color-text-inverse-link` | Link sobre superfície inversa | Preservar identificação por sublinhado/estado |
| `action.primary.background` | `--cp-color-action-primary-background` | Botão principal | Identidade teal vira tom claro no escuro para legibilidade |
| `action.primary.foreground` | `--cp-color-action-primary-foreground` | Texto/ícone desse botão | Branco no claro; #0D1724 no escuro |
| `action.primary.hover` | `--cp-color-action-primary-hover` | Fundo de hover primário | #00666D no claro; alias do normal no escuro nesta proposta |
| `border.decorative` | `--cp-color-border-decorative` | Divisor discreto ou contorno não essencial | Não deve ser o único limite de um campo ou estado selecionado |
| `border.control` | `--cp-color-border-control` | Contorno necessário de campo/controle | Par calculado para canvas, panel e alternate |
| `status.*.foreground/background` | `--cp-color-status-…` | Avisos e badges de significado estável | Não sobrescrever com cor institucional do cliente |
| `focus.ring` | `--cp-color-focus-ring` | Anel de foco nas superfícies comuns | Outline 2px com offset 3px, sem clipping |
| `focus.inverse` | `--cp-color-focus-inverse` | Foco em superfície inversa | Resolve o contraste insuficiente do azul light sobre navy |

O JSON também traz aliases de componente. Um botão consome `button.primary.text`, que aponta para `action.primary.foreground`. O cliente altera somente propriedades autorizadas do perfil; o compilador resolve quais tokens precisam mudar. Não permitir edição direta de `--cp-*` no painel de cliente.

## 4. Sequência de integração no frontend

1. Revisar este candidato e escolher a fonte canônica no pacote de design tokens existente.
2. Criar geração determinística para uma folha web e um módulo TypeScript native. Exemplo de destino web: `apps/web/src/generated/tokens.css`; destino native: módulo gerado importado por `apps/mobile/src/ui/theme.ts`.
3. Migrar primeiro o shell e os componentes reutilizados. Depois migrar Dashboard, Gestão e Disponibilidade.
4. Substituir cores pelo papel de cada ocorrência. Uma mesma cor literal pode representar texto, fundo ou marca e precisar de tokens diferentes.
5. Mapear pesos para Lato 400/700. O código auditado pede outros pesos sem importar arquivos correspondentes; decidir entre carregar pesos reais ou consolidar a escala.
6. Implementar provedor de tema autenticado e preferências admitidas; resolver `system` para `light` ou `dark` antes de aplicar `data-cp-theme`.
7. Verificar componentes com teclado, leitor de tela, zoom, layout móvel e troca de tenant. Só então publicar a implementação.

O arquivo CSS deste handoff exporta variáveis. Ele não muda as classes atuais ao ser salvo e não contém tokens como `--navy` para aparentar uma migração automática. O estado dark do logo atual não é um modo escuro completo.

## 5. Espaço, densidade e tipografia

- Lato 400/700 permanece o padrão web. No native, manter fonte nativa até existir carregamento e teste específico de Lato; usar o mesmo nome no JSON não carrega uma fonte no aplicativo.
- Corpo 16px; rótulo/legenda 14px; título de seção 20px; página 28px; métrica 32px. Metadata de 12px só em conteúdo secundário não crítico.
- Espaçamento em escala de 4px; controles raio 10px e cards/diálogos 16px, preservando a direção existente.
- Controle confortável 48px; compacto 40px apenas com ponteiro preciso. A meta de toque do produto permanece 48px. O critério AA de alvo tem definição própria de 24px com exceções; não confundir a meta do produto com mínimo normativo.
- O shell mantém como referência 244px de sidebar, 215px na faixa mais estreita, gaveta abaixo de 1024px e conteúdo até 1550px. Confirmar o comportamento nas telas reais antes de refatorar media queries.
- Movimento de 150/220ms e modo reduzido com 0ms para transições não essenciais. Mudança de movimento não interrompe operações de domínio.

Tokens CSS de breakpoint não substituem diretamente números em media queries. O gerador pode reutilizar os números do JSON para produzir os seletores corretos. Não afirmar que a mera declaração de variável faz a navegação responsiva.

## 6. Contraste: resultado e limites

O script executado encontrou **96/96 pares obrigatórios adequados** para os usos descritos. O JSON registra separadamente 14 medições decorativas sem limiar, seis verificações complementares de contraste de foco e 12 diagnósticos de uso inadequado. Onze diagnósticos ficam abaixo do limiar; eles documentam combinações a evitar, não pares aprovados.

Decisões principais:

| Combinação | Razão aproximada | Decisão |
|---|---:|---|
| Branco sobre #007F86 | 4,795:1 | Pode ser texto comum do botão no claro |
| #007F86 sobre #F4F7FA | 4,459:1 | Não passa texto comum AA; não arredondar para 4,5 |
| #00666D sobre #F4F7FA | 6,257:1 | Usar para link light |
| #7B8C9C contra branco | 3,458:1 | Borda de campo passa o par não textual |
| #7B8C9C contra #F4F7FA | 3,216:1 | Borda de campo passa o par não textual |
| #005FCC diretamente contra #007F86 | 1,248:1 | Não encostar o foco diretamente na ação primária |
| #8EBDFF diretamente contra #5DD6C7 | 1,095:1 | Mesma restrição no dark; separar por superfície |

O anel é desenhado sobre o fundo com offset; sua cor também precisa ser adequada à superfície externa. Não usar margem entre anel e botão preenchida de outra cor sem recalcular. Verificar se a região do outline fica visível, especialmente em cards com `overflow: hidden`, drawers e rodapés fixos.

O relatório avalia contraste de cores opacas. Não avalia tamanho real, área do foco, opacidade global, antialiasing, fundos fotográficos, estados de carregamento ou todos os requisitos WCAG. Hover dark usa a mesma cor de base nesta proposta; o feedback completo ainda precisa de revisão de componente. Texto, foco, forma e estados não devem depender só de variação de cor.

## 7. Contrato de tenant branding

O schema é um **contrato de rascunho de override**, em JSON Schema 2020-12. Propriedades omitidas herdam da revisão pai. Exige `schemaVersion`, `owner` e `inherits`; restringe os demais campos, enumera opções e rejeita propriedades desconhecidas. Não aceita URL arbitrária de imagem, CSS, HTML, script nem `status: published` enviado pelo cliente.

### Campos aceitos

| Grupo | Conteúdo |
|---|---|
| `owner` | Tipo organization/condominium e ID; o servidor confronta com o escopo autorizado |
| `inherits` | Perfil e revisão pai fixada; não resolver automaticamente “última” |
| `identity` | Nome de exibição, nome curto e modo de co-branding permitido |
| `brand` | HEX de seis dígitos para primary/accent; família `lato` no ciclo inicial |
| `assets` | IDs de arquivos processados; variantes horizontal/símbolo, mono, fundo de marca e favicon |
| `appearance` | Modo e densidade padrão, listas de opções permitidas |

### O schema não substitui estas verificações no servidor

1. Autenticação, associação ao tenant e capacidade específica para editar/publicar marca.
2. Permissão para usar o perfil pai; proibição de ciclos; compatibilidade de versão.
3. Resolução integral da herança e confirmação de que o modo padrão está entre os permitidos.
4. Disponibilidade das variantes de assets para os modos efetivos, inclusive dark quando `system` pode resolvê-lo.
5. Propriedade e processamento de cada asset; MIME, sanitização, dimensão, hash e autorização de uso.
6. Normalização e limites de nomes; renderização como texto, nunca HTML.
7. Política contratual de co-branding e fonte aprovada.
8. Compilação semântica e teste de todos os pares aplicáveis à marca resultante.
9. Controle de concorrência, auditoria e publicação atômica da revisão imutável.
10. Cache por tenant/revisão/modo, reversão e limpeza de contexto ao trocar condomínio.

É possível um JSON ser válido e ainda assim ser rejeitado por autorização, herança, cor inadequada ou asset pertencente a outro cliente. O schema não calcula contraste e não concede acesso. O exemplo usa IDs fictícios e não foi enviado para nenhum endpoint.

Os arquivos JSON foram analisados sintaticamente e revisados contra as restrições declaradas. Este handoff não afirma execução de validador JSON Schema completo, pois tal biblioteca não estava instalada no ambiente; integrar um validador compatível com Draft 2020-12 na implementação.

## 8. Publicação e vínculo com o domínio

Manter o fluxo rascunho → validação → aprovação quando exigida pela política → publicação. Qualquer edição posterior invalida a validação anterior. Uma revisão efetiva registra versões de plataforma, administradora e condomínio. A marca da administradora não deve alterar silenciosamente condomínios com composição publicada.

A troca visual não concede abertura de área, muda idade mínima, libera visitantes, habilita dispositivo nem redefine “sucesso”. Abertura solicitada, solicitação enviada, confirmação recebida e falha continuam sendo estados distintos definidos pelo serviço. Gestores não recebem chaves ou acesso a integrações por obter permissão de aparência.

## 9. Evidências e fontes

Auditoria do código:

- https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/styles.css
- https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/main.tsx
- https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/web/src/ui/primitives.tsx
- https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/apps/mobile/src/ui/theme.ts
- https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/packages/design-tokens/tokens.json
- https://github.com/advancedgit-alt/Condpulse-v2.0/blob/7130b52769ad2acaac420fd2896b32b1ad7484ad/spec/design/tokens.json

Referências externas:

- Tokens semânticos: https://atlassian.design/tokens/design-tokens/
- Norma de acessibilidade: https://www.w3.org/TR/WCAG22/
- Contraste textual: https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
- Contraste não textual: https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html
- Aparência do foco: https://www.w3.org/WAI/WCAG22/Understanding/focus-appearance.html
- JSON Schema: https://json-schema.org/draft/2020-12
- Isolamento de tenant: https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html

