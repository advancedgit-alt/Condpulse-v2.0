# CondPulse — regras de execução
- Português do Brasil; fuso America/Sao_Paulo; nome canônico CondPulse.
- Leia CLAUDE.md, docs/PLANO_IMPLANTACAO.md e docs/STATUS.md.
- Requisitos: PRD CondPulse v1.0, 27/10/2026. Desvios exigem ADR e evidência.
- Trabalhe em branch própria, PR e revisão antes de merge; sem force push.
- Gasto pago só com OK explícito, conforme CLAUDE.md.
- Não alterar recursos, dados ou segredos da Advanced.
- Nunca commitar segredos, fotos biométricas, dados pessoais reais ou dumps.
- Fixtures são sintéticas. Não usar mocks/demonstração como dados de produção.
- Flyway é o único dono do schema da aplicação. Migration aplicada é imutável.
- Exceção única de bootstrap vazio: ADR-0003, source/hash atestados e baseline oficial explícito depois; nunca fabricar histórico Flyway.
- API/worker não usam SUPERUSER, BYPASSRLS ou DDL.
- Tenant em toda relação de negócio; contexto local à transação e mesma conexão.
- Não desativar autenticação, RLS ou constraints para passar testes.
- Nenhuma chamada externa dentro da transação de negócio.
- Hardware só fica ativo com confirmação da versão atual nos destinos exigidos.
- Não inventar endpoints de fornecedor. Facial/abertura remota começam desligados.
- Marcar testes não executados quando não houver evidência objetiva.
- Atualizar status por ticket, comando, ambiente, commit e resultado.

