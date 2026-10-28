package br.com.condpulse.onboarding;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Manual DML only: no DDL, Auth mutation, HTTP calls, API bean or role elevation. */
public final class OnboardingStore {
    public record Result(boolean alreadyApplied, int accountsToCreate, int units, int memberships, int amenities) { }

    public Result execute(Connection connection, OnboardingManifest plan, String expectedActor, boolean apply,
            AuthAdminVerifier.Eligibility proof) throws SQLException {
        connection.setAutoCommit(false);
        connection.setReadOnly(!apply);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        try {
            requireOperator(connection, expectedActor);
            try (var lock = statement(connection, "select pg_try_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "condpulse.onboarding:" + plan.tenant().id());
                try (var rows = lock.executeQuery()) { require(rows.next() && rows.getBoolean(1), "ONBOARDING_BUSY"); }
            }
            context(connection, plan.tenant().id(), null);
            requireEligibleAuth(connection, plan, proof);
            boolean replay = receipt(connection, plan, expectedActor);
            int missingAccounts = accountStates(connection, plan);
            if (replay) {
                verifyExisting(connection, plan);
                requireEligibleAuth(connection, plan, proof);
                connection.commit();
                return result(plan, true, 0);
            }
            try (var statement = statement(connection, "select count(*) from platform.tenants where id=?")) {
                statement.setObject(1, plan.tenant().id());
                try (var rows = statement.executeQuery()) {
                    require(rows.next() && rows.getLong(1) == 0, "ONBOARDING_TENANT_ALREADY_EXISTS");
                }
            }
            if (apply) {
                insert(connection, plan);
                verifyExisting(connection, plan);
                try (var statement = statement(connection, """
                    insert into platform.onboarding_runs(operation_id,tenant_id,manifest_hash,database_actor,counts)
                    values(?,?,?,?,jsonb_build_object('accounts',?::integer,'units',?::integer,'memberships',?::integer,'amenities',?::integer))
                    """)) {
                    statement.setObject(1, plan.operationId()); statement.setObject(2, plan.tenant().id());
                    statement.setString(3, plan.manifestHash()); statement.setString(4, expectedActor);
                    statement.setInt(5, plan.accounts().size()); statement.setInt(6, plan.units().size());
                    statement.setInt(7, plan.memberships().size()); statement.setInt(8, plan.amenities().size());
                    require(statement.executeUpdate() == 1, "ONBOARDING_AUDIT_NOT_WRITTEN");
                }
            }
            requireEligibleAuth(connection, plan, proof);
            connection.commit();
            return result(plan, false, missingAccounts);
        } catch (SQLException | RuntimeException error) {
            try { connection.rollback(); } catch (SQLException ignored) { /* close() terminates the session. */ }
            throw error;
        }
    }

    private static Result result(OnboardingManifest plan, boolean replay, int newAccounts) {
        return new Result(replay, newAccounts, plan.units().size(), plan.memberships().size(), plan.amenities().size());
    }

    private static PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var statement = connection.prepareStatement(sql); statement.setQueryTimeout(10); return statement;
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }

    private static void requireOperator(Connection connection, String expectedActor) throws SQLException {
        try (var statement = statement(connection, """
            select current_user,session_user,rolcanlogin,
              rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication as unsafe,
              pg_has_role(current_user,'cp_onboarding','MEMBER') as allowed,
              pg_has_role(current_user,'cp_api','MEMBER') or pg_has_role(current_user,'cp_worker','MEMBER') as runtime,
              has_schema_privilege(current_user,'platform','CREATE') or has_schema_privilege(current_user,'cp','CREATE') as ddl,
              has_any_column_privilege(current_user,'platform.onboarding_runs','UPDATE')
                or has_table_privilege(current_user,'platform.onboarding_runs','DELETE')
                or has_any_column_privilege(current_user,'platform.accounts','UPDATE') as rewrite
            from pg_roles where rolname=current_user
            """); var rows = statement.executeQuery()) {
            require(rows.next() && expectedActor.equals(rows.getString("current_user"))
                && expectedActor.equals(rows.getString("session_user")) && rows.getBoolean("rolcanlogin")
                && !rows.getBoolean("unsafe") && rows.getBoolean("allowed") && !rows.getBoolean("runtime")
                && !rows.getBoolean("ddl") && !rows.getBoolean("rewrite"), "ONBOARDING_OPERATOR_NOT_AUTHORIZED");
        }
    }

