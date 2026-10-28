package br.com.condpulse.bootstrap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.LogFactory;
import org.postgresql.util.PasswordUtil;

/** Manual privileged provisioner. Not an API component, migration or deployment launcher. */
public final class RuntimeConfiguration {
    public static final String PROJECT = "rpnrqfrfwywglvyykgrg";
    public static final String VERCEL_PROJECT = "prj_IeUa4Zjg1IwaPqJQwv525H14I837";
    public static final String TEAM = "team_6fzA029jxhDb1LpnrnphWGQ0";
    static final String HOST = "aws-1-sa-east-1.pooler.supabase.com";
    static final String OWNER_URL = "jdbc:postgresql://" + HOST + ":5432/postgres?sslmode=verify-full&sslrootcert=/run/secrets/db-ca.crt";
    static final String RUNTIME_URL = "jdbc:postgresql://" + HOST + ":5432/postgres?sslmode=verify-full&sslrootcert=/tmp/condpulse-db-ca.crt";
    static final String SECRET = "DATABASE_PASSWORD";
    static final Set<String> LEGACY_CONFIG = Set.of("PORT", "DB_POOL_SIZE", "CP_JWT_ISSUER", "CP_JWT_JWK_SET_URI", "CP_BFF_ORIGIN", "CP_BFF_ENABLED", "CP_RESERVATIONS_ENABLED");
    private static final Map<String, Set<String>> TABLE_GRANTS = Map.ofEntries(
        Map.entry("platform.accounts", Set.of("SELECT")), Map.entry("platform.tenants", Set.of("SELECT")),
        Map.entry("cp.memberships", Set.of("SELECT")), Map.entry("cp.blocks", Set.of("SELECT")),
        Map.entry("cp.units", Set.of("SELECT")), Map.entry("cp.amenities", Set.of("SELECT")),
        Map.entry("cp.reservations", Set.of("SELECT", "INSERT", "UPDATE")),
        Map.entry("cp.parcels", Set.of("SELECT", "INSERT", "UPDATE")),
        Map.entry("cp.parcel_handoffs", Set.of("SELECT", "INSERT")),
        Map.entry("cp.arrival_requests", Set.of("SELECT", "INSERT", "UPDATE")),
        Map.entry("cp.audit_log", Set.of("INSERT")), Map.entry("cp.outbox", Set.of("INSERT")),
        Map.entry("cp.idempotency_keys", Set.of("SELECT", "INSERT", "UPDATE")),
        Map.entry("platform.web_sessions", Set.of("SELECT", "INSERT", "DELETE")),
        Map.entry("platform.auth_rate_limits", Set.of("SELECT", "INSERT", "DELETE")),
        Map.entry("platform.onboarding_runs", Set.of()));
    private static final Map<String, Set<String>> COLUMN_UPDATES = Map.of(
        "platform.web_sessions", Set.of("encrypted_access_token", "encrypted_refresh_token", "access_expires_at", "refresh_lease_id", "refresh_lease_until", "version"),
        "platform.auth_rate_limits", Set.of("window_started_at", "attempts", "expires_at"));

    interface Gateway {
        Map<String, Entry> inspect() throws Exception;
        void put(List<Change> changes) throws Exception;
    }
    record Entry(String id, String key, String type, String visibility, List<String> target,
                 String gitBranch, String comment, String configValue, String revision) {
        Entry { target = List.copyOf(target); }
    }
    // Deliberately not a record: accidental logging cannot reveal the HTTP payload value.
    static final class Change {
        private final String key, value, comment; private final boolean secret;
        Change(String key, String value, boolean secret, String comment) { this.key=key; this.value=value; this.secret=secret; this.comment=comment; }
        String key() { return key; } String value() { return value; } boolean secret() { return secret; } String comment() { return comment; }
        @Override public String toString() { return "RuntimeChange[redacted]"; }
    }
    record Config(UUID generation, String sha, String mode, String ack, String caBase64) {
        Config {
            require(generation != null && !generation.equals(new UUID(0,0)) && sha != null && sha.matches("[a-f0-9]{40}"), "RUNTIME_REQUEST_INVALID");
            require("inspect".equals(mode) || "apply".equals(mode), "RUNTIME_OPERATION_INVALID");
            require(caBase64 != null && !caBase64.isBlank(), "RUNTIME_CA_INVALID");
            require("apply".equals(mode) || ack == null || ack.isEmpty(), "RUNTIME_ACK_NOT_APPLICABLE");
        }
        String role() { return "cp_runtime_" + generation.toString().replace("-", ""); }
        String marker() { return "condpulse-runtime:" + PROJECT + ":" + generation + ":" + sha; }
        String acknowledgement(String fingerprint) { return PROJECT + ":runtime:" + generation + ":" + sha + ":" + fingerprint; }
    }
    record Outcome(boolean eligible, boolean applied, boolean alreadyApplied, String state, String fingerprint, String code) { }
    private record Role(boolean exists, boolean login, String expiry, String comment, String state) { }
    @FunctionalInterface interface RuntimeConnector { Connection open(String role, String password) throws Exception; }
    static final class Denied extends IllegalStateException {
        final String code; Denied(String code) { super(code); this.code=code; }
    }
    static void require(boolean allowed, String code) { if (!allowed) throw new Denied(code); }
    private RuntimeConfiguration() { }

