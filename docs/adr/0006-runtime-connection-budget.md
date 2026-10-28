# ADR-0006 — Orçamento provisório de conexões runtime

Data:28/10/2026. Decisão de homologação, sujeita a medição e revisão.
Referência: PRD CondPulse v1.0; ADR-0005.

## Contexto

O apply40757578769 concluiu READY no source
2f1abf3547736c247fc082b2cc6ed76c18f2d38b e criou uma identidade restrita
com CONNECTION LIMIT 4. Isso comprovou a conexão de prova do executor e o
transporte dos valores para Vercel, não a conectividade das instâncias Vercel.

No run público40772909749, em duas tentativas, /v1/me respondeu401 com o
problema esperado, mas /actuator/health/readiness respondeu503JSON/no-store.
Na janela21:33–21:36UTC o Supabase registrou 53300/FATAL na mesma role.
Foram observados quatro backends Supavisor SSL sob o teto4. O pool Hikari é2
por instância; em session mode, novos clientes podem conservar backends
atribuídos. Duas instâncias ou overlap de deployment podem consumir esse teto.
Não se presume um pool size15 nem se atribui toda possível falha503 a essa causa.

## Decisão

Adotar teto provisório16 para a identidade runtime, mantendo Hikari2.
A amostra administrativa mostrou max_connections60,12 conexões não-runtime
e0runtime: projetar16runtime nesse baseline resultaria em28/60, deixando32
de folga. Outra amostra mostrou16totais/3runtime. São fotografias do ambiente;
não são garantia de reserva ou dimensionamento de carga.

Novas identidades usam esse teto no helper. A role READY existente só pode
ser ajustada pelo SQL operacional versionado
infra/operations/runtime-connection-budget.sql, após revisão e CI. A execução
é separada do provisionamento da credencial e deve registrar source/hash,
operador, projeto, resultado e readback. O código deste ADR não atesta execução.

O canal administrativo do projeto CondPulse, inclusive MCP autorizado, é
aceitável para esse atributo de LOGIN já gerido fora do Flyway. Flyway continua
sendo o único dono do schema da aplicação; não se cria histórico de migration
Supabase nem se altera migration aplicada para esse ajuste. API/worker não
recebem DDL nem privilégios administrativos.

O SQL é uma instrução DO atômica e usa o mesmo advisory lock do executor
(4850451892997804869). Exige operador/sessão/banco postgres, marker READY
exato da geração/origem aprovada, LOGIN com validade infinita, flags seguras
e associação única a cp_api, com ADMIN=false/INHERIT=true/SET=false.
Recusa ownership/CREATE de schema, privilégio do operador ausente, valores
de limite inesperados ou falta de margem. Só admite4→16;16 é noop.
Antes de mudar, reconfere max_connections60 e exige pelo menos20 conexões de
margem no cenário backends não-runtime atuais + teto16.

A operação modifica somente CONNECTION LIMIT. Não muda senha, validade,
marker, memberships, grants, TLS, RLS, configurações globais ou sessões.
BFF e reservas permanecem desligados.

## Consequências e validação

- O teto deixa de bloquear tão cedo clientes de instâncias sobrepostas, mas
  permanece finito; não se adota limite ilimitado.
- A contagem é aproximada e varia com poolers, instâncias e serviços internos.
  A margem deve ser lida imediatamente antes da operação, não copiada da amostra.
- Após o ajuste, exigir readback dos atributos preservados e nova prova pública
  de readiness200/JSON/UP, autenticação401 esperada e no-store.
- O smoke passa a rodar em todo push na branch padrão e por dispatch nessa
  branch, sempre aguardando o status Vercel do próprio SHA. Continua somente
  leitura, sem Environment/CPsecrets, cookies, Bearer de usuário ou corpos brutos.
- Carga, p95, cold start, escala, PITR/restore, Auth real e aceite P0/P1 continuam
  pendentes. Uma respostaUP pontual não homologa capacidade ou o produto.
- Se houver503 persistente, investigar e registrar; não mascarar o resultado,
  retirar db da readiness, trocar de pooler sem ensaio ou afrouxar TLS/RLS.

## Fontes primárias

- https://supabase.com/kb/troubleshooting/supavisor-faq-YyP5tI
- https://supabase.com/docs/guides/database/connection-management
- https://www.postgresql.org/docs/17/sql-alterrole.html
- https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS
