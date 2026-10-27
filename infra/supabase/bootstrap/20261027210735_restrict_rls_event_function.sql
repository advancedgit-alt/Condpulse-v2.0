-- Aplicado em rpnrqfrfwywglvyykgrg pelo provisionamento nativo do Supabase.
-- Hardening do provedor, fora dos schemas de negocio cp/platform controlados por Flyway.
-- Mantem a funcao e o event trigger; retira somente permissao de RPC de usuarios finais.
DO $$
BEGIN
  IF to_regprocedure('public.rls_auto_enable()') IS NOT NULL THEN
    EXECUTE 'REVOKE EXECUTE ON FUNCTION public.rls_auto_enable() FROM PUBLIC, anon, authenticated';
  END IF;
END
$$;
