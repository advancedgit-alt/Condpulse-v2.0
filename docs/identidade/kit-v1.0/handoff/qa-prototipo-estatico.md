# CondPulse — QA estático do protótipo visual

**Data:** 31/10/2026  
**Artefato:** Laboratório visual CondPulse, `sites/condpulse-visual-lab/dist/`  
**Escopo desta verificação:** análise estática e execução local de funções JavaScript em Node.js.  
**Resultado:** verificação de sintaxe aprovada; **61 asserções executadas e aprovadas** nos grupos detalhados abaixo.

Este documento registra o que foi efetivamente verificado. Não declara homologação do sistema CondPulse em produção, de hardware, de autenticação ou de controle de acesso.

## 1. Método executado

Foram lidos `index.html`, `style.css` e `app.js` para identificar problemas concretos de navegação, estado, semântica, contraste e diálogo. As correções atribuídas a esta revisão foram aplicadas somente em `app.js`. O responsável pela entrega tratou CSS, HTML, assets, renderização e publicação separadamente.

A sintaxe de `app.js` foi conferida com:

```sh
node --check sites/condpulse-visual-lab/dist/app.js
```

O comando terminou com código 0.

As 61 asserções foram executadas em um processo Node.js, usando `node:vm` e `node:assert/strict`. O ensaio carregou as definições reais de funções e os dados locais do próprio `app.js`, até o início do registro dos listeners de clique. Referências mínimas ao DOM foram substituídas por objetos locais; renderização, notificações e abertura/fechamento de diálogos foram substituídas por funções de observação para testar a lógica.

Portanto, o ensaio valida a lógica indicada na matriz abaixo. Ele **não simula um navegador completo** nem comprova layout, foco real, comportamento de leitores de tela ou acessibilidade integral.

Saída observada:

```text
PASS: 61 assertions; rules per area, light/dark colors, cross-theme rejection,
tenant snapshots, selected approval. No browser or production executed.
```

## 2. Matriz das 61 asserções

| Grupo | Asserções | Resultado |
| --- | ---: | --- |
| Regra de acesso por área | 9 | Aprovadas |
| Contraste de links, superfícies e botão escuro | 35 | Aprovadas |
| Preservação da família azul no modo escuro | 1 | Aprovada |
| Rejeição de cor inválida no claro mesmo com modo escuro selecionado | 1 | Aprovada |
| Snapshot publicado e isolamento da identidade entre condomínios fictícios | 9 | Aprovadas |
| Dados da solicitação selecionada para revisão | 3 | Aprovadas |
| Atualização da fila após aprovação simulada | 3 | Aprovadas |
| **Total** | **61** | **Todas aprovadas** |

### 2.1 Regra de acesso por área — 9 asserções

1. Academia: morador com 17 anos recebe resultado negado.
2. Academia: morador com 18 anos e reserva recebe resultado permitido.
3. Academia: morador com 18 anos sem reserva recebe resultado negado.
4. Academia: visitante de 30 anos recebe resultado negado no padrão inicial.
5. Academia: morador sem idade recebe resultado de revisão necessária.
6. Recepção de visitantes: visitante sem idade e sem reserva é permitido pelos campos limitados dessa regra demonstrativa.
7. Recepção de visitantes: morador de 30 anos recebe resultado negado.
8. Acesso de serviço: prestador de 25 anos sem reserva é permitido pelos campos demonstrativos configurados.
9. Academia: idade 121 recebe resultado de cenário inválido.

Os casos “permitido” verificam apenas público, idade e reserva na simulação. Não validam identidade, convite, horário, credencial, equipamento ou direito real de passagem.

### 2.2 Contraste — 35 asserções

Foram examinadas sete cores de entrada:

- `#007F86`
- `#2858A8`
- `#87445D`
- `#52632A`
- `#FFFFFF`
- `#777777`
- `#FFEA00`

Para cada cor, cinco relações calculadas precisavam atingir pelo menos 4,5:1:

1. Link do tema claro sobre o fundo `#F4F7FA`.
2. Link do tema claro sobre a superfície suave derivada da marca.
3. Link do tema escuro sobre a superfície `#1A2B3F`.
4. Link do tema escuro sobre a superfície suave derivada da marca.
5. Cor do botão escuro contra sua cor de texto.

Total: 7 cores × 5 relações = **35 asserções**.

O cálculo usa luminância relativa sRGB. Não inclui todos os componentes, estados, bordas, foco, imagens ou combinações de superfície. Não constitui uma auditoria completa de acessibilidade.

### 2.3 Família de cor e validação entre temas — 2 asserções

- A entrada azul `#2858A8` gera a variante escura prevista `#9BBCFF`.
- Com o modo escuro selecionado e cor personalizada `#777777`, a validação retorna valor inferior a 4,5 por considerar também o botão do tema claro. Assim, o modo escuro não mascara uma falha no claro.

### 2.4 Snapshot e identidade por condomínio — 9 asserções

O ensaio publicou localmente nome, cor e logo de exemplo, alterou o rascunho e restaurou o snapshot. Em seguida, trocou para outro condomínio fictício e retornou.

Foram conferidos:

1. Nome restaurado do primeiro condomínio.
2. Cor restaurada do primeiro condomínio.
3. Logo restaurada do primeiro condomínio.
4. Nome inicial do segundo condomínio.
5. Cor inicial do segundo condomínio.
6. Ausência de logo herdada indevidamente no segundo condomínio.
7. Nome preservado ao retornar ao primeiro condomínio.
8. Cor preservada ao retornar ao primeiro condomínio.
9. Logo preservada ao retornar ao primeiro condomínio.

