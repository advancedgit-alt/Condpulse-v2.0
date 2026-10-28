-- Apenas catalogos e historico de DDL. Nao consulta dados das tabelas de negocio.
SELECT json_build_object(
  'catalog', json_build_object(
    'acl', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, c.relacl::text AS acl
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname IN ('cp','platform') AND c.relkind='r'
    ) x),
    'columns', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT table_schema AS schema, table_name AS table, column_name AS name,
             data_type AS type, is_nullable AS nullable, column_default AS default
      FROM information_schema.columns WHERE table_schema IN ('cp','platform')
    ) x),
    'history', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT name, version, statements IS NOT NULL AS has_statements,
        encode(sha256(convert_to(to_jsonb(statements)::text,'UTF8')),'hex') AS statements_digest_sha256
      FROM supabase_migrations.schema_migrations
    ) x),
    'indexes', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT schemaname, tablename, indexname, tablespace, indexdef
      FROM pg_indexes WHERE schemaname IN ('cp','platform')
    ) x),
    'policies', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual, with_check
      FROM pg_policies WHERE schemaname IN ('cp','platform')
    ) x),
    'constraints', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, k.conname AS name,
             k.contype::text AS type, pg_get_constraintdef(k.oid) AS definition
      FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('cp','platform')
    ) x)
  ),
  'extras', json_build_object(
    'server', json_build_object('version', current_setting('server_version_num'),
      'actor', current_user, 'replication', current_setting('session_replication_role')),
    'tables', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, pg_get_userbyid(c.relowner) AS owner,
             c.relrowsecurity AS rls, c.relforcerowsecurity AS force_rls
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname IN ('cp','platform') AND c.relkind='r'
    ) x),
    'schemas', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT nspname AS schema, pg_get_userbyid(nspowner) AS owner
      FROM pg_namespace WHERE nspname IN ('cp','platform')
    ) x),
    'schemaGrants', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, coalesce(r.rolname,'PUBLIC') AS grantee,
             pg_get_userbyid(a.grantor) AS grantor, a.privilege_type AS privilege, a.is_grantable AS grantable
      FROM pg_namespace n CROSS JOIN LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a
      LEFT JOIN pg_roles r ON r.oid=a.grantee WHERE n.nspname IN ('cp','platform')
    ) x),
    'functions', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, p.proname AS name, pg_get_userbyid(p.proowner) AS owner,
             p.prokind::text AS kind, pg_get_function_identity_arguments(p.oid) AS arguments,
             format_type(p.prorettype,NULL) AS returns, l.lanname AS language,
             p.provolatile::text AS volatility, p.prosecdef AS definer, p.proretset AS set_returning,
             p.proisstrict AS strict, p.proleakproof AS leakproof, p.proconfig AS config, btrim(p.prosrc) AS body
      FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace JOIN pg_language l ON l.oid=p.prolang
      WHERE n.nspname IN ('cp','platform')
    ) x),
    'functionGrants', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, p.proname AS name, coalesce(r.rolname,'PUBLIC') AS grantee,
             pg_get_userbyid(a.grantor) AS grantor, a.privilege_type AS privilege, a.is_grantable AS grantable
      FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      CROSS JOIN LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
      LEFT JOIN pg_roles r ON r.oid=a.grantee WHERE n.nspname IN ('cp','platform')
    ) x),
    'columnGrants', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, t.attname AS column,
             coalesce(r.rolname,'PUBLIC') AS grantee, pg_get_userbyid(a.grantor) AS grantor,
             a.privilege_type AS privilege, a.is_grantable AS grantable
      FROM pg_attribute t JOIN pg_class c ON c.oid=t.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
      CROSS JOIN LATERAL aclexplode(t.attacl) a LEFT JOIN pg_roles r ON r.oid=a.grantee
      WHERE n.nspname IN ('cp','platform') AND t.attnum>0 AND NOT t.attisdropped
    ) x),
    'roles', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT rolname AS name, rolcanlogin AS login, rolsuper AS superuser, rolbypassrls AS bypass_rls,
             rolcreatedb AS createdb, rolcreaterole AS createrole, rolreplication AS replication,
             rolinherit AS inherit, rolconfig AS config
      FROM pg_roles WHERE rolname IN ('cp_api','cp_worker')
    ) x),
    'roleMemberships', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT r.rolname AS member, p.rolname AS role
      FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member JOIN pg_roles p ON p.oid=m.roleid
      WHERE r.rolname IN ('cp_api','cp_worker')
    ) x),
    'flywayHistory', (SELECT count(*) FROM pg_class WHERE relname='flyway_schema_history'),
    'unexpectedRelations', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS name, c.relkind::text AS kind
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname IN ('cp','platform') AND c.relkind NOT IN ('r','i')
    ) x),
    'unexpectedTypes', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, t.typname AS name, t.typtype::text AS kind
      FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace LEFT JOIN pg_class c ON c.oid=t.typrelid
      WHERE n.nspname IN ('cp','platform') AND NOT (t.typtype='c' AND c.relkind='r')
        AND NOT (t.typtype='b' AND t.typelem<>0)
    ) x),
    'triggerViolations', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, t.tgname AS name
      FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname IN ('cp','platform') AND (NOT t.tgisinternal OR t.tgenabled<>'O')
    ) x),
    'columnFlags', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, a.attname AS column
      FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
      JOIN pg_type t ON t.oid=a.atttypid WHERE n.nspname IN ('cp','platform') AND a.attnum>0
        AND NOT a.attisdropped AND (a.attidentity<>'' OR a.attgenerated<>'' OR a.attcollation<>t.typcollation)
    ) x),
    'invalidConstraints', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS table, k.conname AS name
      FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname IN ('cp','platform') AND NOT k.convalidated
    ) x),
    'invalidIndexes', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT n.nspname AS schema, c.relname AS name FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('cp','platform')
        AND (NOT i.indisvalid OR NOT i.indisready OR NOT i.indislive)
    ) x),
    'inheritance', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT c.relname AS child, p.relname AS parent FROM pg_inherits i
      JOIN pg_class c ON c.oid=i.inhrelid JOIN pg_class p ON p.oid=i.inhparent
      JOIN pg_namespace cn ON cn.oid=c.relnamespace JOIN pg_namespace pn ON pn.oid=p.relnamespace
      WHERE cn.nspname IN ('cp','platform') OR pn.nspname IN ('cp','platform')
    ) x),
    'defaultPrivileges', (SELECT coalesce(json_agg(row_to_json(x)), '[]'::json) FROM (
      SELECT pg_get_userbyid(d.defaclrole) AS owner, n.nspname AS schema, d.defaclobjtype::text AS kind
      FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid=d.defaclnamespace
      WHERE n.nspname IN ('cp','platform') OR (d.defaclnamespace=0 AND pg_get_userbyid(d.defaclrole)='postgres')
    ) x)
  )
)::text
