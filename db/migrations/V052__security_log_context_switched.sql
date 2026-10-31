-- R4.4 (PRD complementar Segurança v1.0, §4.2; ADR-0011): trocar de contexto grava CONTEXT_SWITCHED no Log de Segurança
-- para papéis de segurança do parceiro. O tipo já está no catálogo da V050; esta migration só acrescenta a guarda do
-- banco para esse tipo, sem tabela nova e sem mudar a cadeia, os grants ou as políticas da V050.
--  - Papéis de segurança (docs/LOG_SEGURANCA.md): os papéis de parceiro com capacidade de segurança ou de operação
--    (security.* ou ops.*, §4.4): PARTNER_ADMIN (lê o log da carteira), SECURITY_LEAD, SECURITY_SUPERVISOR e
--    REMOTE_OPERATOR. TECHNICIAN (só devices.commission, com concessão técnica que já grava TECH_GRANT_*) fica fora.
--  - Gravação só pela API (cp_api), com ator na transação. O lado do evento (details.side) diz qual contexto alcança o
--    condomínio: TO (destino), FROM (origem) ou BOTH. Cada contexto declarado nesse lado precisa ser papel de segurança
--    de parceiro e estar em platform.partner_reach() para este condomínio no momento da gravação (contrato ativo e, para
--    operador e supervisor, escala vigente). Assim a API nunca grava a troca no log de um condomínio que a conta não
--    alcança, mesmo que a conferência da aplicação falhe.
--  - details mínimo: só from, to e side; cada contexto só com kind (ENTRY, TENANT, PARTNER, PLATFORM) e identificadores
--    (partnerId, role, tenantId), sem nome nem dado pessoal. Severidade INFO; origem REMOTE_CENTRAL ou MANAGEMENT.
--  - Idempotência: o id do evento é derivado pela API (conta, chave da troca, conteúdo e condomínio); a repetição bate
--    na chave primária e a transação inteira é desfeita, inclusive o avanço da cabeça da cadeia.

CREATE FUNCTION cp.security_log_context_roles() RETURNS text[]
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT ARRAY['PARTNER_ADMIN','SECURITY_LEAD','SECURITY_SUPERVISOR','REMOTE_OPERATOR'] $$;
REVOKE ALL ON FUNCTION cp.security_log_context_roles() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_context_roles() TO cp_api;

-- Contexto declarado em details (origem ou destino): forma fechada, só identificadores.
CREATE FUNCTION cp.security_log_context_valid(context jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SET search_path=pg_catalog AS $$
 SELECT context IS NOT NULL AND jsonb_typeof(context)='object'
  AND NOT EXISTS(SELECT 1 FROM jsonb_object_keys(context) k WHERE k NOT IN ('kind','partnerId','role','tenantId'))
  AND (NOT context ? 'partnerId' OR (jsonb_typeof(context->'partnerId')='string'
   AND context->>'partnerId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'))
  AND (NOT context ? 'tenantId' OR (jsonb_typeof(context->'tenantId')='string'
   AND context->>'tenantId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'))
  AND (NOT context ? 'role' OR (jsonb_typeof(context->'role')='string' AND context->>'role' ~ '^[A-Z][A-Z_]{1,39}$'))
  AND CASE context->>'kind'
   WHEN 'ENTRY' THEN NOT (context ?| ARRAY['partnerId','role','tenantId'])
   WHEN 'PLATFORM' THEN NOT (context ?| ARRAY['partnerId','role','tenantId'])
   WHEN 'TENANT' THEN context ? 'tenantId' AND NOT (context ?| ARRAY['partnerId','role'])
   WHEN 'PARTNER' THEN context ?& ARRAY['partnerId','role']
   ELSE false END $$;
REVOKE ALL ON FUNCTION cp.security_log_context_valid(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_context_valid(jsonb) TO cp_api;

-- Contexto de segurança de parceiro que alcança o condomínio agora (lado declarado do evento).
CREATE FUNCTION cp.security_log_context_reaches(context jsonb,target_tenant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog AS $$
 SELECT context->>'kind'='PARTNER' AND context->>'role'=ANY(cp.security_log_context_roles())
  AND (NOT context ? 'tenantId' OR (context->>'tenantId')::uuid=target_tenant)
  AND EXISTS(SELECT 1 FROM platform.partner_reach() r WHERE r.tenant_id=target_tenant
   AND r.partner_id=(context->>'partnerId')::uuid AND r.role=context->>'role') $$;
REVOKE ALL ON FUNCTION cp.security_log_context_reaches(jsonb,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.security_log_context_reaches(jsonb,uuid) TO cp_api;

CREATE FUNCTION cp.security_log_context_switch_guard() RETURNS trigger
LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE side text:=NEW.details->>'side'; target jsonb;
BEGIN
 -- Só a API, com ator: worker e onboarding não executam partner_reach() e nunca gravam troca de contexto.
 IF platform.current_actor_id() IS NULL OR NOT has_function_privilege('platform.partner_reach()','EXECUTE') THEN
  RAISE EXCEPTION 'troca de contexto só é gravada pela API com ator' USING ERRCODE='42501';
 END IF;
 IF EXISTS(SELECT 1 FROM jsonb_object_keys(NEW.details) k WHERE k NOT IN ('from','to','side'))
   OR side IS NULL OR side NOT IN ('TO','FROM','BOTH')
   OR NOT cp.security_log_context_valid(NEW.details->'from') OR NOT cp.security_log_context_valid(NEW.details->'to')
   OR NEW.severity<>'INFO' OR NEW.source NOT IN ('REMOTE_CENTRAL','MANAGEMENT') OR NEW.subject_type IS DISTINCT FROM 'PARTNER'
   OR NEW.unit_id IS NOT NULL OR NEW.point_id IS NOT NULL OR NEW.device_id IS NOT NULL OR NEW.corrects_id IS NOT NULL
   OR NEW.reason IS NOT NULL OR NEW.evidence_ref IS NOT NULL THEN
  RAISE EXCEPTION 'troca de contexto com forma inválida' USING ERRCODE='23514';
 END IF;
 IF side IN ('TO','BOTH') AND NOT cp.security_log_context_reaches(NEW.details->'to',NEW.tenant_id)
   OR side IN ('FROM','BOTH') AND NOT cp.security_log_context_reaches(NEW.details->'from',NEW.tenant_id) THEN
  RAISE EXCEPTION 'contexto de segurança não alcança este condomínio' USING ERRCODE='42501';
 END IF;
 -- O sujeito é o parceiro do lado gravado (destino quando os dois lados alcançam).
 target:=CASE WHEN side='FROM' THEN NEW.details->'from' ELSE NEW.details->'to' END;
 IF NEW.subject_id IS DISTINCT FROM (target->>'partnerId')::uuid THEN
  RAISE EXCEPTION 'sujeito da troca de contexto diferente do parceiro' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION cp.security_log_context_switch_guard() FROM PUBLIC;
-- Antes da cadeia (ordem alfabética dos gatilhos BEFORE: "security_log_a..." < "security_log_chain"): recusa sem tocar a
-- cabeça.
CREATE TRIGGER security_log_a_context_switch BEFORE INSERT ON cp.security_log FOR EACH ROW
 WHEN (NEW.event_type='CONTEXT_SWITCHED') EXECUTE FUNCTION cp.security_log_context_switch_guard();