    private static void context(Connection connection, UUID tenant, UUID actor) throws SQLException {
        try (var statement = statement(connection, """
            select set_config('app.tenant_id',?,true),set_config('app.actor_id',?,true),set_config('app.session_hash','',true)
            """)) {
            statement.setString(1, tenant.toString()); statement.setString(2, actor == null ? "" : actor.toString());
            statement.execute();
        }
    }

    private static void requireEligibleAuth(Connection connection, OnboardingManifest plan,
            AuthAdminVerifier.Eligibility proof) throws SQLException {
        require(proof != null && proof.accounts().equals(Set.copyOf(plan.accounts())), "ONBOARDING_AUTH_IDENTITY_INELIGIBLE");
        Instant now;
        try (var statement = statement(connection, "select clock_timestamp()"); var rows = statement.executeQuery()) {
            require(rows.next(), "ONBOARDING_DATABASE_TIME_UNAVAILABLE"); now = rows.getTimestamp(1).toInstant();
        }
        require(proof.checkedAt() != null && !proof.checkedAt().isAfter(now.plusSeconds(5))
            && proof.checkedAt().plusSeconds(120).isAfter(now), "ONBOARDING_AUTH_PROOF_EXPIRED");
        for (var membership : plan.memberships()) {
            require(membership.validUntil() == null || membership.validUntil().isAfter(now), "ONBOARDING_MEMBERSHIP_EXPIRED");
        }
    }

    private static boolean receipt(Connection connection, OnboardingManifest plan, String actor) throws SQLException {
        try (var statement = statement(connection, """
            select tenant_id,manifest_hash,database_actor from platform.onboarding_runs where operation_id=?
            """)) {
            statement.setObject(1, plan.operationId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                require(plan.tenant().id().equals(rows.getObject("tenant_id", UUID.class))
                    && plan.manifestHash().equals(rows.getString("manifest_hash"))
                    && actor.equals(rows.getString("database_actor")), "ONBOARDING_OPERATION_REUSED");
                return true;
            }
        }
    }

