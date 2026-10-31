# CondPulse — relatório de contraste da proposta

**Status: proposta técnica. Não é homologação da aplicação nem certificação WCAG.**

Data: 2026-10-31 · Tokens: 1.0.0-proposal · Fonte: `design-tokens.json`.

## Resultado

**96 de 96 verificações de pares obrigatórios passaram**, dentro dos usos e condições especificados.

Há 14 medições decorativas sem limiar, 6 verificações complementares de contraste de foco e 12 diagnósticos de usos inadequados (11 abaixo do limiar).

A aplicação completa não foi executada ou avaliada por este script. Valores repetidos em usos diferentes aparecem como verificações distintas.

## Método

Cores sRGB opacas em HEX de 6 dígitos. Cada canal é linearizado; a luminância usa 0,2126R + 0,7152G + 0,0722B. A razão é (Lclara + 0,05)/(Lescura + 0,05). O comparador usa o valor numérico integral disponível antes de qualquer formatação. A tabela exibe três casas por legibilidade.

Critérios: texto comum AA 4,5:1 (1.4.3); informação visual não textual necessária AA 3:1 (1.4.11). O teste complementar de foco usa apenas a parcela de contraste do critério AAA 2.4.13, sem validar área ou visibilidade. Bordas puramente decorativas não recebem falso requisito de 3:1.

## Decisões decorrentes

- Texto branco sobre o botão #007F86 é permitido pelo par calculado. O mesmo teal como texto pequeno sobre #F4F7FA falha: usar link #00666D.
- No modo escuro, o botão #5DD6C7 usa texto #0D1724; não usar branco nesse botão.
- Campos usam a borda `border.control`; `border.decorative` é reservada a divisores/superfícies que não dependem dela para serem reconhecidos.
- O foco usa outline de 2px e offset de 3px sobre a superfície medida. Em navy/superfície inversa, usar `focus.inverse`. Verificar clipping, área e estados no navegador.
- Links têm sublinhado ou outro identificador persistente; o significado não depende somente da cor.
- Estados de erro, alerta e sucesso incluem texto/ícone. Cor não confirma uma abertura física ou sincronização.

## Modo claro — pares obrigatórios

