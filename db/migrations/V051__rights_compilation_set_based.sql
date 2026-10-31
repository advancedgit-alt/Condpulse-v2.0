-- Custo da derivação/publicação de direitos com centenas de direitos por sujeito (AccessGrantJdbcTest, 512 autorizações
-- funcionais estourava o prazo de 5 s da transação). Medição em PostgreSQL descartável (pg_stat_xact_user_functions):
--  - cp.subject_context_current era chamada uma vez por direito dentro de cp.access_grants_current_now (V047), ou seja,
--    a cada compilação do sujeito; a função não é inlinada (SET search_path) e cada chamada reavalia o RLS de
--    subject_contexts, access_subjects, memberships e units (3.609 chamadas, ~1,7 s na derivação).
--  - cp.rights_compiled refazia cp.rights_targets do sujeito inteiro por destino, e o planejador ainda chamava
--    cp.rights_destinations (outra passagem completa) antes: N+1 por equipamento.
--  - Os predicados de RLS sem dependência da linha (capacidades e tenant do contexto) eram avaliados por linha
--    (platform.has_capability ~43 mil chamadas numa derivação). Em subconsulta escalar o PostgreSQL os avalia uma vez
--    por consulta (InitPlan), com o mesmo resultado: as funções são STABLE e só leem o contexto da transação.
-- Nada muda no que é visível, gravado, compilado ou publicado: mesmos predicados, mesma ordem e mesmo hash; a
-- paridade é conferida no harness (tests/database/run.py) para todos os contextos, direitos e destinos.

-- 1. Vigência dos contextos em conjunto: o mesmo predicado de cp.subject_context_current (V038), avaliado de uma vez.
CREATE FUNCTION cp.subject_contexts_current_now(target_tenant uuid,target_subject uuid) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT c.id
  FROM cp.subject_contexts c JOIN cp.access_subjects s ON s.tenant_id=c.tenant_id AND s.id=c.subject_id
  WHERE c.tenant_id=target_tenant AND (target_subject IS NULL OR c.subject_id=target_subject)
   AND c.status='ACTIVE' AND s.status='ACTIVE' AND c.valid_from<=now() AND (c.valid_until IS NULL OR c.valid_until>now())
   AND CASE c.source_kind
    WHEN 'MEMBERSHIP' THEN EXISTS(SELECT 1 FROM cp.memberships m LEFT JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id
      WHERE m.tenant_id=c.tenant_id AND m.id=c.membership_id AND m.status='ACTIVE' AND m.version=c.source_version
       AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now()) AND (m.unit_id IS NULL OR u.status='ACTIVE'))
      AND cp.membership_context_kind(c.tenant_id,c.membership_id)=c.context_kind
    WHEN 'VISIT_PERMIT' THEN EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=c.tenant_id AND p.id=c.permit_id
      AND p.state='ACTIVE' AND p.version=c.source_version AND p.valid_from<=now() AND p.valid_until>now())
    ELSE true END $$;
