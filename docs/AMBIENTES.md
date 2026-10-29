# Inventário dos ambientes CondPulse

Registro do lote de 28/10/2026, em America/Sao_Paulo. Base documental do repositório:
`9b204b282020d285e9de9c0ca30e848930968fe1`, tree
`66bcce68a4ade9d837a383d9acb881820d8a5ce5`. A criação do projeto foi uma operação
do plano de gerenciamento Supabase; esse SHA identifica a base deste registro,
não um deployment ou uma migration executada no projeto novo.

## Inventário atual

| Recurso | Homologação preparatória | Produção reservada |
| --- | --- | --- |
| Nome Supabase | Condpulse-v2.0 | CondPulse-producao |
| Project ref | `rpnrqfrfwywglvyykgrg` | `tkxcujckpsuawnzkvpjg` |
| Região | `sa-east-1`, São Paulo | `sa-east-1`, São Paulo |
| PostgreSQL registrado | `17.11.0.003` | `17.11.0.003`, engine17 |
| Aplicação | Projeto Vercel `condpulse`, origem observada `https://project-rhvdq.vercel.app` | Sem origem de aplicação ou runtime Vercel configurado |
| Banco da aplicação | Baseline oficial002 e V003-V005 já aplicadas, conforme STATUS | Nenhum schema/tabela de aplicação ou histórico Flyway encontrado na conferência agregada |
| Auth e Storage | Preparação própria existente; jornadas reais ainda não aceitas | Cadastro público e anônimo desligados, confirmação de e-mail ligada; zero usuários e buckets; jornadas não configuradas/homologadas |
| Transporte PostgreSQL | CA/hostname do runtime preparados, com limites em STATUS | SSL enforcement ligado pelo diálogo que solicita reinício; saúde confirmada depois; JDBC/runtime ainda não ensaiados |
| Credenciais e jornada | Runtime JDBC e chaves BFF preparados; BFF/reservas continuam desligados | Sem credenciais runtime da aplicação, migrations ou jornada Auth configuradas |

O rótulo Vercel `Production` do deployment atual é o escopo de hospedagem da
aplicação preparatória ligada a `rpnrqfrfwywglvyykgrg`. Não o confundir com o
projeto Supabase reservado para produção nem com aceite operacional do CondPulse.
Não houve alteração nos recursos existentes de homologação durante a criação do
novo projeto. Nenhum recurso, dado ou segredo da Advanced foi utilizado.

## Provisionamento autorizado e conferido

O responsável autorizou expressamente o custo-base incremental de **US$10/mês**
para o segundo projeto. Foi criado `CondPulse-producao`, ref
`tkxcujckpsuawnzkvpjg`, na organização `hkbgyjyyacyiltxqotwj`, região `sa-east-1`.
A leitura posterior confirmou `ACTIVE_HEALTHY` e PostgreSQL `17.11.0.003`.
Não foram contratados adicionais, PITR, domínio, SMTP, VPS ou upgrade neste lote.
O aceite do custo-base não representa aceite de consumo variável ou de serviços
adicionais futuros.

O campo `created_at` retornado pelo provedor foi
`2026-10-29T00:25:18.99128Z`. Esse valor é preservado como metadado do provedor;
não é usado para inferir o relógio real da execução ou inventar um horário local
de conclusão.

A inspeção somente leitura, agregada, encontrou no projeto novo:

- Zero schemas `platform`/`cp` e zero tabelas de aplicação nesses schemas.
- Zero roles de aplicação com prefixo `cp_` e ausência de histórico Flyway da aplicação.
- Zero usuários Auth e zero buckets Storage.
- Security advisor com `lints=[]` na consulta registrada.

Esses resultados da inspeção inicial comprovam o recorte observado, não um
PostgreSQL sem schemas gerenciados, isolamento completo, restore ou aceite de
segurança do produto. O endurecimento posterior está registrado abaixo. Não foram
lidas chaves Auth, criadas fixtures ou aplicadas migrations.
Evidência sanitizada: [production-provisioning-20261028.json](evidence/production-provisioning-20261028.json).

## Endurecimento posterior ao inventário inicial

Somente no projeto `tkxcujckpsuawnzkvpjg`, o painel `/auth/providers` foi usado para
desligar e salvar o cadastro público. A conferência manteve cadastro anônimo
desligado, confirmação de e-mail ligada e vinculação manual de identidades
desligada. Não houve criação/alteração de usuários Auth, troca de senha ou
leitura/cópia de chaves privadas.

