# ADR-0007 - Aplicação central, tenants e promoção de versões

Estado: proposta para revisão. Referências: PRD CondPulse v1.0, ADR-0001,
ADR-0004, ADR-0005 e [plano de entrega do MVP](../MVP_ENTREGA.md).

## Contexto

O responsável aprovou a direção de uma aplicação CondPulse central, com o mesmo padrão para
todos os condomínios. Cada condomínio mantém seu tenant, seus usuários, unidades,
regras e dados. Atualizações devem ser testadas antes de alcançar os clientes.
Também é necessário um painel master para operar os condomínios e acompanhar o
financeiro da assinatura do software.

A fundação implementa contexto de tenant, RLS, vínculos e uma fatia residencial de
reservas. O contrato HTTP e a interface atuais não implementam painel master,
cadastro administrativo, assinaturas ou controle de liberação por tenant. As flags
`CP_BFF_ENABLED` e `CP_RESERVATIONS_ENABLED` são do ambiente. Criar um tenant de
teste no mesmo deployment não impede os demais tenants de receberem código novo.

O plano de implantação exige separação entre homologação e produção, incluindo
PostgreSQL, Auth, Storage e credenciais. O segundo ambiente ainda precisa ser
provisionado e verificado; a URL ou o nome de um deployment não comprova essa
separação. Nenhum recurso novo faturável foi criado por este lote documental;
esta proposta não provisiona infraestrutura nem autoriza custo novo. O rótulo
Vercel `Production` do deployment atual é um escopo da plataforma de hospedagem,
não um aceite de produção operacional do CondPulse.

## Decisão proposta

### Aplicação e dados

- Manter um único código e uma linha de releases CondPulse. A produção usa uma
  versão central aprovada, compartilhada pelos tenants.
- Cada condomínio é um tenant com configuração explícita. Não criar forks de
  código por condomínio. A escolha de hospedagem continua conforme ADR-0005.
- Preservar FKs de negócio com `tenant_id`, ENABLE/FORCE RLS e contexto local à
  transação na mesma conexão. API/worker continuam sem DDL, SUPERUSER ou BYPASSRLS.
- Não misturar recursos, dados ou credenciais da Advanced.

### Homologação e tenant de teste

- Usar um ambiente CondPulse de homologação separado da produção, com projetos,
  Auth, buckets, identidades, segredos e origem próprios, com ingresso restrito.
- Manter nesse ambiente um tenant de teste aprovado para validar a mesma base
  funcional dos condomínios. Dados sintéticos continuam nos ensaios descartáveis;
  não importar fixtures do CI para o ambiente remoto real. Dados de piloto exigem
  responsável, finalidade e manifesto aprovado no canal privado.
- Conferir o inventário efetivo antes de ativar jornadas. O projeto remoto
  existente não passa a ser homologação ou produção somente por renomeação.
- Os executores atuais fixam o projeto admitido. Suporte a outro ambiente exige
  revisão dos alvos, guardas, credenciais e evidências; não trocar um identificador
  manualmente para contornar essa proteção.

### Ciclo de atualização

1. Implementar em branch e PR, com revisão independente e CI do head exato.
2. Construir a release rastreável, registrando commit, artefatos imutáveis e
   configuração não secreta por ambiente. Web, API e worker precisam de versões
   compatíveis; migrador permanece um job separado.
3. Aplicar migrations revisadas em homologação pelo Flyway e testar a release no
   tenant de teste, incluindo os cenários negados e de falha.
4. Registrar o aceite da release e a estratégia de recuperação. Promover os mesmos
   artefatos homologados, sem rebuild silencioso, para a produção. A configuração
   própria de produção é conferida separadamente; nenhum segredo é copiado da HML.
5. Executar migrations compatíveis pelo job próprio, smoke e observação. O plano
   deve permitir voltar a aplicação anterior quando compatível; não pressupõe
   rollback automático de migrations ou restauração destrutiva de dados.

A promoção controlada e a recuperação ainda precisam ser implementadas e
demonstradas no caminho Vercel. A integração Git/publicação existente não comprova
que o gate acima já esteja em funcionamento. Antes de clientes em produção,
impedir que merge de desenvolvimento publique diretamente para esses clientes.

