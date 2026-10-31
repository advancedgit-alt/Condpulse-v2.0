package br.com.condpulse.onboarding;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import br.com.condpulse.identity.application.CurrentIdentityQuery;
import br.com.condpulse.reservations.application.ReservationModels;
import br.com.condpulse.reservations.application.ReservationService;
import br.com.condpulse.reservations.infrastructure.ReservationStore;
import br.com.condpulse.tenancy.application.TenantTransactionExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real least-privilege DML/RLS and resulting reservation journey; no remote resources. */
@EnabledIfEnvironmentVariable(named="CP_JDBC_TEST_OK", matches="DISPOSABLE")
class OperationalOnboardingJdbcTest {
    private static String url;
    private static final String OPERATOR = "cp_onboarding_ci", OPERATOR_PASSWORD = "synthetic_onboarding_ci_only";
    private static OnboardingManifest plan;
    private static final UUID FOREIGN_TENANT = UUID.fromString("88000000-0000-4000-8000-000000000001");
    private final OnboardingStore store = new OnboardingStore();

    @BeforeAll static void initialize() throws Exception {
        String host = System.getenv("PGHOST"), database = System.getenv("PGDATABASE"), port = System.getenv("PGPORT");
        if (!"DISPOSABLE".equals(System.getenv("CP_DB_TEST_OK")) || !Set.of("localhost", "127.0.0.1").contains(host)
                || !"condpulse_ci".equals(database) || port == null || !port.matches("[0-9]{1,5}")
                || !"postgres".equals(System.getenv("PGUSER")) || !"condpulse_ci_only".equals(System.getenv("PGPASSWORD")))
            throw new IllegalStateException("DISPOSABLE_ONBOARDING_DATABASE_REQUIRED");
        url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        plan = OnboardingManifest.parse(OnboardingManifestTest.fixture().getBytes(StandardCharsets.UTF_8));
        admin("""
            do $$ begin
             if not exists(select from pg_roles where rolname='cp_onboarding_ci') then
              create role cp_onboarding_ci login password 'synthetic_onboarding_ci_only' in role cp_onboarding;
             end if;
            end $$
            """);
    }
    private static Connection adminConnection() throws SQLException { return DriverManager.getConnection(url, "postgres", "condpulse_ci_only"); }
    private static Connection operator() throws SQLException { return DriverManager.getConnection(url, OPERATOR, OPERATOR_PASSWORD); }
    private static void admin(String sql, Object... args) throws SQLException {
        try (var connection = adminConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            statement.execute();
        }
    }
    private static long count(String sql, Object... args) throws SQLException {
        try (var connection = adminConnection(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private AuthAdminVerifier.Eligibility proof() { return new AuthAdminVerifier.Eligibility(Set.copyOf(plan.accounts()), Instant.now()); }
    private OnboardingStore.Result run(boolean apply) throws SQLException {
        try (var connection = operator()) { return store.execute(connection, plan, OPERATOR, apply, proof()); }
    }
    @BeforeEach @AfterEach void cleanup() throws Exception {
        if (plan == null) return;
        for (String table : new String[] {"idempotency_keys", "outbox", "audit_log", "reservations", "memberships", "amenities", "units"})
            admin("delete from cp." + table + " where tenant_id in (?,?)", plan.tenant().id(), FOREIGN_TENANT);
        admin("delete from platform.onboarding_runs where tenant_id=?", plan.tenant().id());
        // Log de Segurança (V049) só acrescenta: na fixture descartável sai com os gatilhos desligados, antes do condomínio.
        for (UUID tenant : new UUID[] {plan.tenant().id(), FOREIGN_TENANT})
            admin("set session_replication_role=replica; delete from cp.security_log where tenant_id='" + tenant
                + "'; delete from cp.security_log_heads where tenant_id='" + tenant + "'; delete from platform.security_log_anchors where tenant_id='"
                + tenant + "'");
        admin("delete from platform.tenants where id in (?,?)", plan.tenant().id(), FOREIGN_TENANT);
        for (UUID account : plan.accounts()) {
            admin("delete from platform.web_sessions where account_id=?", account);
            admin("delete from platform.accounts where id=?", account);
        }
    }

    @Test void inspectionIsReadOnlyAndApplyReplayHasOneAuditThenRuntimeCanBookAndCancel() throws Exception {
        var inspected = run(false); assertFalse(inspected.alreadyApplied()); assertEquals(2, inspected.accountsToCreate());
        assertEquals(0, count("select count(*) from platform.tenants where id=?", plan.tenant().id()));
        assertEquals(0, count("select count(*) from platform.accounts where id=?", plan.accounts().getFirst()));
        assertFalse(run(true).alreadyApplied()); assertTrue(run(true).alreadyApplied());
        assertEquals(1, count("select count(*) from platform.onboarding_runs where tenant_id=? and database_actor='cp_onboarding_ci'", plan.tenant().id()));
        assertEquals(2, count("select count(*) from cp.memberships where tenant_id=?", plan.tenant().id()));
        var dataSource = new DriverManagerDataSource(url, "cp_ci_login", "synthetic_ci_runtime_only");
        var jdbc = new JdbcTemplate(dataSource); var manager = new JdbcTransactionManager(dataSource);
        var identity = new TransactionTemplate(manager).execute(status -> new CurrentIdentityQuery(jdbc).execute(plan.accounts().getFirst()));
        assertNotNull(identity); assertEquals(1, identity.memberships().size());
        var service = new ReservationService(new TenantTransactionExecutor(jdbc, manager), new ReservationStore(jdbc));
        var start = LocalDate.now(ZoneId.of(plan.tenant().timeZone())).plusDays(2).atTime(12, 0).atZone(ZoneId.of(plan.tenant().timeZone())).toInstant();
        var booking = new ReservationModels.Create(plan.units().getFirst().id(), plan.amenities().getFirst().id(), start, start.plusSeconds(3600), 1);
        var created = service.create(plan.accounts().getFirst(), plan.tenant().id(), "onboarding-booking:" + UUID.randomUUID(), booking).reservation();
        assertEquals("CONFIRMED", created.status());
        assertEquals("CANCELLED", service.cancel(plan.accounts().getFirst(), plan.tenant().id(), created.reservationId(),
            "onboarding-cancel:" + UUID.randomUUID(), new ReservationModels.Cancel(created.unitId(), created.version())).reservation().status());
        assertEquals(2, count("select count(*) from cp.audit_log where tenant_id=?", plan.tenant().id()));
    }

    @Test void staleOrIncompleteIdentityProofCannotWriteAnyPartOfTheBundle() throws Exception {
        for (var invalid : new AuthAdminVerifier.Eligibility[] {
                new AuthAdminVerifier.Eligibility(Set.of(plan.accounts().getFirst()), Instant.now()),
                new AuthAdminVerifier.Eligibility(Set.copyOf(plan.accounts()), Instant.now().minusSeconds(121))}) {
            try (var connection = operator()) {
                assertThrows(IllegalStateException.class, () -> store.execute(connection, plan, OPERATOR, true, invalid));
            }
        }
        assertEquals(0, count("select count(*) from platform.tenants where id=?", plan.tenant().id()));
    }

    @Test void suspendedAccountAndRevokedMembershipAreNeverReactivatedByReplay() throws Exception {
        admin("insert into platform.accounts(id,status) values(?,'SUSPENDED')", plan.accounts().getFirst());
        assertEquals("ONBOARDING_ACCOUNT_NOT_ACTIVE", assertThrows(IllegalStateException.class, () -> run(true)).getMessage());
        admin("delete from platform.accounts where id=?", plan.accounts().getFirst()); run(true);
        admin("update cp.memberships set status='REVOKED' where id=?", plan.memberships().getFirst().id());
        assertEquals("ONBOARDING_STATE_DIVERGED", assertThrows(IllegalStateException.class, () -> run(true)).getMessage());
        assertEquals(1, count("select count(*) from cp.memberships where id=? and status='REVOKED'", plan.memberships().getFirst().id()));
        assertEquals(1, count("select count(*) from platform.onboarding_runs where tenant_id=?", plan.tenant().id()));
    }

    @Test void lateCrossTenantUuidConflictRollsBackAccountsTenantAndAuditTogether() throws Exception {
        admin("insert into platform.tenants(id,name,active) values(?,'OUTRO CONDOMINIO SINTETICO',true)", FOREIGN_TENANT);
        admin("insert into cp.units(id,tenant_id,label) values(?,?,'UNIDADE SINTETICA EXTERNA')", plan.units().getFirst().id(), FOREIGN_TENANT);
        assertThrows(SQLException.class, () -> run(true));
        assertEquals(0, count("select count(*) from platform.tenants where id=?", plan.tenant().id()));
        assertEquals(0, count("select count(*) from platform.accounts where id=?", plan.accounts().getFirst()));
        assertEquals(0, count("select count(*) from platform.onboarding_runs where tenant_id=?", plan.tenant().id()));
    }

    @Test void runtimeIdentityCannotExecuteOnboardingAndOperatorCannotRewriteItsAudit() throws Exception {
        try (var connection = DriverManager.getConnection(url, "cp_ci_login", "synthetic_ci_runtime_only")) {
            assertEquals("ONBOARDING_OPERATOR_NOT_AUTHORIZED", assertThrows(IllegalStateException.class,
                () -> store.execute(connection, plan, "cp_ci_login", true, proof())).getMessage());
        }
        run(true);
        try (var connection = operator(); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("delete from platform.onboarding_runs"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("update platform.accounts set status='ACTIVE'"));
        }
    }
}