No painel `/database/settings`, SSL enforcement passou de desligado para ligado
pelo diálogo nativo Enable SSL, que solicita reinício do banco. A leitura posterior
de metadados confirmou novamente `ACTIVE_HEALTHY`, com a mesma versão PostgreSQL.
Uma consulta posterior confirmou `pg_stat_ssl.ssl=true` na conexão backend
inspecionada e preservou as contagens zero de schemas de aplicação, usuários e
buckets. A evidência não comprova conexão JDBC da aplicação com CA/hostname
verificados, `sslmode=verify-full` ou política de validação do próprio pooler.

Uma leitura do catálogo nativo registrou timestamp do servidor
`2026-10-29T00:37:44.815753Z`, `server_version_num=170011` e ator administrativo
`postgres`. Esses são metadados da consulta, não horário local inferido. O recorte
confirmou ausência dos schemas/tabelas da aplicação. Nenhuma entrada explícita
de privilégio padrão foi encontrada no escopo global ou nos schemas `platform`/`cp`,
considerando todos os criadores e beneficiários. Isso não elimina os padrões
implícitos do PostgreSQL, como EXECUTE para PUBLIC em novas funções, a tratar nas
migrations. A extensão `btree_gist` estava disponível na versão
`1.7`, ainda não instalada; seis event triggers nativos gerenciados foram
inventariados. O catálogo gerenciado foi somente lido, sem alteração de hooks,
ownership ou grants para preparar o Flyway.

Este lote é de inventário e segurança; não cria executor de migrations nem
executa SQL de aplicação. Os schemas/tabelas e o histórico de migrations da
aplicação continuam ausentes. Os ajustes de Auth/SSL não liberam login ou painel.

## O que a separação já comprova e o que falta

**O segundo projeto existe**, está ativo e possui identificador próprio. A
separação completa dos ambientes continua pendente: configurar e verificar
credenciais independentes, Auth, Storage/políticas, origem/ingresso, aplicação,
monitoramento e recuperação. Não copiar credenciais ou dados de homologação para
preencher o projeto de produção, nem ligar a aplicação existente ao projeto novo
como atalho.

O projeto novo está reservado para produção futura. Não há neste registro
origem de aplicação de produção, runtime Vercel conectado, credenciais de execução,
migrations, cadastro operacional ou jornada Auth. `ACTIVE_HEALTHY` descreve o
projeto Supabase, não um painel CondPulse utilizável.

## Próximos gates por ambiente

1. Manter o alvo atual de homologação e suas flags preservados. Concluir no alvo
   restrito o cadastro aprovado, principal operacional/cofre e jornadas do
   [executor de onboarding](EXECUTOR_ONBOARDING.md), sem dados fictícios remotos.
2. Revisar o suporte ao novo alvo em executores e inventários. Os guards atuais
   fixam `rpnrqfrfwywglvyykgrg`; não substituir IDs manualmente para contorná-los.
   Novos alvos exigem revisão/CI, CA/TLS e credenciais próprios, antes de execução.
3. No banco novo, executar **Flyway migrate normal desde V001 até V005** a partir
   da release revisada. Não executar baseline002, não copiar o histórico do alvo
   antigo e não repetir a exceção de bootstrap da ADR-0003. Se
   `createSchemas(true)` gerar um marcador nativo `SCHEMA`, preservar esse registro
   legítimo junto às cinco migrations SQL001-SQL005; não exigir artificialmente
   cinco linhas totais nem criar um registro `BASELINE`. Migrador continua
   separado da API/worker e migrations aplicadas permanecem imutáveis.
4. Configurar produção com identidades de menor privilégio, Auth/Storage/origem
   próprios e segredos somente em seu cofre. Confirmar isolamento por testes
   positivos/negados; API/worker continuam sem DDL, SUPERUSER ou BYPASSRLS.
5. Homologar a versão no tenant de teste do ambiente separado antes de promover
   os artefatos aprovados. Conferir configuração/migrations compatíveis, smoke,
   retorno da aplicação e recuperação; criação do projeto não conclui esses gates.

O [plano de seis fases](MVP_ENTREGA.md) e a
[ADR-0007](adr/0007-tenancy-e-promocao-de-releases.md) continuam válidos.
Provisionar o segundo projeto é progresso da fase1, não conclusão da separação,
habilitação de acesso ou liberação operacional. Registros antigos de “segundo
ambiente pendente” descrevem etapas anteriores; este inventário registra a criação
do projeto, preservando as integrações ainda pendentes.
