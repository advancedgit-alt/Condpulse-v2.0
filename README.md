# CondPulse
Sistema residencial independente para moradores, portaria e gestão de condomínios.

**Estado: fundação integrada; V001/V002 e arquivos privados configurados no Supabase de São Paulo. Autenticação BFF em validação; aplicação ainda sem deploy.**
Uma estrutura de banco e um scaffold não equivalem ao produto homologado.

## Base adotada
- Java 25 LTS e Spring Boot 4.1.2; API/BFF e worker separados, no mesmo monólito modular.
- React/TypeScript/Vite na web; React Native/Expo com development build no aplicativo.
- PostgreSQL como único banco principal, em projetos Supabase exclusivos em São Paulo (`sa-east-1`).
- Supabase Auth para identidade e Storage privado para arquivos. Não há necessidade atual de MongoDB.
- Aplicação no Coolify, em host exclusivo CondPulse; infraestrutura ainda não provisionada.
- Outbox/inbox no PostgreSQL, sem Redis/Kafka/LLM como dependência inicial.
- GitHub para código; Woodpecker como executor preferencial, após verificar capacidade/isolamento.

## Documentação de execução
- [Plano detalhado e implantação](docs/PLANO_IMPLANTACAO.md)
- [Decisões e alternativas](docs/adr/0001-arquitetura.md)
- [Banco único: PostgreSQL](docs/adr/0002-postgresql.md)
- [Bootstrap auditável e transição ao Flyway](docs/adr/0003-bootstrap-supabase.md)
- [Integração e habilitação do BFF](docs/BFF_INTEGRACAO.md)
- [Modelo e autorização](docs/DADOS_E_AUTORIZACAO.md)
- [Operação e recuperação](docs/OPERACAO.md)
- [Estado e pendências](docs/STATUS.md)
- [68 tickets do PRD](docs/backlog.json) e [60 casos de aceite](tests/acceptance-catalog.json)

## Diretórios
`apps/backend`: API, domínios e infraestrutura Java.
`apps/web` e `apps/mobile`: contratos de implementação; interfaces ainda pendentes.
`packages`: tokens de design e contrato futuro do cliente.
`db/migrations`: fonte do schema controlado por Flyway, com a exceção inicial documentada na ADR-0003.
`contracts`: OpenAPI da superfície realmente implementada na fundação.
`infra`: build e topologia, sem segredos.
`tests`: isolamento, integridade e concorrência em PostgreSQL descartável.

## Verificação
```sh
node --test tests/foundation.test.mjs
mvn -f apps/backend/pom.xml verify
```
Teste PostgreSQL: leia `tests/database/README.md`.
Não usar credenciais ou dados da Advanced. Nenhuma migration pode ser aplicada diretamente
ao projeto Advanced gerenciado anterior ou à produção self-hosted da Advanced.