REVOKE ALL ON FUNCTION cp.subject_contexts_current_now(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.subject_contexts_current_now(uuid,uuid) TO cp_api;

-- 2. Direitos vigentes na origem (V047): o contexto passa a vir do conjunto acima (a FK (tenant, contexto, sujeito) de
-- cp.access_grants garante que o contexto do direito é do mesmo sujeito). Demais predicados idênticos.
CREATE OR REPLACE FUNCTION cp.access_grants_current_now(target_tenant uuid,target_subject uuid,target_area uuid) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ WITH ctx AS MATERIALIZED (SELECT x.id FROM cp.subject_contexts_current_now(target_tenant,target_subject) x(id))
  SELECT g.id
  FROM cp.access_grants g
  CROSS JOIN LATERAL (SELECT greatest(clock_timestamp(),g.valid_from) at) t
  JOIN cp.access_subjects s ON s.tenant_id=g.tenant_id AND s.id=g.subject_id
  JOIN cp.access_policies p ON p.tenant_id=g.tenant_id AND p.id=g.policy_id
  JOIN cp.access_policy_versions v ON v.tenant_id=g.tenant_id AND v.id=g.policy_version_id
  JOIN cp.access_areas a ON a.tenant_id=g.tenant_id AND a.id=g.area_id
  WHERE g.tenant_id=target_tenant AND (target_subject IS NULL OR g.subject_id=target_subject) AND (target_area IS NULL OR g.area_id=target_area)
   AND g.status='ACTIVE' AND g.valid_from<=t.at AND g.valid_until>t.at
   AND s.status='ACTIVE' AND s.restriction_epoch=g.subject_restriction_epoch
   AND EXISTS(SELECT 1 FROM ctx WHERE ctx.id=g.context_id)
   AND p.status<>'RETIRED' AND v.state IN ('APPROVED','PUBLISHED')
   AND a.status='ACTIVE'
   AND CASE g.origin_kind
    WHEN 'VISIT_PERMIT' THEN EXISTS(SELECT 1 FROM cp.visit_permits vp WHERE vp.tenant_id=g.tenant_id AND vp.id=g.origin_permit_id
      AND vp.state='ACTIVE' AND vp.version=g.origin_version AND vp.valid_until>t.at
      AND (vp.reservation_id IS NULL OR EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=vp.tenant_id AND r.id=vp.reservation_id
       AND r.status='CONFIRMED')))
    WHEN 'RESERVATION' THEN EXISTS(SELECT 1 FROM cp.reservations r WHERE r.tenant_id=g.tenant_id AND r.id=g.origin_reservation_id
      AND r.status='CONFIRMED' AND r.version=g.origin_version)
    ELSE true END
   AND (g.age_evidence_id IS NULL OR EXISTS(SELECT 1 FROM cp.age_evidence e WHERE e.tenant_id=g.tenant_id AND e.id=g.age_evidence_id
      AND e.status='VALID' AND e.valid_until>t.at))
   AND NOT EXISTS(SELECT 1 FROM cp.revocation_markers m WHERE m.tenant_id=g.tenant_id AND m.subject_id=g.subject_id
      AND m.revoked_through_generation>g.generation
      AND ((m.target_kind='SUBJECT' AND m.subject_restriction_epoch>=g.subject_restriction_epoch)
       OR (m.target_kind='CONTEXT' AND m.context_id=g.context_id) OR (m.target_kind='GRANT' AND m.grant_id=g.id))) $$;