Uma atualização da produção compartilhada alcança todos os tenants dessa versão.
Se uma funcionalidade precisar ser liberada primeiro para um condomínio piloto,
implementar flags por tenant, com autorização no servidor, padrão fechado,
auditoria, prazo de remoção e testes. Esse mecanismo é opcional e ainda não existe.
Flags não isolam migrations, infraestrutura ou defeitos no código compartilhado;
o primeiro gate de uma release continua sendo a homologação isolada.

### Painel master e gestão local

- O master é uma superfície de operação da plataforma, com capacidades explícitas.
  O perfil Administrador SaaS previsto no PRD governa contrato, capacidade,
  configuração da plataforma e suporte. Seu identificador técnico e suas
  capacidades ainda dependem do contrato de implementação. O fluxo OPS-01 não concede conteúdo
  residencial por padrão nem permite personificação silenciosa de usuário.
  `MANAGER` continua sendo um papel do condomínio; não vira master nem ganha acesso
  a outros condomínios por inferência.
- Toda ação privilegiada exige conta individual, vínculo/capacidade de plataforma,
  MFA/AAL2 validado no backend e auditoria. Listagens agregadas devem expor somente
  o necessário para a finalidade administrativa, por operações autorizadas.
- Operações dentro de um condomínio selecionam explicitamente o tenant e passam
  pelo contexto/autorização normal. Suporte temporário tem finalidade, vigência,
  escopo e registro; não implica acesso irrestrito ou permanente aos moradores.
- A API master não recebe credencial de dono, Auth Admin ou bypass de RLS. Os
  contratos, políticas/grants mínimos, migrations e testes de acesso cruzado serão
  revisados na implementação; esta decisão não concede privilégios novos.

### Financeiro inicial

O primeiro recorte é a assinatura CondPulse: contrato/plano aprovado, valor e
vigência, competência/vencimento e situação de cobrança com origem e auditoria.
Valores comerciais não são presumidos a partir das hipóteses do PRD. Registro de
pagamento não deve ser confundido com integração de recebimento homologada.

Este recorte de contas a receber SaaS é uma expansão solicitada pelo responsável;
não deve ser registrado como módulo financeiro integral já previsto ou aceito.
Contabilidade do condomínio, folha, orçamento, rateios e emissão de boleto não
entram neste recorte. Gateway, emissão fiscal, cobrança automática ou custo de
provedor exigem escopo e integração próprios. Inadimplência não bloqueia
automaticamente passagem física de moradores.

## Consequências e gates

- A mesma base funcional é atualizada uma vez, preservando dados e regras locais.
- Ambiente de teste e tenant são conceitos distintos; ambos precisam estar
  identificados no inventário e nas evidências.
- Não há promessa de versões independentes por tenant, master pronto, financeiro
  integrado ou promoção automática concluída.
- Novos ambientes/serviços pagos dependem de aceite específico de custo.
- O primeiro teste restrito do condomínio ocorre após as fases 1 a 3 do plano,
  incluindo o painel residencial e a gestão local mínima ainda a implementar. O
  MVP ampliado com master e financeiro básico depende das seis fases. Isso não
  equivale ao aceite de todo o P0/P1 do PRD ou ao lançamento comercial. O gate do
  capítulo 30 do PRD exige 100% dos P0 para o piloto operacional; o teste técnico
  restrito deste plano não substitui esse gate ou processos oficiais do condomínio.
- Cada gate deve registrar source, ambiente, comando/jornada, resultado, revisão
  e limitações; readiness pontual não homologa login, capacidade ou recuperação.

## Alternativas avaliadas

| Alternativa | Avaliação |
| --- | --- |
| Código/deployment independente por condomínio | Aumenta divergência e manutenção; não atende à atualização central solicitada |
| Apenas tenant de teste na produção compartilhada | Não protege os clientes de alterações de código/schema; insuficiente para homologação |
| Homologação isolada e promoção de release central | Proposta adotada para revisão; atende ao fluxo solicitado e preserva separação de dados |
| Versões de aplicação por tenant com roteamento próprio | Complexidade adicional sem requisito atual; avaliar por ADR se necessária |