| Uso | Frente | Fundo | Razão | Critério | Resultado |
|---|---|---|---:|---|---|
| text.primary sobre surface.canvas | `#102A43` | `#F4F7FA` | 13.618:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.primary sobre surface.panel | `#102A43` | `#FFFFFF` | 14.642:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.primary sobre surface.alternate | `#102A43` | `#F4F7FA` | 13.618:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.secondary sobre surface.canvas | `#52667A` | `#F4F7FA` | 5.517:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.secondary sobre surface.panel | `#52667A` | `#FFFFFF` | 5.932:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.secondary sobre surface.alternate | `#52667A` | `#F4F7FA` | 5.517:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.link sobre surface.canvas | `#00666D` | `#F4F7FA` | 6.257:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.link sobre surface.panel | `#00666D` | `#FFFFFF` | 6.728:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.link sobre surface.alternate | `#00666D` | `#F4F7FA` | 6.257:1 | 1.4.3 ≥ 4.5 | PASSA |
| Placeholder informativo sobre campo | `#52667A` | `#FFFFFF` | 5.932:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto da ação sobre action.primary.background | `#FFFFFF` | `#007F86` | 4.795:1 | 1.4.3 ≥ 4.5 | PASSA |
| Limite da ação action.primary.background contra surface.canvas | `#007F86` | `#F4F7FA` | 4.459:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.background contra surface.panel | `#007F86` | `#FFFFFF` | 4.795:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.background contra surface.alternate | `#007F86` | `#F4F7FA` | 4.459:1 | 1.4.11 ≥ 3 | PASSA |
| Texto da ação sobre action.primary.hover | `#FFFFFF` | `#00666D` | 6.728:1 | 1.4.3 ≥ 4.5 | PASSA |
| Limite da ação action.primary.hover contra surface.canvas | `#00666D` | `#F4F7FA` | 6.257:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.hover contra surface.panel | `#00666D` | `#FFFFFF` | 6.728:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.hover contra surface.alternate | `#00666D` | `#F4F7FA` | 6.257:1 | 1.4.11 ≥ 3 | PASSA |
| Borda de controle contra surface.canvas | `#7B8C9C` | `#F4F7FA` | 3.216:1 | 1.4.11 ≥ 3 | PASSA |
| Foco contra surface.canvas, com offset | `#005FCC` | `#F4F7FA` | 5.566:1 | 1.4.11 ≥ 3 | PASSA |
| Borda de controle contra surface.panel | `#7B8C9C` | `#FFFFFF` | 3.458:1 | 1.4.11 ≥ 3 | PASSA |
| Foco contra surface.panel, com offset | `#005FCC` | `#FFFFFF` | 5.985:1 | 1.4.11 ≥ 3 | PASSA |
| Borda de controle contra surface.alternate | `#7B8C9C` | `#F4F7FA` | 3.216:1 | 1.4.11 ≥ 3 | PASSA |
| Foco contra surface.alternate, com offset | `#005FCC` | `#F4F7FA` | 5.566:1 | 1.4.11 ≥ 3 | PASSA |
| text.inverse sobre superfície inversa | `#FFFFFF` | `#102A43` | 14.642:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.inverseSecondary sobre superfície inversa | `#B4C2D1` | `#102A43` | 8.072:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.inverseLink sobre superfície inversa | `#5DD6C7` | `#102A43` | 8.303:1 | 1.4.3 ≥ 4.5 | PASSA |
| Foco da superfície inversa | `#8EBDFF` | `#102A43` | 7.581:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado success | `#17633D` | `#EAF5EE` | 6.506:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado success | `#17633D` | `#EAF5EE` | 6.506:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso success | `#102A43` | `#EAF5EE` | 13.104:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso success | `#52667A` | `#EAF5EE` | 5.309:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico success sobre painel | `#17633D` | `#FFFFFF` | 7.270:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado warning | `#8A4B08` | `#FFF4DF` | 6.231:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado warning | `#8A4B08` | `#FFF4DF` | 6.231:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso warning | `#102A43` | `#FFF4DF` | 13.427:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso warning | `#52667A` | `#FFF4DF` | 5.440:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico warning sobre painel | `#8A4B08` | `#FFFFFF` | 6.795:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado danger | `#B42318` | `#FFF0EE` | 5.933:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado danger | `#B42318` | `#FFF0EE` | 5.933:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso danger | `#102A43` | `#FFF0EE` | 13.213:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso danger | `#52667A` | `#FFF0EE` | 5.353:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico danger sobre painel | `#B42318` | `#FFFFFF` | 6.574:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado info | `#2459A6` | `#EDF3FF` | 6.176:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado info | `#2459A6` | `#EDF3FF` | 6.176:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso info | `#102A43` | `#EDF3FF` | 13.153:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso info | `#52667A` | `#EDF3FF` | 5.329:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico info sobre painel | `#2459A6` | `#FFFFFF` | 6.875:1 | 1.4.11 ≥ 3 | PASSA |

## Modo escuro — pares obrigatórios

