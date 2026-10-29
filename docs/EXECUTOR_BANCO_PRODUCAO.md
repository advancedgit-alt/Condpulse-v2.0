# Executor manual do banco de produção CondPulse

Workflow `.github/workflows/production-database-operations.yml`, preparado em 29/10/2026 depois da autorização do
responsável para configurar o projeto de produção. Alvo único: projeto Supabase `tkxcujckpsuawnzkvpjg`
(CondPulse-producao, São Paulo), pooler de sessão `aws-1-sa-east-1.pooler.supabase.com:5432`, usuário
`postgres.tkxcujckpsuawnzkvpjg`, TLS `verify-full` com a CA pública do Supabase. O pooler fixado ainda precisa ser
confirmado pelo primeiro `inspect`; se a conexão falhar por tenant inexistente, corrigir o host em PR própria.

Não usa baseline: produção nova recebe V001 em diante pelo migrate normal do Flyway (ADR-0007), sem cópia de dados,
credenciais ou segredos da homologação. Não cria logins runtime/operacionais, não configura Auth, Storage, Vercel ou
flags e não cadastra condomínio.

## Pré-requisito do responsável

1. Criar o GitHub Environment `condpulse-production-database-operations`, com deployment branches selecionadas:
   somente `ccr-5e725112-3x8hyg`.
2. Cadastrar nele somente o secret `CP_PRODUCTION_DATABASE_PASSWORD`, com a senha existente do `postgres` do projeto
   de produção. Não usar a senha da homologação, não colocar em repository secrets, Vercel, chat ou Git, e não
   resetar a senha só para esta etapa sem decidir onde ela ficará guardada.

## Operações

- `inspect` (padrão, não escreve): confere ator `postgres`, TLS da conexão, schemas e tabelas da aplicação,
  ownership e histórico Flyway, e devolve só contagens agregadas.
- `migrate`: exige `maintenance_confirmed=true` e `migration_ack` `tkxcujckpsuawnzkvpjg:DE-ATE:SHA_REVISADO`.
  Banco novo começa em `001`; ATE é a última migration resolvida no JAR do commit revisado. O guard aceita somente o
  marcador de criação de schemas do Flyway e versões SQL contíguas a partir de 1 (histórico com baseline é recusado),
  sem lacuna antes de DE e com ownership `postgres`; em banco sem histórico exige schemas sem objetos da aplicação.
  Validação estrita antes (quando já há histórico) e depois do migrate; callbacks SQL padrão desligados.

O ensaio JDBC descartável do CI cobre banco novo do V001 até a última versão, repetição com zero, faixa curta,
lacuna, histórico com baseline legado, checksum divergente e histórico acima do JAR.

## Limites

Resultado desconhecido depois de iniciar a escrita: conferir o histórico por leitura autorizada antes de repetir;
nunca usar repair/clean nem fabricar histórico. O executor não comprova backup/PITR, restore, capacidade, Auth real
ou separação completa dos ambientes; esses gates continuam em STATUS.md e AMBIENTES.md.