-- 3. Entradas de todos os destinos do sujeito numa só passagem de cp.rights_targets (mesmo objeto e mesma ordem de
-- cp.rights_entries, V047). Destino só com alvos fora do documento aparece com '[]'.
CREATE FUNCTION cp.rights_entries_set(target_tenant uuid,target_subject uuid) RETURNS TABLE(device_id uuid,entries jsonb)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT x.device_id,coalesce(jsonb_agg(jsonb_build_object('grantId',x.grant_id,'areaId',x.area_id,'pointId',x.point_id,'bindingId',x.binding_id,
   'bindingRevision',x.binding_revision,'channelId',x.channel_id,'direction',x.direction,'deviceRole',x.device_role,'generation',x.generation,
   'validFrom',to_char(x.valid_from AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
   'validUntil',to_char(x.valid_until AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
   ORDER BY x.grant_id,x.point_id,x.direction,x.device_role) FILTER (WHERE x.issue IS NULL AND x.is_current),'[]'::jsonb)
  FROM cp.rights_targets(target_tenant,target_subject) x
  GROUP BY x.device_id $$;
REVOKE ALL ON FUNCTION cp.rights_entries_set(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_entries_set(uuid,uuid) TO cp_api;

CREATE OR REPLACE FUNCTION cp.rights_entries(target_tenant uuid,target_subject uuid,target_device uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT coalesce((SELECT e.entries FROM cp.rights_entries_set(target_tenant,target_subject) e WHERE e.device_id=target_device),'[]'::jsonb) $$;

-- 4. Documentos compilados de vários destinos com uma só passagem dos alvos. target_devices null = todos os destinos do
-- sujeito (os de cp.rights_destinations: alvos e equipamentos que já receberam trabalho dele). Fonte única do documento:
-- cp.rights_compiled (gatilho da publicação) passa a ser este conjunto com um destino, então planejador e gatilho
-- calculam o mesmo hash por construção. Acima de 512 entradas: falha fechada (retira tudo do destino).
CREATE FUNCTION cp.rights_compiled_set(target_tenant uuid,target_subject uuid,target_devices uuid[]) RETURNS TABLE(device_id uuid,document jsonb)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ WITH e AS MATERIALIZED (SELECT * FROM cp.rights_entries_set(target_tenant,target_subject)),
  d AS (SELECT DISTINCT u.device FROM unnest(coalesce(target_devices,ARRAY(SELECT e.device_id FROM e
    UNION SELECT r.device_id FROM cp.rights_publications r WHERE r.tenant_id=target_tenant AND r.subject_id=target_subject))) u(device))
  SELECT d.device,CASE WHEN s.status<>'ACTIVE' THEN jsonb_build_object('schemaVersion','1.0','subjectRef',s.external_ref,'action','REVOKE_ALL',
    'subjectRestrictionEpoch',s.restriction_epoch,'coveredMethods',jsonb_build_array('FACE','CARD','PIN','QR','INTERMEDIARY_GRANT'),'entries','[]'::jsonb)
   ELSE jsonb_build_object('schemaVersion','1.0','subjectRef',s.external_ref,
     'action',CASE WHEN jsonb_array_length(x.e)>0 AND jsonb_array_length(x.e)<=512 THEN 'APPLY_RIGHTS' ELSE 'REVOKE_RIGHTS' END,
     'subjectRestrictionEpoch',s.restriction_epoch,'coveredMethods','[]'::jsonb,
     'entries',CASE WHEN jsonb_array_length(x.e)<=512 THEN x.e ELSE '[]'::jsonb END) END
  FROM cp.access_subjects s CROSS JOIN d
  CROSS JOIN LATERAL (SELECT coalesce((SELECT e.entries FROM e WHERE e.device_id=d.device),'[]'::jsonb) e) x
  WHERE s.tenant_id=target_tenant AND s.id=target_subject $$;
REVOKE ALL ON FUNCTION cp.rights_compiled_set(uuid,uuid,uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION cp.rights_compiled_set(uuid,uuid,uuid[]) TO cp_api;

CREATE OR REPLACE FUNCTION cp.rights_compiled(target_tenant uuid,target_subject uuid,target_device uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ SELECT c.document FROM cp.rights_compiled_set(target_tenant,target_subject,ARRAY[target_device]) c $$;

-- 5. Vigência de um contexto (V038), chamada uma vez por direito pelo gatilho de cp.access_grants (V044) e pelas
-- leituras de sujeitos: mesmo predicado, agora em PL/pgSQL. Função SQL com SET não é inlinada e era replanejada a cada
-- chamada (~1,3 ms); em PL/pgSQL o plano fica em cache na sessão. RLS do chamador vale do mesmo jeito (INVOKER).
CREATE OR REPLACE FUNCTION cp.subject_context_current(target_tenant uuid,target_context uuid) RETURNS boolean
LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path=pg_catalog
AS $$ BEGIN
 RETURN coalesce((SELECT c.status='ACTIVE' AND s.status='ACTIVE' AND c.valid_from<=now() AND (c.valid_until IS NULL OR c.valid_until>now())
   AND CASE c.source_kind
    WHEN 'MEMBERSHIP' THEN EXISTS(SELECT 1 FROM cp.memberships m LEFT JOIN cp.units u ON u.tenant_id=m.tenant_id AND u.id=m.unit_id
      WHERE m.tenant_id=c.tenant_id AND m.id=c.membership_id AND m.status='ACTIVE' AND m.version=c.source_version
       AND m.valid_from<=now() AND (m.valid_until IS NULL OR m.valid_until>now()) AND (m.unit_id IS NULL OR u.status='ACTIVE'))
      AND cp.membership_context_kind(c.tenant_id,c.membership_id)=c.context_kind
    WHEN 'VISIT_PERMIT' THEN EXISTS(SELECT 1 FROM cp.visit_permits p WHERE p.tenant_id=c.tenant_id AND p.id=c.permit_id
      AND p.state='ACTIVE' AND p.version=c.source_version AND p.valid_from<=now() AND p.valid_until>now())
    ELSE true END
  FROM cp.subject_contexts c JOIN cp.access_subjects s ON s.tenant_id=c.tenant_id AND s.id=c.subject_id
  WHERE c.tenant_id=target_tenant AND c.id=target_context),false);
END $$;

-- 6. RLS das tabelas lidas na derivação e na compilação: os mesmos predicados, com as funções que não dependem da
-- linha (capacidades, escopos técnicos, ator e tenant do contexto da transação) em subconsulta escalar, avaliada uma vez
-- por consulta em vez de uma vez por linha. Funções com argumento da linha (por exemplo cp.rental_owner_hidden,
-- cp.edge_device) continuam por linha. ALTER POLICY mantém nome, comando, roles e o caráter permissivo/restritivo.
-- cp.memberships fica de fora: a política de inserção por convite lê platform.tenants, cuja política lê cp.memberships;
-- com subconsulta nas políticas de cp.memberships o PostgreSQL recusaria esse ciclo (recursão infinita de política).
-- Nenhuma das tabelas abaixo está num ciclo de políticas (conferido em pg_depend das políticas).
ALTER POLICY access_areas_change ON cp.access_areas
 USING (((SELECT platform.has_capability('area.manage'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text))))
 WITH CHECK (((SELECT platform.has_capability('area.manage'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text))));
ALTER POLICY access_areas_create ON cp.access_areas
 WITH CHECK ((((SELECT platform.has_capability('area.manage'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text))) AND (created_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_areas_read ON cp.access_areas
 USING ((SELECT cp.access_map_reader()));
ALTER POLICY tenant_boundary ON cp.access_areas
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY access_devices_maintain ON cp.access_devices
 USING ((SELECT platform.has_technical_scope('installation.manage'::text)))
 WITH CHECK ((SELECT platform.has_technical_scope('installation.manage'::text)));
ALTER POLICY access_devices_read ON cp.access_devices
 USING (((SELECT platform.has_capability('memberships.manage'::text)) OR (SELECT platform.has_capability('concierge.operate'::text)) OR (SELECT platform.has_technical_scope('installation.read'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text)) OR (SELECT platform.has_technical_scope('installation.bind_channel'::text)) OR (SELECT platform.has_technical_scope('installation.commission'::text)) OR (EXISTS ( SELECT 1
   FROM cp.access_credentials c
  WHERE ((c.tenant_id = access_devices.tenant_id) AND (c.connector_id = access_devices.connector_id)))) OR (((SELECT cp.edge_credential()) IS NOT NULL) AND (connector_id = (SELECT cp.edge_connector())))));
ALTER POLICY access_devices_register ON cp.access_devices
 WITH CHECK (((SELECT platform.has_technical_scope('installation.manage'::text)) AND (registered_by = (SELECT platform.current_actor_id()))));
ALTER POLICY tenant_boundary ON cp.access_devices
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY access_grants_close ON cp.access_grants
 USING ((SELECT cp.grant_reader()))
 WITH CHECK (((status = 'REVOKED'::text) OR (SELECT cp.grant_manager())));
ALTER POLICY access_grants_create ON cp.access_grants
 WITH CHECK (((SELECT cp.grant_manager()) AND (derived_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_grants_read ON cp.access_grants
 USING ((SELECT cp.grant_reader()));
ALTER POLICY tenant_boundary ON cp.access_grants
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY access_points_change ON cp.access_points
 USING (((SELECT platform.has_technical_scope('installation.manage'::text)) OR (SELECT platform.has_technical_scope('installation.commission'::text))))
 WITH CHECK ((((SELECT platform.has_technical_scope('installation.manage'::text)) OR (SELECT platform.has_technical_scope('installation.commission'::text))) AND (updated_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_points_create ON cp.access_points
 WITH CHECK (((SELECT platform.has_technical_scope('installation.manage'::text)) AND (created_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_points_read ON cp.access_points
 USING ((SELECT cp.access_map_reader()));
ALTER POLICY tenant_boundary ON cp.access_points
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY access_policies_change ON cp.access_policies
 USING ((SELECT platform.has_capability('policy.edit'::text)))
 WITH CHECK (((SELECT platform.has_capability('policy.edit'::text)) AND (updated_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_policies_create ON cp.access_policies
 WITH CHECK (((SELECT platform.has_capability('policy.edit'::text)) AND (created_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_policies_read ON cp.access_policies
 USING ((SELECT cp.policy_reader()));
ALTER POLICY tenant_boundary ON cp.access_policies
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY access_policy_versions_change ON cp.access_policy_versions
 USING ((SELECT cp.policy_reader()))
 WITH CHECK ((SELECT cp.policy_reader()));
ALTER POLICY access_policy_versions_create ON cp.access_policy_versions
 WITH CHECK (((SELECT platform.has_capability('policy.edit'::text)) AND (authored_by = (SELECT platform.current_actor_id()))));
ALTER POLICY access_policy_versions_read ON cp.access_policy_versions
 USING ((SELECT cp.policy_reader()));
ALTER POLICY tenant_boundary ON cp.access_policy_versions
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY access_subjects_create ON cp.access_subjects
 WITH CHECK ((((origin_kind = 'ACCOUNT'::text) AND ((SELECT platform.has_capability('memberships.manage'::text)) OR (SELECT platform.has_technical_scope('person.review'::text)))) OR ((origin_kind = 'VISITOR'::text) AND (SELECT platform.has_capability('concierge.operate'::text)))));
ALTER POLICY access_subjects_read ON cp.access_subjects
 USING (((SELECT cp.subject_staff()) OR (account_id = (SELECT platform.current_actor_id()))));
ALTER POLICY access_subjects_restrict ON cp.access_subjects
 USING ((SELECT platform.has_capability('memberships.manage'::text)))
 WITH CHECK (((SELECT platform.has_capability('memberships.manage'::text)) AND (status = ANY (ARRAY['ACTIVE'::text, 'BLOCKED'::text])) AND ((status = 'ACTIVE'::text) OR (blocked_by = (SELECT platform.current_actor_id())))));
ALTER POLICY tenant_boundary ON cp.access_subjects
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY point_bindings_change ON cp.point_bindings
 USING (((SELECT platform.has_technical_scope('installation.bind_channel'::text)) OR (SELECT platform.has_technical_scope('installation.commission'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text))))
 WITH CHECK (((SELECT platform.has_technical_scope('installation.bind_channel'::text)) OR (SELECT platform.has_technical_scope('installation.commission'::text)) OR (SELECT platform.has_technical_scope('installation.manage'::text))));
ALTER POLICY point_bindings_create ON cp.point_bindings
 WITH CHECK (((SELECT platform.has_technical_scope('installation.bind_channel'::text)) AND (created_by = (SELECT platform.current_actor_id()))));
ALTER POLICY point_bindings_read ON cp.point_bindings
 USING (((SELECT cp.installation_technical()) OR (SELECT cp.policy_destination_scan())));
ALTER POLICY tenant_boundary ON cp.point_bindings
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY revocation_markers_create ON cp.revocation_markers
 WITH CHECK (((SELECT cp.subject_staff()) OR (SELECT cp.grant_manager())));
ALTER POLICY revocation_markers_read ON cp.revocation_markers
 USING ((SELECT cp.grant_reader()));
ALTER POLICY tenant_boundary ON cp.revocation_markers
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY rights_publications_create ON cp.rights_publications
 WITH CHECK (((SELECT cp.grant_reader()) AND ((origin = 'REVOCATION'::text) OR (SELECT cp.grant_manager()))));
ALTER POLICY rights_publications_read ON cp.rights_publications
 USING (((SELECT cp.grant_reader()) OR cp.edge_device(tenant_id, device_id)));
ALTER POLICY rights_publications_update ON cp.rights_publications
 USING (((SELECT cp.grant_reader()) OR cp.edge_lease_holder(tenant_id, id)))
 WITH CHECK ((((state = 'SUPERSEDED'::text) AND ((SELECT cp.edge_credential()) IS NULL)) OR ((state <> 'SUPERSEDED'::text) AND cp.edge_lease_holder(tenant_id, id))));
ALTER POLICY tenant_boundary ON cp.rights_publications
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY subject_contexts_close ON cp.subject_contexts
 USING (((SELECT platform.has_capability('memberships.manage'::text)) OR ((source_kind = 'VISIT_PERMIT'::text) AND (SELECT platform.has_capability('concierge.operate'::text)))))
 WITH CHECK (((SELECT platform.has_capability('memberships.manage'::text)) OR ((source_kind = 'VISIT_PERMIT'::text) AND (SELECT platform.has_capability('concierge.operate'::text)))));
ALTER POLICY subject_contexts_create ON cp.subject_contexts
 WITH CHECK (((created_by = (SELECT platform.current_actor_id())) AND ((reauthorizes_context_id IS NULL) OR (SELECT platform.has_capability('memberships.manage'::text))) AND (((source_kind = ANY (ARRAY['MEMBERSHIP'::text, 'FUNCTIONAL_AUTHORIZATION'::text])) AND ((SELECT platform.has_capability('memberships.manage'::text)) OR (SELECT platform.has_technical_scope('person.review'::text))) AND ((source_kind = 'MEMBERSHIP'::text) OR (approved_by_account_id = (SELECT platform.current_actor_id())))) OR ((source_kind = 'VISIT_PERMIT'::text) AND (SELECT platform.has_capability('concierge.operate'::text))))));
ALTER POLICY subject_contexts_read ON cp.subject_contexts
 USING (((SELECT cp.subject_staff()) OR (EXISTS ( SELECT 1
   FROM cp.access_subjects s
  WHERE ((s.tenant_id = subject_contexts.tenant_id) AND (s.id = subject_contexts.subject_id) AND (s.account_id = (SELECT platform.current_actor_id())))))));
ALTER POLICY tenant_boundary ON cp.subject_contexts
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY onboarding_insert ON cp.units
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY onboarding_read ON cp.units
 USING ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY tenant_boundary ON cp.units
 USING ((tenant_id = (SELECT platform.current_tenant_id())))
 WITH CHECK ((tenant_id = (SELECT platform.current_tenant_id())));
ALTER POLICY units_manage_insert ON cp.units
 WITH CHECK (((SELECT platform.has_capability('units.manage'::text)) OR platform.tenant_created_here(tenant_id)));
ALTER POLICY units_manage_update ON cp.units
 USING (true)
 WITH CHECK ((SELECT platform.has_capability('units.manage'::text)));
ALTER POLICY units_platform_read ON cp.units
 USING ((SELECT platform.has_platform_capability('tenants.read'::text)));