| Uso | Frente | Fundo | Razão | Critério | Resultado |
|---|---|---|---:|---|---|
| text.primary sobre surface.canvas | `#F2F6FB` | `#0D1724` | 16.608:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.primary sobre surface.panel | `#F2F6FB` | `#152334` | 14.639:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.primary sobre surface.alternate | `#F2F6FB` | `#1A2B3F` | 13.243:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.secondary sobre surface.canvas | `#B4C2D1` | `#0D1724` | 9.937:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.secondary sobre surface.panel | `#B4C2D1` | `#152334` | 8.759:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.secondary sobre surface.alternate | `#B4C2D1` | `#1A2B3F` | 7.923:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.link sobre surface.canvas | `#5DD6C7` | `#0D1724` | 10.221:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.link sobre surface.panel | `#5DD6C7` | `#152334` | 9.009:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.link sobre surface.alternate | `#5DD6C7` | `#1A2B3F` | 8.150:1 | 1.4.3 ≥ 4.5 | PASSA |
| Placeholder informativo sobre campo | `#B4C2D1` | `#152334` | 8.759:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto da ação sobre action.primary.background | `#0D1724` | `#5DD6C7` | 10.221:1 | 1.4.3 ≥ 4.5 | PASSA |
| Limite da ação action.primary.background contra surface.canvas | `#5DD6C7` | `#0D1724` | 10.221:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.background contra surface.panel | `#5DD6C7` | `#152334` | 9.009:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.background contra surface.alternate | `#5DD6C7` | `#1A2B3F` | 8.150:1 | 1.4.11 ≥ 3 | PASSA |
| Texto da ação sobre action.primary.hover | `#0D1724` | `#5DD6C7` | 10.221:1 | 1.4.3 ≥ 4.5 | PASSA |
| Limite da ação action.primary.hover contra surface.canvas | `#5DD6C7` | `#0D1724` | 10.221:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.hover contra surface.panel | `#5DD6C7` | `#152334` | 9.009:1 | 1.4.11 ≥ 3 | PASSA |
| Limite da ação action.primary.hover contra surface.alternate | `#5DD6C7` | `#1A2B3F` | 8.150:1 | 1.4.11 ≥ 3 | PASSA |
| Borda de controle contra surface.canvas | `#7891AC` | `#0D1724` | 5.531:1 | 1.4.11 ≥ 3 | PASSA |
| Foco contra surface.canvas, com offset | `#8EBDFF` | `#0D1724` | 9.332:1 | 1.4.11 ≥ 3 | PASSA |
| Borda de controle contra surface.panel | `#7891AC` | `#152334` | 4.875:1 | 1.4.11 ≥ 3 | PASSA |
| Foco contra surface.panel, com offset | `#8EBDFF` | `#152334` | 8.226:1 | 1.4.11 ≥ 3 | PASSA |
| Borda de controle contra surface.alternate | `#7891AC` | `#1A2B3F` | 4.410:1 | 1.4.11 ≥ 3 | PASSA |
| Foco contra surface.alternate, com offset | `#8EBDFF` | `#1A2B3F` | 7.441:1 | 1.4.11 ≥ 3 | PASSA |
| text.inverse sobre superfície inversa | `#F2F6FB` | `#0D1724` | 16.608:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.inverseSecondary sobre superfície inversa | `#B4C2D1` | `#0D1724` | 9.937:1 | 1.4.3 ≥ 4.5 | PASSA |
| text.inverseLink sobre superfície inversa | `#5DD6C7` | `#0D1724` | 10.221:1 | 1.4.3 ≥ 4.5 | PASSA |
| Foco da superfície inversa | `#8EBDFF` | `#0D1724` | 9.332:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado success | `#8FDDB2` | `#1C392D` | 7.864:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado success | `#8FDDB2` | `#1C392D` | 7.864:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso success | `#F2F6FB` | `#1C392D` | 11.570:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso success | `#B4C2D1` | `#1C392D` | 6.923:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico success sobre painel | `#8FDDB2` | `#152334` | 9.950:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado warning | `#FFD18A` | `#43321C` | 8.611:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado warning | `#FFD18A` | `#43321C` | 8.611:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso warning | `#F2F6FB` | `#43321C` | 11.305:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso warning | `#B4C2D1` | `#43321C` | 6.764:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico warning sobre painel | `#FFD18A` | `#152334` | 11.150:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado danger | `#FFB4AD` | `#42272C` | 7.947:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado danger | `#FFB4AD` | `#42272C` | 7.947:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso danger | `#F2F6FB` | `#42272C` | 12.418:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso danger | `#B4C2D1` | `#42272C` | 7.430:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico danger sobre painel | `#FFB4AD` | `#152334` | 9.368:1 | 1.4.11 ≥ 3 | PASSA |
| Rótulo do estado info | `#A8C8FF` | `#213653` | 7.204:1 | 1.4.3 ≥ 4.5 | PASSA |
| Ícone significativo do estado info | `#A8C8FF` | `#213653` | 7.204:1 | 1.4.11 ≥ 3 | PASSA |
| Texto principal no aviso info | `#F2F6FB` | `#213653` | 11.269:1 | 1.4.3 ≥ 4.5 | PASSA |
| Texto secundário no aviso info | `#B4C2D1` | `#213653` | 6.742:1 | 1.4.3 ≥ 4.5 | PASSA |
| Linha de gráfico info sobre painel | `#A8C8FF` | `#152334` | 9.358:1 | 1.4.11 ≥ 3 | PASSA |