As imagens utilizadas nas asserções eram apenas strings sintéticas. Nenhum arquivo pessoal foi enviado. Os snapshots ficam em memória durante a sessão do protótipo.

### 2.5 Revisão e aprovação de reserva — 6 asserções

Ao abrir a solicitação do Salão de festas:

1. O conteúdo de revisão contém Camila Nunes.
2. O período contém 07/11/2026.
3. O conteúdo não contém Felipe Almeida, responsável de outra solicitação.

Após a aprovação simulada:

4. A fila tem duas solicitações pendentes.
5. A solicitação do Salão de festas está marcada como aprovada.
6. A solicitação da Churrasqueira permanece pendente.

## 3. Correções verificadas por inspeção de código

As alterações abaixo foram inspecionadas estaticamente. Apenas os comportamentos abrangidos pela matriz anterior receberam asserções executadas.

### Navegação e diálogos

- Selecionar condomínio fecha o diálogo antes da mudança de tela.
- Um diálogo reutilizado mantém título e conteúdo atualizados.
- Mensagens produzidas enquanto um diálogo está aberto são inseridas no próprio diálogo, com `role="alert"`, evitando depender de um toast externo ao contexto modal.
- A navegação móvel possui botão de fechar, backdrop, tratamento de Esc e foco.
- A sidebar fechada em tela estreita usa `inert`; ao abrir, o conteúdo externo fica inerte.
- Navegar por ação do protótipo fecha a gaveta e direciona foco ao conteúdo principal.
- Na finalização, `route()` recebeu `window.scrollTo(0,0)` imediatamente após `render()`, preservando o foco posterior em `#main` com `preventScroll:true`. Essa alteração teve nova verificação de sintaxe aprovada; não recebeu ensaio em navegador nesta revisão.

Seletores introduzidos para integração com o CSS:

- `#drawerBackdrop.drawer-backdrop`
- `#closeNavigation.drawer-close`

### Regras e estados demonstrativos

- Cada área mantém seu próprio público, idade mínima, autorização adicional a visitantes e exigência de reserva.
- A seleção de público é efetivamente salva.
- O simulador usa a área selecionada e distingue negado, revisão necessária, cenário inválido e permitido na simulação.
- A idade numérica é validada como inteiro entre 0 e 120.
- A revisão da reserva usa os dados do item selecionado.
- Aprovar uma solicitação atualiza a fila e os contadores locais.
- As mensagens indicam que a decisão é simulada e não provisiona autorização real.

### White label e cor

- Nome, logo, cor, rascunho e revisão publicada são mantidos por condomínio fictício.
- Restaurar publicado recupera o snapshot daquele condomínio.
- A cor escolhida mantém uma variante correspondente no modo escuro.
- Links e preenchimentos de marca usam tokens separados.
- O critério de publicação da demonstração considera o botão nos dois temas.
- A leitura assíncrona de imagem não exige que a prévia ainda exista na tela e mantém o resultado associado ao condomínio em que o upload começou.

## 4. Limites e exclusões

**Não executado por esta revisão:**

- Navegador real, captura de tela, inspeção visual final ou automação de interface.
- Teste de teclado, foco, leitor de tela, zoom ou contraste de todos os componentes.
- Autenticação, autorização real, RBAC, isolamento de banco ou teste de segurança de produção.
- Chamada à API CondPulse, Supabase, fornecedores, serviços de mensagens ou integrações financeiras.
- Reconhecimento facial, envio de comandos, acionamento de relé, confirmação de sensor ou teste de contingência física.
- Publicação ou implantação em qualquer ambiente.
- Persistência em banco ou armazenamento de produção.
- Auditoria de licença, marca registrada ou aprovação comercial de identidade.

O seletor de perfil é demonstrativo e não é uma barreira de segurança. O protótipo permite explorar apresentações de papéis sem autenticar usuários. As regras e os snapshots locais não substituem as decisões do servidor no sistema real.

A execução das 61 asserções ocorreu na versão corrigida pelo revisor antes das capturas finais. Ajustes posteriores de CSS, HTML, legendas e publicação pertencem à validação conduzida pelo responsável pela entrega. Não se deve interpretar este relatório como comprovação de que esses ajustes posteriores foram testados por este ensaio.

## 5. Estado de entrega

O pacote registra **QA estático e lógico aprovado no escopo descrito**. As capturas e a revisão em navegador são uma etapa separada. Nenhum teste futuro foi contabilizado como executado e nenhum resultado desta demonstração foi atribuído à aplicação real.



## Revisão de navegador conduzida na entrega

Em 31/10/2026 foram inspecionadas as 11 telas, os temas claro/escuro e três quadros responsivos de 374 CSS px. Fluxos observados: abrir confirmação de comando, retorno de comando aceito sem confirmação física, simular academia aos 17 e 18 anos, abrir editor de regra, filtrar pessoa por nome, selecionar condomínio demonstrativo, alterar cor, publicar tema demonstrativo e alternar perfil. No celular, o drawer abriu com foco no fechamento e fechou por Escape com aria-expanded=false. Não foram observadas mensagens de erro da aplicação no log filtrado pela origem do protótipo; mensagens da extensão do navegador não foram atribuídas ao produto.

As capturas usam dados sintéticos e representam a viewport disponível. Não houve teste com usuários, aparelho móvel físico, leitor de tela, backend, biometria ou equipamentos reais. Algumas ações exibem apenas um diálogo explicativo do fluxo.