    public static void main(String[] args) {
        java.util.logging.LogManager.getLogManager().reset();
        LogFactory.setLogCreator(new BootstrapBaseline.QuietLogCreator());
        Outcome outcome=null; Connection owner=null;
        try {
            require(args.length == 0, "RUNTIME_ARGUMENTS_FORBIDDEN");
            Map<String,String> env = System.getenv();
            BootstrapBaseline.productionConfig(env);
            require(OWNER_URL.equals(env.get("DATABASE_URL")) && ("postgres."+PROJECT).equals(env.get("DATABASE_USER")), "RUNTIME_TARGET_DENIED");
            require(BootstrapManifest.sourceCodes(BootstrapManifest.load()).isEmpty(), "RUNTIME_SOURCE_INVALID");
            String generation = env.get("CP_RUNTIME_GENERATION"), sha=env.get("CP_RUNTIME_EXPECTED_COMMIT");
            require(generation != null && generation.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"), "RUNTIME_GENERATION_INVALID");
            require(sha != null && sha.equals(env.get("GITHUB_SHA")), "RUNTIME_COMMIT_MISMATCH");
            String token=env.get("CP_VERCEL_TOKEN");
            require(token != null && token.matches("[!-~]{16,4096}"), "RUNTIME_VERCEL_TOKEN_REQUIRED");
            Config config=new Config(UUID.fromString(generation),sha,env.getOrDefault("CP_RUNTIME_OPERATION","inspect"),env.get("CP_RUNTIME_ACK"),productionCa(env));
            Properties properties=properties(env.get("DATABASE_USER"),env.get("DATABASE_PASSWORD"));
            owner=DriverManager.getConnection(OWNER_URL,properties);
                requireTls(owner);
                Flyway flyway=Flyway.configure().dataSource(OWNER_URL,env.get("DATABASE_USER"),env.get("DATABASE_PASSWORD"))
                    .defaultSchema("platform").schemas("platform","cp").createSchemas(false).locations("classpath:db/migration")
                    .baselineOnMigrate(false).cleanDisabled(true).validateMigrationNaming(true).outOfOrder(false).target("005")
                    .loggers(BootstrapBaseline.QuietLogCreator.class.getName()).load();
                outcome=execute(config,owner,flyway,new RuntimeVercelClient(token),(role,password)-> {
                    Connection connection=DriverManager.getConnection(OWNER_URL,properties(role+"."+PROJECT,password));
                    try { requireTls(connection); return connection; }
                    catch(Exception error) { connection.close(); throw error; }
                });
        } catch (Denied denied) { outcome=new Outcome(false,false,false,"NOT_ATTEMPTED","",denied.code); }
        catch(Exception ignored) { outcome=new Outcome(false,false,false,"NOT_ATTEMPTED","","RUNTIME_PREFLIGHT_FAILED"); }
        finally { outcome=closeOwner(owner,outcome); }
        System.out.printf("{\"eligible\":%s,\"applied\":%s,\"alreadyApplied\":%s,\"state\":\"%s\",\"fingerprint\":\"%s\",\"code\":\"%s\"}%n",
            outcome.eligible(),outcome.applied(),outcome.alreadyApplied(),outcome.state(),outcome.fingerprint(),outcome.code());
        System.exit(outcome.code().isEmpty()?0:1);
    }

    /** A close failure cannot relabel an already produced write outcome as NOT_ATTEMPTED. */
    static Outcome closeOwner(Connection owner,Outcome outcome) {
        if(owner!=null)try {owner.close();}catch(Exception ignored) {
            if(outcome==null)return new Outcome(false,false,false,"UNKNOWN","","RUNTIME_RESULT_UNKNOWN");
        }
        return outcome==null?new Outcome(false,false,false,"UNKNOWN","","RUNTIME_RESULT_UNKNOWN"):outcome;
    }

    private static String productionCa(Map<String,String> env) throws Exception {
        require("/run/secrets/db-ca.crt".equals(env.get("CP_RUNTIME_CA_PATH")),"RUNTIME_CA_PATH_DENIED");
        Path path=Path.of("/run/secrets/db-ca.crt");
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.size(path)>0 && Files.size(path)<=65536,"RUNTIME_CA_INVALID");
        byte[] bytes=Files.readAllBytes(path);
        var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new java.io.ByteArrayInputStream(bytes));
        require(certificates.size()==1,"RUNTIME_CA_INVALID");
        X509Certificate certificate=(X509Certificate)certificates.iterator().next();
        certificate.checkValidity(java.util.Date.from(Instant.now().plusSeconds(86400)));
        require(certificate.getBasicConstraints()>=0,"RUNTIME_CA_INVALID"); certificate.verify(certificate.getPublicKey());
        return Base64.getEncoder().encodeToString(bytes);
    }
    private static Properties properties(String user,String password) {
        Properties p=new Properties(); p.setProperty("user",user); p.setProperty("password",password);
        p.setProperty("connectTimeout","5"); p.setProperty("socketTimeout","20"); p.setProperty("ApplicationName","condpulse-runtime-configuration");
        p.setProperty("requireAuth","scram-sha-256"); return p;
    }
    static void requireTls(Connection c) throws Exception {
        try(var q=query(c,"select ssl from pg_stat_ssl where pid=pg_backend_pid()");var r=q.executeQuery()) {
            require(r.next() && r.getBoolean(1),"RUNTIME_DATABASE_TLS_REQUIRED");
        }
    }

    static Outcome execute(Config config,Connection owner,Flyway flyway,Gateway api,RuntimeConnector runtime) {
        String fingerprint=""; boolean locked=false,attempted=false;
        try {
            require(owner.getAutoCommit(),"RUNTIME_TRANSACTION_STATE_INVALID"); owner.setReadOnly(true);
            try(var q=query(owner,"select current_user,session_user,pg_try_advisory_lock(?)")) {
                q.setLong(1,BootstrapBaseline.LOCK_KEY);
                try(var r=q.executeQuery()) { require(r.next(),"RUNTIME_ACTOR_INVALID"); locked=r.getBoolean(3);
                    require("postgres".equals(r.getString(1)) && "postgres".equals(r.getString(2)),"RUNTIME_ACTOR_INVALID"); require(locked,"RUNTIME_BUSY"); }
            }
            foundation(owner,flyway); verifyGrants(owner,"cp_api");
            Map<String,String> values=values(config); Map<String,Entry> entries=api.inspect();
            Role role=role(owner,config); namespace(owner,config); eligibleMetadata(config,role,entries,values);
            if(role.exists()) verifyRole(owner,config.role());
            fingerprint=fingerprint(config,role,entries,values.keySet());
            if("READY".equals(role.state())) {
                require(role.login() && "infinity".equals(role.expiry()),"RUNTIME_READY_ROLE_DIVERGED"); reconcile(config,entries,values);
                if("apply".equals(config.mode())) require(config.acknowledgement(fingerprint).equals(config.ack()),"RUNTIME_ACK_REQUIRED");
                return new Outcome(true,false,true,"READY",fingerprint,"");
            }
            if("inspect".equals(config.mode())) return new Outcome(true,false,false,role.state(),fingerprint,"");
            require(config.acknowledgement(fingerprint).equals(config.ack()),"RUNTIME_ACK_REQUIRED");
            // Re-read metadata immediately before writes. No HTTP request occurs inside a DB transaction.
            require(fingerprint.equals(fingerprint(config,role(owner,config),api.inspect(),values.keySet())),"RUNTIME_INSPECTION_CHANGED");
            byte[] entropy=new byte[36]; new SecureRandom().nextBytes(entropy);
            String password=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); Arrays.fill(entropy,(byte)0);
            String verifier=PasswordUtil.encodeScramSha256(password.toCharArray());
            attempted=true; stage(owner,config,role.exists(),verifier); verifyRole(owner,config.role());
            try(Connection login=runtime.open(config.role(),password)) { verifyLogin(login,config.role()); }
            List<Change> changes=new ArrayList<>();
            values.forEach((key,value)->changes.add(new Change(key,value,false,config.marker())));
            changes.add(new Change(SECRET,password,true,config.marker()));
            api.put(List.copyOf(changes));
            Map<String,Entry> after=api.inspect(); reconcile(config,after,values); verifyRole(owner,config.role());
            ready(owner,config);
            Role finalRole=role(owner,config);
            return new Outcome(true,true,false,"READY",fingerprint(config,finalRole,after,values.keySet()),"");
        } catch(Exception failure) {
            if(attempted) {
                boolean disabled=disable(owner,config);
                return new Outcome(false,false,false,disabled?"DISABLED":"UNKNOWN",fingerprint,disabled?"RUNTIME_PARTIAL_RECONCILE_REQUIRED":"RUNTIME_RESULT_UNKNOWN");
            }
            String code=failure instanceof Denied denied?denied.code:"RUNTIME_INSPECTION_FAILED";
            return new Outcome(false,false,false,"NOT_ATTEMPTED",fingerprint,code);
        } finally {
            try { if(!owner.getAutoCommit()) { owner.rollback(); owner.setAutoCommit(true); } }
            catch(Exception ignored) { /* Connection close terminates a failed transaction/session. */ }
            if(locked) try(var q=query(owner,"select pg_advisory_unlock(?)")) { q.setLong(1,BootstrapBaseline.LOCK_KEY);q.execute(); }
            catch(Exception ignored) { /* Caller closes the session; staged password expiry remains bounded. */ }
        }
    }

    static Map<String,String> values(Config c) {
        return new TreeMap<>(Map.ofEntries(Map.entry("DATABASE_USER",c.role()+"."+PROJECT),Map.entry("DATABASE_URL",RUNTIME_URL),
            Map.entry("CP_VERCEL_DB_CA_BASE64",c.caBase64()),Map.entry("PORT","8080"),Map.entry("DB_POOL_SIZE","2"),
            Map.entry("CP_JWT_ISSUER","https://"+PROJECT+".supabase.co/auth/v1"),
            Map.entry("CP_JWT_JWK_SET_URI","https://"+PROJECT+".supabase.co/auth/v1/.well-known/jwks.json"),
            Map.entry("CP_BFF_ORIGIN","https://project-rhvdq.vercel.app"),Map.entry("CP_BFF_ENABLED","false"),Map.entry("CP_RESERVATIONS_ENABLED","false")));
    }
    private static void eligibleMetadata(Config c,Role role,Map<String,Entry> entries,Map<String,String> values) {
        for(String key:values.keySet()) {
            Entry e=entries.get(key); if(e==null)continue;
            metadata(e,false);
            require(c.marker().equals(e.comment()) || (!"READY".equals(role.state()) && LEGACY_CONFIG.contains(key) && (e.comment()==null || e.comment().isEmpty())),"RUNTIME_ENV_OWNERSHIP_CONFLICT");
            if(e.configValue()!=null && LEGACY_CONFIG.contains(key)) require(values.get(key).equals(e.configValue()),"RUNTIME_CONFIG_DIVERGED");
        }
        Entry password=entries.get(SECRET);
        if(password!=null) { metadata(password,true);require(role.exists() && c.marker().equals(password.comment()),"RUNTIME_SECRET_OWNERSHIP_CONFLICT"); }
        if(!role.exists()) require(!entries.containsKey("DATABASE_USER") && password==null,"RUNTIME_EXISTING_RUNTIME_CONFLICT");
    }
    private static void metadata(Entry e,boolean secret) {
        require(e.id()!=null && !e.id().isBlank() && e.target().equals(List.of("production")) && (e.gitBranch()==null || e.gitBranch().isEmpty()),"RUNTIME_ENV_TARGET_INVALID");
        require(secret ? "sensitive".equals(e.type()) && "secret".equals(e.visibility()) && e.configValue()==null
            : !"sensitive".equals(e.type()) && !"secret".equals(e.visibility()),"RUNTIME_ENV_TYPE_INVALID");
    }
    private static void reconcile(Config c,Map<String,Entry> entries,Map<String,String> values) {
        for(String key:values.keySet()) {
            Entry e=entries.get(key);require(e!=null,"RUNTIME_ENV_INCOMPLETE");metadata(e,false);
            require(c.marker().equals(e.comment()),"RUNTIME_ENV_OWNERSHIP_CONFLICT");
            require(values.get(key).equals(e.configValue()),"RUNTIME_CONFIG_DIVERGED");
        }
        Entry e=entries.get(SECRET);require(e!=null,"RUNTIME_ENV_INCOMPLETE");metadata(e,true);require(c.marker().equals(e.comment()),"RUNTIME_SECRET_OWNERSHIP_CONFLICT");
    }
    private static String fingerprint(Config c,Role r,Map<String,Entry> entries,Set<String> keys) throws Exception {
        StringBuilder body=new StringBuilder("runtime-v1|").append(PROJECT).append('|').append(VERCEL_PROJECT).append('|').append(TEAM)
            .append('|').append(c.generation()).append('|').append(c.sha()).append('|').append(r.exists()).append('|').append(r.login()).append('|').append(r.expiry()).append('|').append(r.comment());
        var selected=new java.util.TreeSet<>(keys);selected.add(SECRET);
        for(String key:selected) { Entry e=entries.get(key);body.append('\n').append(key).append('|');
            if(e!=null)body.append(e.id()).append('|').append(e.type()).append('|').append(e.visibility()).append('|').append(e.target()).append('|').append(e.gitBranch()).append('|').append(e.comment()).append('|').append(e.revision()).append('|').append(e.configValue()); }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void foundation(Connection c,Flyway flyway) throws Exception {
        var versions=new ArrayList<String>();
        try(var q=query(c,"select version,type,success,checksum from platform.flyway_schema_history order by installed_rank");var r=q.executeQuery()) {
            while(r.next()) {
                String version=new java.math.BigInteger(r.getString(1)).toString(); int index=versions.size();
                require(r.getBoolean(3) && (index==0 ? "2".equals(version)&&"BASELINE".equals(r.getString(2))
                    : index<=3 && Integer.toString(index+2).equals(version)&&"SQL".equals(r.getString(2))&&r.getObject(4)!=null),"RUNTIME_HISTORY_INCOMPATIBLE");versions.add(version);
            }
        }
        require(versions.equals(List.of("2","3","4","5")),"RUNTIME_HISTORY_INCOMPATIBLE");flyway.validate();require(flyway.info().pending().length==0,"RUNTIME_MIGRATIONS_PENDING");
        try(var q=query(c,"select n.nspname||'.'||c.relname,pg_get_userbyid(c.relowner),c.relrowsecurity,c.relforcerowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname in ('platform','cp') and c.relkind in ('r','p') and c.relname<>'flyway_schema_history'");var r=q.executeQuery()) {
            var found=new java.util.HashSet<String>();while(r.next()) {require(TABLE_GRANTS.containsKey(r.getString(1)) && "postgres".equals(r.getString(2)) && r.getBoolean(3)&&r.getBoolean(4),"RUNTIME_SCHEMA_RLS_DIVERGED");found.add(r.getString(1));}
            require(found.equals(TABLE_GRANTS.keySet()),"RUNTIME_SCHEMA_RLS_DIVERGED");
        }
        try(var q=query(c,"select count(*) from pg_namespace where nspname in ('platform','cp') and pg_get_userbyid(nspowner)<>'postgres'");var r=q.executeQuery()) {require(r.next()&&r.getLong(1)==0,"RUNTIME_SCHEMA_OWNER_DIVERGED");}
        try(var q=query(c,"select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('platform','cp') and (pg_get_userbyid(p.proowner)<>'postgres' or p.prosecdef)");var r=q.executeQuery()) {require(r.next()&&r.getLong(1)==0,"RUNTIME_FUNCTION_OWNER_DIVERGED");}
        try(var q=query(c,"select rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolcanlogin,(select count(*) from pg_auth_members m where m.member=pg_roles.oid) from pg_roles where rolname='cp_api'");var r=q.executeQuery()) {require(r.next()&&!r.getBoolean(1)&&r.getLong(2)==0,"RUNTIME_API_ROLE_DIVERGED");}
        noDdlOrOwnership(c,"cp_api");
    }
    private static Role role(Connection c,Config config) throws Exception {
        try(var q=query(c,"select rolcanlogin,rolvaliduntil::text,shobj_description(oid,'pg_authid') from pg_roles where rolname=?")) {
            q.setString(1,config.role());try(var r=q.executeQuery()) {
                if(!r.next())return new Role(false,false,"","","ABSENT");
                String comment=r.getString(3);require(comment!=null && comment.startsWith(config.marker()+":"),"RUNTIME_ROLE_OWNERSHIP_CONFLICT");
                String state=comment.substring(config.marker().length()+1);require(Set.of("STAGED","DISABLED","READY").contains(state),"RUNTIME_ROLE_STATE_INVALID");
                return new Role(true,r.getBoolean(1),r.getString(2),comment,state);
            }
        }
    }
    private static void namespace(Connection c,Config config) throws Exception {
        try(var q=query(c,"select rolname from pg_roles where rolname like 'cp\\_runtime\\_%' escape '\\' and rolname<>?")) {
            q.setString(1,config.role());try(var r=q.executeQuery()) {require(!r.next(),"RUNTIME_OTHER_GENERATION_EXISTS");}
        }
    }
    static void verifyRole(Connection c,String role) throws Exception {
        try(var q=query(c,"select oid,rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication from pg_roles where rolname=?")) {
            q.setString(1,role);try(var r=q.executeQuery()) {require(r.next()&&!r.getBoolean(2),"RUNTIME_ROLE_UNSAFE");}
        }
        try(var q=query(c,"select g.rolname,m.admin_option,m.inherit_option,m.set_option from pg_auth_members m join pg_roles u on u.oid=m.member join pg_roles g on g.oid=m.roleid where u.rolname=?")) {
            q.setString(1,role);try(var r=q.executeQuery()) {require(r.next()&&"cp_api".equals(r.getString(1))&&!r.getBoolean(2)&&r.getBoolean(3)&&!r.getBoolean(4)&&!r.next(),"RUNTIME_MEMBERSHIP_UNSAFE");}
        }
        noDdlOrOwnership(c,role);
        verifyGrants(c,role);
    }
    private static void noDdlOrOwnership(Connection c,String role) throws Exception {
        try(var q=query(c,"select (select count(*) from pg_shdepend where refclassid='pg_authid'::regclass and refobjid=(select oid from pg_roles where rolname=?) and deptype='o')+(select count(*) from pg_namespace where nspname !~ '^pg_' and nspname<>'information_schema' and has_schema_privilege(?,oid,'CREATE'))")) {
            q.setString(1,role);q.setString(2,role);try(var r=q.executeQuery()) {require(r.next()&&r.getLong(1)==0,"RUNTIME_DDL_OR_OWNERSHIP_UNSAFE");}
        }
    }
    private static void verifyGrants(Connection c,String role) throws Exception {
        for(var table:TABLE_GRANTS.entrySet()) {
            try(var q=query(c,"select p.privilege,has_table_privilege(?,?::text,p.privilege),has_table_privilege(?,?::text,p.privilege||' WITH GRANT OPTION') from unnest(array['DELETE','TRUNCATE','REFERENCES','TRIGGER','MAINTAIN']) as p(privilege)")) {
                q.setString(1,role);q.setString(2,table.getKey());q.setString(3,role);q.setString(4,table.getKey());
                try(var r=q.executeQuery()) {int checked=0;while(r.next()) {checked++;require(r.getBoolean(2)==table.getValue().contains(r.getString(1))&&!r.getBoolean(3),"RUNTIME_GRANTS_DIVERGED");}require(checked==5,"RUNTIME_GRANTS_DIVERGED");}
            }
            try(var q=query(c,"select a.attname,p.privilege,has_column_privilege(?,a.attrelid,a.attnum,p.privilege),has_column_privilege(?,a.attrelid,a.attnum,p.privilege||' WITH GRANT OPTION') from pg_attribute a cross join unnest(array['SELECT','INSERT','UPDATE','REFERENCES']) as p(privilege) where a.attrelid=?::regclass and a.attnum>0 and not a.attisdropped")) {
                q.setString(1,role);q.setString(2,role);q.setString(3,table.getKey());
                try(var r=q.executeQuery()) {int checked=0;while(r.next()) {
                    checked++;String column=r.getString(1),privilege=r.getString(2);
                    boolean expected=table.getValue().contains(privilege) || ("UPDATE".equals(privilege)&&COLUMN_UPDATES.getOrDefault(table.getKey(),Set.of()).contains(column));
                    require(r.getBoolean(3)==expected&&!r.getBoolean(4),"RUNTIME_GRANTS_DIVERGED");
                }require(checked>0,"RUNTIME_GRANTS_DIVERGED");}
            }
        }
    }
    static void verifyLogin(Connection c,String role) throws Exception {
        try(var q=query(c,"select current_user,session_user,current_setting('row_security')");var r=q.executeQuery()) {
            require(r.next()&&role.equals(r.getString(1))&&role.equals(r.getString(2))&&"on".equals(r.getString(3)),"RUNTIME_LOGIN_PROOF_FAILED");
        }
        verifyRole(c,role);
        try(var q=query(c,"select count(*) from cp.reservations");var r=q.executeQuery()) {require(r.next()&&r.getLong(1)==0,"RUNTIME_RLS_PROOF_FAILED");}
    }
    private static void stage(Connection c,Config config,boolean exists,String verifier) throws Exception {
        c.setReadOnly(false);c.setAutoCommit(false);
        try {
            if(!exists)sql(c,"CREATE ROLE "+config.role()+" NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4");
            sql(c,"GRANT cp_api TO "+config.role()+" WITH ADMIN FALSE");sql(c,"GRANT cp_api TO "+config.role()+" WITH INHERIT TRUE");sql(c,"GRANT cp_api TO "+config.role()+" WITH SET FALSE");
            Instant expiry;
            try(var q=query(c,"select clock_timestamp()+interval '10 minutes'");var r=q.executeQuery()) {require(r.next(),"RUNTIME_DATABASE_TIME_INVALID");expiry=r.getTimestamp(1).toInstant();}
            sql(c,"ALTER ROLE "+config.role()+" LOGIN PASSWORD "+literal(verifier)+" VALID UNTIL "+literal(expiry.toString()));
            sql(c,"COMMENT ON ROLE "+config.role()+" IS "+literal(config.marker()+":STAGED"));c.commit();
        }catch(Exception error) {c.rollback();throw error;}finally {c.setAutoCommit(true);}
    }
    private static void ready(Connection c,Config config) throws Exception {
        c.setReadOnly(false);c.setAutoCommit(false);
        try {sql(c,"ALTER ROLE "+config.role()+" VALID UNTIL 'infinity'");sql(c,"COMMENT ON ROLE "+config.role()+" IS "+literal(config.marker()+":READY"));c.commit();}
        catch(Exception error) {c.rollback();throw error;}finally {c.setAutoCommit(true);}
    }
    private static boolean disable(Connection c,Config config) {
        try {
            if(!c.getAutoCommit()) {c.rollback();c.setAutoCommit(true);}c.setReadOnly(false);
            Role current=role(c,config);if(!current.exists())return true;
            // Only this generation, marked by this reviewed source, may be disabled.
            c.setAutoCommit(false);sql(c,"ALTER ROLE "+config.role()+" NOLOGIN PASSWORD NULL VALID UNTIL '1970-01-01T00:00:00Z'");
            sql(c,"COMMENT ON ROLE "+config.role()+" IS "+literal(config.marker()+":DISABLED"));c.commit();c.setAutoCommit(true);return true;
        }catch(Exception ignored) {try{c.rollback();c.setAutoCommit(true);}catch(Exception closed){}return false;}
    }
    private static String literal(String value) {return "'"+value.replace("'","''")+"'";}
    private static PreparedStatement query(Connection c,String sql) throws Exception {var q=c.prepareStatement(sql);q.setQueryTimeout(10);return q;}
    private static void sql(Connection c,String sql) throws Exception {try(var q=c.createStatement()){q.setQueryTimeout(10);q.execute(sql);}}
}
