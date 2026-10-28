# Transição do bootstrap para Flyway

Operação única da ADR-0003 no projeto CondPulse `rpnrqfrfwywglvyykgrg`, São Paulo.
O executor está separado da API e não aplica V003. O remoto ainda não recebeu baseline.

## O que foi conferido

A consulta somente leitura de 27/10/2026 comparou o banco com o manifesto imutável:
13 ACLs de tabelas,124 colunas,2 migrations nativas,37 índices,14 policies e101 constraints
coincidem. Também coincidem ownership/RLS, schemas, funções, permissões por coluna,
roles e ausência de objetos/privilégios inesperados. O advisor de segurança retornou
`lints=[]`; isso não constitui uma auditoria completa. Resultado resumido, sem dados
de negócio: [baseline-readiness-20261027.json](evidence/baseline-readiness-20261027.json).

O manifesto original registra o estado histórico do bootstrap. Não alterar seu
`baselineExecuted=false`, hashes, fontes ou histórico nativo depois de executar:
registrar a nova evidência em outro documento. O executor confere o manifesto inteiro
e os bytes canônicos de V001/V002, distinguindo o LF adicional da representação executada.

## Artefato e identidade

O target `baseline` de infra/Dockerfile.api usa o mesmo source commit da release,
com classes/dependências extraídas do JAR. Seu entrypoint é BootstrapBaseline,
sem iniciar Spring, servidor HTTP, API, worker ou migrations automáticas. O target
final padrão continua `api`, com a classe principal Spring explicitamente fixada.

Construir e testar fora do host de produção, publicar o artefato no registro aprovado
e registrar seu digest próprio em CP_BASELINE_IMAGE. Esse digest difere da imagem API;
ambos precisam vir do mesmo commit revisado. O CI testa o launcher e os recursos
embutidos, mas não publica imagens nem fornece credenciais remotas.

Usar infra/compose.baseline.yaml como operação manual separada. O inventário
infra/baseline.env.example contém somente marcadores. A identidade DDL e a CA vêm do
cofre; jamais importar esses valores na aplicação. O atestado atual exige ator e
ownership `postgres`, como no bootstrap. O prefixo cp_migrator_ aceito pela sintaxe
da conexão não dispensa essa atestação: outra identidade/transferência de ownership
exige revisão específica antes desta exceção. Não mudar o esperado para fazer passar.

JDBC usa conexão direta ou pooler de sessão do próprio projeto, porta5432,
`sslmode=verify-full` e CA em `/run/secrets/db-ca.crt`. O CLI não possui flag para
desligar TLS ou aceitar banco local. O helper descartável dos testes não é acessível
por argumentos ou variáveis do comando de produção. O ensaio JDBC remoto continua pendente.

## Sequência de operação

1. Conferir orçamento/ambiente isolado, backup compatível, identidade DDL, CA e digest.
   Coordenar manutenção exclusiva, suspender outros migradores e qualquer DDL externo.
   O advisory lock serializa este executor; não impede alterações por uma ferramenta
   ou operador que não participe do mesmo lock. A inspeção e a chamada oficial Flyway
   usam conexões diferentes. O fingerprint não elimina essa janela de concorrência.
2. Usar Docker Compose5.2.0 e validar o manifesto antes de executar. O JSON expandido
   contém credenciais e deve passar apenas pelo pipe, sem `tee`, arquivo de log ou upload.

```sh
set -o pipefail
docker compose --project-name condpulse-baseline --env-file /run/condpulse/baseline.env -f infra/compose.baseline.yaml config --format json | node infra/validate-release.mjs --baseline
```

3. Executar inspeção. `inspect` é também o comando padrão do manifesto e do CLI.

```sh
docker compose --project-name condpulse-baseline --env-file /run/condpulse/baseline.env -f infra/compose.baseline.yaml run --rm --no-deps baseline inspect
```

O resultado contém somente `eligible`, `baselineCreated`, `fingerprint` e códigos
controlados. Inspeção não cria schemas, tabelas ou histórico. Exige ausência de qualquer
flyway_schema_history, inventário íntegro e configuração correspondente ao projeto.
Falha impede a operação; nunca contornar o atestador, RLS ou constraints.

4. Após conferir a inspeção e a manutenção, registrar a aprovação da operação e
   preencher CP_BASELINE_ACK no ambiente seguro com `rpnrqfrfwywglvyykgrg:002:FINGERPRINT`.
   FINGERPRINT é exatamente o SHA256 retornado pela inspeção, não uma senha.
   O CLI reavalia o estado sob lock e exige correspondência antes de chamar Flyway.
5. Acionar explicitamente o único comando de escrita:

```sh
docker compose --project-name condpulse-baseline --env-file /run/condpulse/baseline.env -f infra/compose.baseline.yaml run --rm --no-deps baseline baseline
```

O código usa a API oficial `Flyway.baseline()` na versão002, com createSchemas e
baselineOnMigrate desabilitados, clean desabilitado e schemas platform/cp. Nunca
insere linhas manualmente no histórico, chama repair/clean ou executa migrate.
Confirma depois o marcador BASELINE2 único e bem-sucedido. V001/V002 não ganham checksums
retroativos: a equivalência é demonstrada pela atestação, não pelo marcador.

6. Registrar saída, commit/digests, aprovador, ambiente e conferência do marcador.
   Remover ACK/credenciais temporárias conforme a política do cofre. Não reutilizar
   este executor em um ambiente novo: banco novo segue o migrate normal desde V001.
7. Somente depois desse aceite, executar validate/migrate paraV003 pelo job separado
   descrito em DEPLOY_COOLIFY.md. O baseline não habilita BFF ou reservas.

Após o baseline, `validate` isolado sinaliza V003 ainda pendente. Conferir que essa
é a única migration pendente e o único diagnóstico de validação. O migrador normal
mantém validateOnMigrate habilitado, aplica V003 e então passa na validação estrita
e no replay sem alterações. Não ignorar erros de checksum, migrations ausentes ou
qualquer outro diagnóstico para liberar a execução.

## Falha e recuperação

Em erro anterior à chamada Flyway, o executor não cria histórico. Em
`BASELINE_RESULT_UNKNOWN` ou `BASELINE_MARKER_INVALID`, interromper a promoção e
conferir o histórico por leitura autorizada. `baselineCreated=false` nesses casos
não prova ausência de efeito. Não repetir cegamente, apagar o histórico, mudar
baselineVersion, preencher linhas ou usar repair como recuperação automática.

Depois do baseline, a inspeção pré-bootstrap deixa de ser elegível por desenho:
histórico Flyway presente é uma condição de bloqueio. A validação das próximas
migrations passa ao migrador normal. Preservar o registro da operação inicial.

## Evidência e limites

O CI usa banco exclusivo condpulse_bootstrap_ci, protegido por guards e valores
sintéticos, sem conexão remota. Reconstrói V001/V002 e o histórico nativo das fontes Git
e wrappers conhecidos; não usa dump ou histórico Flyway fabricado. A suíte cobre
inspeção, alterações indevidas, ACK, baseline oficial, concorrência, resultado incerto
e aplicação separada de V003. O teste de imagem confere manifesto/recursos e saída
sanitizada sem rede. Resultados por commit ficam na PR desta etapa.

Ainda pendentes: credenciais/cofre e executor remoto aprovados, janela de manutenção,
TLS/pooler/Auth reais, backup/restore, publicação das imagens e ambiente dedicado.
Nenhuma migration, credencial ou recurso da Advanced participa desta operação.

Referência oficial: https://documentation.red-gate.com/flyway/reference/commands/baseline
