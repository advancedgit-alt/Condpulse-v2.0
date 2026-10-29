# Instruções de engenharia para agentes — CondPulse

## Fonte de verdade

Ler README, PRD v1, ADRs, OpenAPI, migrations e ticket atribuído. Produto independente da Advanced. Não conectar ao projeto Supabase legado ou executar comandos no servidor Advanced. Backend Java/Spring; web React/TypeScript; mobile React Native/Expo. Alterar stack só com ADR aprovado.

## Regras inegociáveis

1. Nunca inventar API de fornecedor, segredo, confirmação física, resultado de teste ou ação de implantação.
2. Sem alteração de produção, DNS, cobrança ou contrato sem autorização explícita e gates cumpridos.
3. Toda operação deriva tenant/ator do contexto validado. Nunca confiar em um tenantId de payload.
4. Negócio no backend; autorização por unidade/pessoa além do RLS de tenant.
5. Instante UTC, fuso da área para regras, dinheiro decimal, IDs opacos e versão de registro.
6. Contrações de banco em migrations; Postgres real para testes. Proibido substituir testes de integridade por H2.
7. Outbox na mesma transação; callbacks autenticados e idempotentes. OPEN não tem retry cego.
8. Uma foto salva não é facial ativo. Uma porta acionada não comprova pessoa dentro. Push não comprova leitura.
9. Contratos de API tipados, erros estáveis, estados pendentes/indisponíveis visíveis no frontend.
10. Não gravar biometria, documento, token ou senha em log, commit, prompt de revisão ou screenshot pública.
11. Não instalar dependência só porque simplifica um trecho; registrar justificativa, versão, licença e manutenção.
12. Não afirmar “pronto” até executar testes e anexar evidências; campo não testado deve ser identificado.

## Trabalho paralelo

Um agente coordenador mantém contratos/migrations. Backend, frontend e QA atuam em branches/tickets independentes. Arquivos compartilhados exigem coordenação; não gerar duas migrations com mesma versão nem duas fontes de contrato. Revisão deve ser independente da geração quando possível. PR pequeno por jornada vertical, não um commit gigante de todo o produto.

## Entrega por ticket

Relatar objetivo, arquivos alterados, decisões, testes executados com comando/resultado, testes não executados, risco, migração e rollback. Atualizar OpenAPI/SDK quando alterar contrato. Nunca marcar ticket dependente como pronto só porque o mock funciona.

## Primeiro incremento

Base compilável + identidade/contexto + uma reserva transacional end-to-end com teste de duas confirmações simultâneas e tentativa de acesso entre tenants. Adaptador facial simulado em paralelo; real apenas após documentação do parceiro e autorização de sandbox.
