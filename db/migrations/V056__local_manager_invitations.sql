-- ADR-0013: segundo gestor pelo fluxo local já existente (convite -> aceite PENDING -> decisão de outro gestor).
-- Somente memberships.manage com AAL2 emite. Aceite exige o próprio ator/e-mail do convite e nunca ativa o papel.
-- RLS, grants, vínculo vigente, decisão contra autoaprovação e guarda do último gestor continuam inalterados.
ALTER TABLE cp.membership_invitations DROP CONSTRAINT membership_invitations_role_check;
ALTER TABLE cp.membership_invitations ADD CONSTRAINT membership_invitations_role_check
 CHECK(role IN ('RESIDENT','RESPONSIBLE','DEPENDENT','OWNER','CONCIERGE','MAINTENANCE','MANAGER'));
ALTER TABLE cp.membership_invitations ADD CONSTRAINT membership_invitations_manager_without_unit
 CHECK(role<>'MANAGER' OR unit_id IS NULL);
