# Estrutura: Central administradora e app do condomínio

Pedido do responsável em 31/10/2026: uma **central administradora** (nosso servidor) onde a administradora libera ou
suspende condomínios, libera pagamentos, gerencia contas, chaves, tokens e credenciais do facial e regula o que cada
prédio pode usar; e, **dentro de cada condomínio**, uma única entrada em que morador, portaria, zelador e gestor
fazem login e cada um vê a sua tela. A tela atual `/contextos` (gestão + escolha de condomínio/unidade misturadas)
deixa de ser o ponto de entrada.

Base: PRD v1.0 (perfis e capacidades), [PAINEL_MASTER.md](PAINEL_MASTER.md), [GESTAO_LOCAL.md](GESTAO_LOCAL.md),
[FINANCEIRO_PLATAFORMA.md](FINANCEIRO_PLATAFORMA.md) e o pacote facial em `docs/integracoes/facial/`.

## 1. Dois mundos, uma regra

| | Central administradora (`/central`) | App do condomínio (`/c/{condomínio}`) |
| --- | --- | --- |
| Quem | `PLATFORM_ADMIN` (MASTER CondPulse), com AAL2 | Contas com vínculo no condomínio |
| Escopo | Todos os condomínios (tenants) | Só o condomínio do vínculo |
| Decide | Liberar/suspender prédio, plano e cobrança, módulos liberados, contas e papéis técnicos, chaves e segredos, catálogo e homologação facial | Operação do dia a dia dentro do que a central liberou |
| Nunca vê | — | Segredos, tokens, senhas de equipamento, firmware, canais/relés (gestão) |

Segredo de integração (equipamento facial, gateway, Asaas, e-mail) **só existe no servidor**: é gravado pela central
de forma write-only (cifrado, nunca devolvido por API nem mostrado em tela), com rotação e trilha de auditoria. O
prédio só vê "configurado / ausente / expira em".

## 2. Entrada única

1. Login (e segundo fator quando o papel exige).
2. O servidor devolve os contextos da conta (`/v1/me`): papel de plataforma, condomínios e papéis em cada um.
3. Roteamento automático:
   - só plataforma → `/central`;
   - um condomínio → `/c/{condomínio}` direto na tela do papel principal;
   - vários → seletor simples **só de condomínio** (cartões), sem misturar gestão e unidade.
4. Dentro do condomínio, o menu mostra apenas as áreas dos papéis da conta; quem tem mais de um papel troca pela
   barra de navegação, sem sair do condomínio. Unidade é escolhida dentro da área do morador quando há mais de uma.

## 3. App do condomínio — áreas por papel

| Área | Papel | Conteúdo (já existente, reorganizado) |
| --- | --- | --- |
| Início | todos | Avisos, pendências do papel, atalhos |
| Morador | RESIDENT/RESPONSIBLE/OWNER/DEPENDENT | Unidade (seletor interno), acessos/visitas, encomendas, reservas, pedidos, financeiro da unidade, inquilinos |
| Portaria | CONCIERGE (`concierge.operate`) | Fila de visitas, entradas/saídas, encomendas, ocorrências; abertura pontual quando o facial estiver pronto |
| Zeladoria | **MAINTENANCE (novo)** | Pedidos/manutenção atribuídos, ocorrências operacionais, mudanças; sem dados financeiros nem cadastros |
| Gestão | MANAGER | Unidades, vínculos, áreas comuns, reservas, avisos, pedidos, financeiro condominial, mapa de acesso e regras (sem equipamento/canal/segredo) |
| Implantação | MASTER de implantação (concessão técnica) | Áreas físicas, pontos, equipamentos, canais, vínculos e aceite |

## 4. Central administradora — seções

| Seção | O que faz | Estado |
| --- | --- | --- |
| Condomínios | Criar, ativar/suspender (motivo), ficha, gestores e convites | Existe (V010/V035) |
| Módulos por condomínio | Liga/desliga módulos (reservas, encomendas, visitas, financeiro, facial…) por prédio, dentro das flags globais | **Novo** (backend + tela) |
| Financeiro da plataforma | Planos, assinaturas, cobranças, registrar/estornar pagamento, inadimplência → suspensão manual | Existe (V034); tela a consolidar |
| Contas e acessos | Buscar conta por e-mail, papéis por condomínio, estado do segundo fator, encerrar sessões, reenviar convite/recuperação | **Novo** (parcial: vínculos na gestão) |
| Concessões técnicas | MASTER de implantação por condomínio | Existe (#144) |
| Chaves e integrações | Cofre write-only por condomínio/conector: gravar, girar, revogar, ver metadados | **Novo** |
| Acesso facial | Catálogo de modelos, homologações, gateways | API existe (V040); tela a fazer |
| Operação | Fila de tarefas, reenvio | Existe |
| Auditoria | Trilha da plataforma | Existe |

## 5. Fatias de implementação

Cada fatia é um PR com testes, CI verde e Codex limpo; migrations aditivas pelo Flyway, aplicadas na homologação
depois do merge. Numeração de migration é a próxima livre no momento do PR (renumerar se a base avançar antes do merge).

1. **Web — entrada e casca do condomínio**: `/c/{condomínio}` com navegação por papel, roteamento automático pós-login,
   seletor só de condomínio, redirecionamento das rotas antigas (`/contextos`, `/gestao`, `/portaria`, `/morador`,
   `/implantacao`) e `/master` → `/central`.
2. **Backend — módulos por condomínio**: tabela de módulos liberados por tenant (plataforma escreve; runtime lê),
   aplicada nas rotas de cada módulo (desligado → 403 `MODULE_DISABLED`), exposta em `/v1/me` para o menu.
3. **Backend — papel MAINTENANCE (zelador)**: vínculo de equipe com capacidade própria (`maintenance.operate`),
   convite pela gestão, leitura/atualização de pedidos atribuídos.
4. **Backend + web — contas e acessos na central**: busca por e-mail, papéis, MFA, encerrar sessões (via Auth),
   reenviar convite/recuperação; sem leitura de senha.
5. **Backend + web — cofre de integrações**: segredos cifrados com chave do servidor, write-only, rotação, metadados,
   auditoria; consumo só pelo worker/gateway.
6. **Web — central consolidada**: financeiro, módulos, contas, cofre e facial em `/central/*` com a identidade visual.

Fora deste documento: produção (`tkxcujckpsuawnzkvpjg`) e qualquer custo novo continuam exigindo OK explícito.