## Combinações diagnosticadas — não copiar como padrão

| Modo | Uso diagnosticado | Frente / fundo | Razão | Limiar | Resultado numérico |
|---|---|---|---:|---:|---|
| light | USO INADEQUADO: divisor como única borda de campo em surface.canvas | `#DCE5EC` / `#F4F7FA` | 1.186:1 | 3 | Abaixo do limiar |
| light | USO INADEQUADO: divisor como única borda de campo em surface.panel | `#DCE5EC` / `#FFFFFF` | 1.276:1 | 3 | Abaixo do limiar |
| light | USO INADEQUADO: divisor como única borda de campo em surface.alternate | `#DCE5EC` / `#F4F7FA` | 1.186:1 | 3 | Abaixo do limiar |
| light | USO INADEQUADO: anel de foco tocando diretamente o botão primário | `#005FCC` / `#007F86` | 1.248:1 | 3 | Abaixo do limiar |
| light | Diagnóstico: foco padrão contra superfície inversa | `#005FCC` / `#102A43` | 2.446:1 | 3 | Abaixo do limiar |
| light | USO INADEQUADO: teal institucional como texto comum no canvas | `#007F86` / `#F4F7FA` | 4.459:1 | 4.5 | Abaixo do limiar |
| dark | USO INADEQUADO: divisor como única borda de campo em surface.canvas | `#34485E` / `#0D1724` | 1.918:1 | 3 | Abaixo do limiar |
| dark | USO INADEQUADO: divisor como única borda de campo em surface.panel | `#34485E` / `#152334` | 1.690:1 | 3 | Abaixo do limiar |
| dark | USO INADEQUADO: divisor como única borda de campo em surface.alternate | `#34485E` / `#1A2B3F` | 1.529:1 | 3 | Abaixo do limiar |
| dark | USO INADEQUADO: anel de foco tocando diretamente o botão primário | `#8EBDFF` / `#5DD6C7` | 1.095:1 | 3 | Abaixo do limiar |
| dark | Diagnóstico: foco padrão contra superfície inversa | `#8EBDFF` / `#0D1724` | 9.332:1 | 3 | Acima do limiar; usar variante indicada |
| dark | USO INADEQUADO: texto branco no botão claro do modo escuro | `#FFFFFF` / `#5DD6C7` | 1.763:1 | 4.5 | Abaixo do limiar |

## Decoração e foco complementar

As medições de divisores/fundos decorativos e as verificações complementares de foco estão registradas individualmente no JSON. `pass: null` identifica decoração sem limiar aplicável; não significa aprovação para converter a cor em borda de campo.

## Limites

- Não verifica tamanho/peso real da fonte, semântica, teclado, leitor de tela, reflow, clipping, zoom, erro, alvo de toque, dados ou autorização.
- Transparência, sombra, gradiente, imagem de fundo e opacidade global alteram o resultado e não são cobertos.
- Passagens de contraste da linha do gráfico não validam a distinção entre séries. Usar rótulos, símbolos e tabela alternativa.
- Foco: o contraste assume outline desenhado sobre a superfície indicada com offset; área e pixels reais precisam de teste em navegador.
- Os pares diagnosticamente inadequados são intencionais para documentar usos proibidos. Não entram como pares aprovados.
- Fundo/divisor decorativo tem pass=null. Sua função precisa realmente ser decorativa.
- Modo escuro mantém hover primário como alias do normal; verificar o feedback completo do componente antes da implantação.

## Reproduzir

```bash
python validate-contrast.py
```

O script usa apenas a biblioteca padrão do Python e grava os dois relatórios. Código de saída 1 indica falha em algum par obrigatório previsto; diagnósticos de combinações proibidas são preservados, mas não confundidos com pares válidos.

## Fontes

- https://www.w3.org/TR/WCAG22/#contrast-minimum
- https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
- https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html
- https://www.w3.org/WAI/WCAG22/Understanding/focus-appearance.html

SHA-256 do arquivo de tokens avaliado: `e639209e6590a1074e7891ce649efbe2de1b526c24feed5bf85558062d6b9bdc`