    private static int accountStates(Connection connection, OnboardingManifest plan) throws SQLException {
        int missing = 0;
        for (UUID account : plan.accounts()) {
            context(connection, plan.tenant().id(), account);
            try (var statement = statement(connection, "select status from platform.accounts where id=?")) {
                statement.setObject(1, account);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) missing++;
                    else require("ACTIVE".equals(rows.getString(1)), "ONBOARDING_ACCOUNT_NOT_ACTIVE");
                }
            }
        }
        return missing;
    }

    private static void insert(Connection connection, OnboardingManifest plan) throws SQLException {
        for (UUID account : plan.accounts()) {
            context(connection, plan.tenant().id(), account);
            try (var statement = statement(connection, "insert into platform.accounts(id,status) values(?,'ACTIVE') on conflict(id) do nothing")) {
                statement.setObject(1, account); statement.executeUpdate();
            }
        }
        context(connection, plan.tenant().id(), null);
        try (var statement = statement(connection, "insert into platform.tenants(id,name,time_zone,active) values(?,?,?,true)")) {
            statement.setObject(1, plan.tenant().id()); statement.setString(2, plan.tenant().name());
            statement.setString(3, plan.tenant().timeZone()); statement.executeUpdate();
        }
        for (var unit : plan.units()) {
            try (var statement = statement(connection, "insert into cp.units(id,tenant_id,label,status) values(?,?,?,'ACTIVE')")) {
                statement.setObject(1, unit.id()); statement.setObject(2, plan.tenant().id()); statement.setString(3, unit.label()); statement.executeUpdate();
            }
        }
        for (var member : plan.memberships()) {
            try (var statement = statement(connection, """
                insert into cp.memberships(id,tenant_id,account_id,unit_id,display_name,role,status,valid_from,valid_until)
                values(?,?,?,?,?,?,'ACTIVE',?,?)
                """)) {
                statement.setObject(1, member.id()); statement.setObject(2, plan.tenant().id());
                statement.setObject(3, member.accountId()); statement.setObject(4, member.unitId());
                statement.setString(5, member.displayName()); statement.setString(6, member.role());
                statement.setTimestamp(7, Timestamp.from(member.validFrom()));
                statement.setTimestamp(8, member.validUntil() == null ? null : Timestamp.from(member.validUntil())); statement.executeUpdate();
            }
        }
        for (var area : plan.amenities()) {
            try (var statement = statement(connection, """
                insert into cp.amenities(id,tenant_id,name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,policy,active)
                values(?,?,?,?,?,?,?,?,?::jsonb,true)
                """)) {
                statement.setObject(1, area.id()); statement.setObject(2, plan.tenant().id()); statement.setString(3, area.name());
                statement.setInt(4, area.capacity()); statement.setBoolean(5, area.approvalRequired()); statement.setLong(6, area.feeCents());
                statement.setInt(7, area.bufferMinutes()); statement.setInt(8, area.policyVersion()); statement.setString(9, area.policyRawJson());
                statement.executeUpdate();
            }
        }
    }

    private static void verifyExisting(Connection connection, OnboardingManifest plan) throws SQLException {
        try (var statement = statement(connection, "select name,time_zone,active from platform.tenants where id=?")) {
            statement.setObject(1, plan.tenant().id());
            try (var rows = statement.executeQuery()) {
                require(rows.next() && plan.tenant().name().equals(rows.getString("name"))
                    && plan.tenant().timeZone().equals(rows.getString("time_zone")) && rows.getBoolean("active"), "ONBOARDING_STATE_DIVERGED");
            }
        }
        count(connection, "units", plan.tenant().id(), plan.units().size());
        count(connection, "memberships", plan.tenant().id(), plan.memberships().size());
        count(connection, "amenities", plan.tenant().id(), plan.amenities().size());
        for (var unit : plan.units()) {
            try (var statement = statement(connection, "select label,status from cp.units where tenant_id=? and id=?")) {
                statement.setObject(1, plan.tenant().id()); statement.setObject(2, unit.id());
                try (var rows = statement.executeQuery()) {
                    require(rows.next() && unit.label().equals(rows.getString("label")) && "ACTIVE".equals(rows.getString("status")), "ONBOARDING_STATE_DIVERGED");
                }
            }
        }
        for (var member : plan.memberships()) {
            try (var statement = statement(connection, """
                select account_id,unit_id,display_name,role,status,valid_from,valid_until,permissions='{}'::jsonb as no_permissions
                from cp.memberships where tenant_id=? and id=?
                """)) {
                statement.setObject(1, plan.tenant().id()); statement.setObject(2, member.id());
                try (var rows = statement.executeQuery()) {
                    require(rows.next() && member.accountId().equals(rows.getObject("account_id", UUID.class))
                        && java.util.Objects.equals(member.unitId(), rows.getObject("unit_id", UUID.class))
                        && member.displayName().equals(rows.getString("display_name")) && member.role().equals(rows.getString("role"))
                        && "ACTIVE".equals(rows.getString("status")) && rows.getBoolean("no_permissions")
                        && member.validFrom().equals(rows.getTimestamp("valid_from").toInstant())
                        && java.util.Objects.equals(member.validUntil(), instant(rows, "valid_until")), "ONBOARDING_STATE_DIVERGED");
                }
            }
        }
        for (var area : plan.amenities()) {
            try (var statement = statement(connection, """
                select name,capacity,approval_required,fee_cents,buffer_minutes,policy_version,active,policy=?::jsonb as policy_matches
                from cp.amenities where tenant_id=? and id=?
                """)) {
                statement.setString(1, area.policyRawJson()); statement.setObject(2, plan.tenant().id()); statement.setObject(3, area.id());
                try (var rows = statement.executeQuery()) {
                    require(rows.next() && area.name().equals(rows.getString("name")) && area.capacity() == rows.getInt("capacity")
                        && area.approvalRequired() == rows.getBoolean("approval_required") && area.feeCents() == rows.getLong("fee_cents")
                        && area.bufferMinutes() == rows.getInt("buffer_minutes") && area.policyVersion() == rows.getInt("policy_version")
                        && rows.getBoolean("active") && rows.getBoolean("policy_matches"), "ONBOARDING_STATE_DIVERGED");
                }
            }
        }
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column); return value == null ? null : value.toInstant();
    }

    private static void count(Connection connection, String table, UUID tenant, int expected) throws SQLException {
        require(Set.of("units", "memberships", "amenities").contains(table), "ONBOARDING_TABLE_NOT_ALLOWED");
        try (var statement = statement(connection, "select count(*) from cp." + table + " where tenant_id=?")) {
            statement.setObject(1, tenant);
            try (var rows = statement.executeQuery()) { require(rows.next() && rows.getLong(1) == expected, "ONBOARDING_STATE_DIVERGED"); }
        }
    }
}
