# ADR-0013 — Gestores adicionais pelo convite local

Estado: implementação para homologação de 01/11/2026. O responsável confirmou a unidade de morador e a concessão de gestão à segunda conta de teste do demo para validar o processo de aprovação. Essa autorização não cria outros papéis ou acessos automaticamente.

## Problema

Depois do primeiro gestor, a Central orienta que novos gestores sejam incluídos pela gestão local. O formulário, o tipo web InvitationRole, a API e a constraint de convites ainda excluíam MANAGER. Isso impedia o percurso normal sem intervenção no banco. A restrição antiga era coberta por um teste explícito; esta decisão substitui somente essa proibição de convidar gestores, sem alterar a aprovação do vínculo.

## Decisão

- O primeiro gestor continua sendo responsabilidade da Central e do convite inicial existente.
- Um gestor local vigente, com memberships.manage e AAL2, pode emitir convite MANAGER para outra conta do mesmo condomínio. Sem unidade, validade curta, token de uso único e destinatário conferido pelo e-mail autenticado, como os convites locais existentes.
- O aceite produz somente PENDING. A ativação depende de decisão de outro gestor vigente com AAL2. O aceite nunca libera gestão de imediato; MEMBERSHIP_SELF_DECISION_DENIED permanece.
- SUPPORT não entra no catálogo de convite. Não há alteração de RLS, grants de runtime, autoaprovação, guarda do último gestor ou criação de conta no provedor.
- V056 estende a whitelist do convite e exige MANAGER sem unidade no banco. Migrations aplicadas permanecem imutáveis. Nenhum vínculo remoto é criado pela migration.
- A interface avisa o alcance de gestão e a necessidade de aprovação. A correção é o fluxo de produto, não uma inserção de ator ou autorização por SQL.

## Evidência e limite

Regressão identificada na Central/gestão da HML em 01/11. JDBC verifica emissão por gestor/AAL2, negações de morador/AAL1/outro tenant/unidade, aceite pendente, e-mail incorreto, ativação por outro gestor e uso único. O cenário de navegador verifica payload sem unidade, aviso e aceite pendente. Testes anteriores mantêm a recusa de autoaprovação e a proteção do último gestor.

Aplicação de V056, publicação e percurso com contas reais devem ser registrados em ESTADO_ATUAL e STATUS com os recibos. CI não substitui esse percurso.
