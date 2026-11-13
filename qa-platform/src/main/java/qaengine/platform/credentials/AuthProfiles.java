package qaengine.platform.credentials;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import qaengine.core.auth.LocatorSpec;
import qaengine.core.auth.LoginForm;
import qaengine.core.auth.OtpStep;
import qaengine.core.policy.BaseUrl;
import qaengine.core.policy.PolicyException;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.SecretStore;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;
import qaengine.platform.shared.Principal;
import tools.jackson.databind.ObjectMapper;

/**
 * How a run signs in to an environment. Each profile uses one credential, and that credential belongs
 * to the profile's environment. Callers check project access first; this checks everything else.
 */
@Component
public class AuthProfiles {

    public static final Set<String> METHODS = Set.of("PASSWORD", "PASSWORD_EMAIL_OTP", "TOTP", "SESSION_IMPORT", "API_TOKEN");
    /** The two methods offered when an application is set up. The others stay available on existing profiles. */
    public static final Set<String> ONBOARDING_METHODS = Set.of("PASSWORD", "PASSWORD_EMAIL_OTP");
    private static final String RELATIVE = "/[A-Za-z0-9._~/-]*";
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}$");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    static final String DEFAULT_CODE_PATTERN = "\\b(\\d{6})\\b";

    /**
     * Mailbox settings. On an update a blank password keeps the sealed one. Confirming the mailbox is
     * dedicated to testing is required when it is first set and whenever its address or server changes.
     */
    public record MailboxSettings(String address, String host, String security, String password, String folder, String senderFilter,
                                  String subjectFilter, String codePattern, Integer timeoutSeconds, Integer pollSeconds, Boolean dedicatedMailboxConfirmed) {}

    public record MailboxView(String address, String host, String security, int port, String folder, String senderFilter, String subjectFilter,
                              String codePattern, int timeoutSeconds, int pollSeconds, String dedicatedConfirmedBy, OffsetDateTime dedicatedConfirmedAt) {}

    /** One locator as the API carries it: strategy ROLE, LABEL, PLACEHOLDER, TEST_ID or CSS; name only for ROLE. */
    public record LocatorView(String strategy, String value, String name) {}

    /** Where the login form's fields are, and what only a signed-in page, or a refused sign-in, shows. All optional. */
    public record Locators(LocatorView username, LocatorView password, LocatorView submit, LocatorView success, LocatorView failure) {
        static final Locators NONE = new Locators(null, null, null, null, null);
    }

    /** Where the one-time code goes, which button sends it, the page's error for a wrong code, and the resend button. All optional. */
    public record OtpLocators(LocatorView field, LocatorView submit, LocatorView failure, LocatorView resend) {
        static final OtpLocators NONE = new OtpLocators(null, null, null, null);
    }

    /** The latest connection test of a profile or environment. Status and failure are empty while it is queued or running. */
    public record LastTest(UUID runId, String runStatus, String status, String failure, String stage, String detail, OffsetDateTime at,
                           java.util.Map<String, LocatorView> suggestions) {}

    /**
     * @param isDefault true makes this the profile runs use; false is ignored, so an environment never
     *                  silently loses its default
     * @param locators  null keeps the current ones on an update
     * @param otp       for Email OTP; null keeps the current code settings on an update. A profile with Email OTP and no
     *                  mailbox takes codes a person types into the dashboard.
     */
    public record Settings(String name, String method, UUID credentialId, String loginPath, String submitPath, String successPath,
                           Boolean enabled, Boolean isDefault, MailboxSettings mailbox, Locators locators, Integer timeoutSeconds,
                           OtpSettings otp) {}

    /**
     * @param maxResends    how many times a new code may be asked for; needs the resend button and path
     * @param typedByPerson true: a person types the code, and any mailbox is removed with its sealed password
     */
    public record OtpSettings(OtpLocators locators, String submitPath, String resendPath, Integer maxResends, Boolean typedByPerson) {}

    /** A new profile together with a new credential for it, as the setup wizard creates them. */
    public record NewProfile(String name, String method, String username, String password, Boolean enabled, MailboxSettings mailbox,
                             String loginPath, String submitPath, String successPath, Locators locators, Integer timeoutSeconds, OtpSettings otp) {}

    /**
     * @param loginFlowComplete false while a web profile lacks a success check, or the form submit path the gate needs to
     *                          let the sign-in through: runs are refused until it has them
     */
    public record View(UUID id, UUID environmentId, String environmentName, String applicationName, String channel, String name, String method,
                       UUID credentialId, String credentialName, boolean credentialEnabled, boolean credentialShared, String usernameHint,
                       boolean enabled, boolean isDefault, String loginPath, String submitPath, String successPath, boolean loginFlowComplete,
                       MailboxView mailbox, OffsetDateTime updatedAt, Locators locators, int timeoutSeconds, LastTest lastTest, OtpSettings otp) {}

    /** The profile a new run will use, or why the run cannot start. Both null: the environment has no sign-in. */
    public record ForRun(UUID profileId, String refusal) {}

    private record Environment(String name, String classification, String channel) {}
    private record Mailbox(String address, String host, String security, int port, String folder, String sender, String subject, String pattern, int timeout, int poll) {}

    private final JdbcClient db;
    private final SecretStore box;
    private final CredentialService credentials;
    private final AuditLog audit;
    private final ObjectMapper json;

    public AuthProfiles(JdbcClient db, SecretStore box, CredentialService credentials, AuditLog audit, ObjectMapper json) {
        this.db = db;
        this.box = box;
        this.credentials = credentials;
        this.audit = audit;
        this.json = json;
    }

    // ---- reading

    private static final String SELECT = """
            SELECT ap.id, ap.environment_id, e.name, t.name, e.channel, ap.name, ap.method, ap.credential_id, c.name, c.enabled, c.shared, c.username_hint,
                   ap.enabled, ap.is_default, ap.login_path, ap.submit_path, ap.success_path, ap.updated_at,
                   m.address, m.host, m.security, m.port, m.folder, m.sender_filter, m.subject_filter, m.code_pattern, m.timeout_seconds, m.poll_seconds,
                   m.dedicated_confirmed_by, m.dedicated_confirmed_at, ap.locators::text, ap.login_timeout_seconds,
                   lt.run_id, lt.run_status, lt.status, lt.failure, lt.stage, lt.detail, lt.at, lt.suggestions::text,
                   ap.otp_locators::text, ap.otp_submit_path, ap.otp_resend_path, ap.otp_max_resends
            FROM auth_profile ap
            JOIN environment e ON e.organization_id = ap.organization_id AND e.id = ap.environment_id
            JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
            JOIN credential c ON c.organization_id = ap.organization_id AND c.id = ap.credential_id
            LEFT JOIN otp_mailbox m ON m.organization_id = ap.organization_id AND m.auth_profile_id = ap.id
            LEFT JOIN LATERAL (
                SELECT r.id AS run_id, r.status AS run_status, aa.status, aa.failure, aa.stage, aa.detail, coalesce(aa.created_at, r.created_at) AS at, aa.suggestions
                FROM test_run r LEFT JOIN auth_attempt aa ON aa.organization_id = r.organization_id AND aa.run_id = r.id
                WHERE r.organization_id = ap.organization_id AND r.kind = 'AUTH_TEST' AND r.auth_profile_id = ap.id
                ORDER BY r.created_at DESC, aa.attempt DESC NULLS LAST, aa.sign_in DESC NULLS LAST LIMIT 1) lt ON true
            """;

    private View view(java.sql.ResultSet rs) throws java.sql.SQLException {
        String channel = rs.getString(5);
        String method = rs.getString(7);
        MailboxView mailbox = rs.getString(19) == null ? null : new MailboxView(rs.getString(19), rs.getString(20), rs.getString(21), rs.getInt(22), rs.getString(23),
                rs.getString(24), rs.getString(25), rs.getString(26), rs.getInt(27), rs.getInt(28), rs.getString(29), rs.getObject(30, OffsetDateTime.class));
        Locators locators = json.readValue(rs.getString(31), Locators.class);
        LastTest last = rs.getObject(33) == null ? null : new LastTest(rs.getObject(33, UUID.class), rs.getString(34), rs.getString(35), rs.getString(36), rs.getString(37),
                rs.getString(38), rs.getObject(39, OffsetDateTime.class), suggestions(rs.getString(40)));
        return new View(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), channel, rs.getString(6), method,
                rs.getObject(8, UUID.class), rs.getString(9), rs.getBoolean(10), rs.getBoolean(11), rs.getString(12), rs.getBoolean(13), rs.getBoolean(14),
                rs.getString(15), rs.getString(16), rs.getString(17), complete(channel, method, rs.getString(16), rs.getString(17), locators, rs.getString(42)), mailbox,
                rs.getObject(18, OffsetDateTime.class), locators, rs.getInt(32), last,
                method.equals("PASSWORD_EMAIL_OTP") ? new OtpSettings(json.readValue(rs.getString(41), OtpLocators.class), rs.getString(42), rs.getString(43), rs.getInt(44), mailbox == null) : null);
    }

    @SuppressWarnings("unchecked")
    java.util.Map<String, LocatorView> suggestions(String text) {
        if (text == null) return java.util.Map.of();
        var out = new java.util.LinkedHashMap<String, LocatorView>();
        json.readValue(text, java.util.Map.class).forEach((k, v) -> out.put((String) k, json.convertValue(v, LocatorView.class)));
        return out;
    }

    /**
     * A web sign-in needs a way to tell success (a success path, a signed-in marker, or both), and the form's
     * submit path, which is what the gate lets through during sign-in. A session cookie submits no form.
     */
    static boolean complete(String channel, String method, String submitPath, String successPath, Locators locators, String otpSubmitPath) {
        if (channel.equals("API")) return true;
        boolean check = successPath != null || (locators != null && locators.success() != null);
        // The code goes through the gate as its own request, so Email OTP needs that path as well.
        return check && (method.equals("SESSION_IMPORT") || submitPath != null) && (!method.equals("PASSWORD_EMAIL_OTP") || otpSubmitPath != null);
    }

    public List<View> list(UUID org, UUID projectId) {
        return db.sql(SELECT + " WHERE ap.organization_id = ? AND t.project_id = ? AND e.archived_at IS NULL ORDER BY t.name, e.name, ap.is_default DESC, ap.name")
                .params(org, projectId).query((rs, n) -> view(rs)).list();
    }

    public View get(UUID org, UUID projectId, UUID id) {
        return db.sql(SELECT + " WHERE ap.organization_id = ? AND t.project_id = ? AND ap.id = ?").params(org, projectId, id)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> new NotFoundException("Sign-in profile"));
    }

    public Optional<View> defaultFor(UUID org, UUID environmentId) {
        return db.sql(SELECT + " WHERE ap.organization_id = ? AND ap.environment_id = ? AND ap.is_default").params(org, environmentId)
                .query((rs, n) -> view(rs)).optional();
    }

    /** Decides what a new run of this environment signs in with. Anything that would make the sign-in fail or be skipped refuses the run. */
    public ForRun forRun(UUID org, UUID environmentId) {
        Optional<View> found = defaultFor(org, environmentId);
        return found.isEmpty() ? new ForRun(null, null) : usable(found.get());
    }

    /**
     * The profile a connection test signs in with: the one asked for, which must belong to the environment, or
     * else the default. With neither, the test only checks the connection.
     */
    public ForRun forTest(UUID org, UUID projectId, UUID environmentId, UUID profileId) {
        if (profileId == null) return forRun(org, environmentId);
        View v = get(org, projectId, profileId);
        if (!v.environmentId().equals(environmentId)) throw new NotFoundException("Sign-in profile");
        return usable(v);
    }

    private static ForRun usable(View v) {
        if (!v.enabled()) return new ForRun(null, "The environment's sign-in profile is disabled");
        if (!v.credentialEnabled()) return new ForRun(null, "The sign-in profile's credential is disabled");
        if (!v.loginFlowComplete()) return new ForRun(null, "Finish the sign-in profile's login flow before running");
        return new ForRun(v.id(), null);
    }

    // ---- writing

    private Environment environment(UUID org, UUID projectId, UUID environmentId) {
        return db.sql("""
                SELECT e.name, e.classification, e.channel FROM environment e JOIN target t ON t.organization_id = e.organization_id AND t.id = e.target_id
                WHERE e.organization_id = ? AND e.id = ? AND t.project_id = ? AND e.archived_at IS NULL
                """).params(org, environmentId, projectId).query((rs, n) -> new Environment(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().orElseThrow(() -> new NotFoundException("Environment"));
    }

    private static String text(String s) {
        return s == null ? "" : s.strip();
    }

    private static String path(String s) {
        String p = text(s);
        if (p.isEmpty()) return null;
        if (!p.matches(RELATIVE)) throw new ConflictException("Login paths must be relative paths");
        return p;
    }

    private void check(Principal p, Environment env, String name, String method) {
        if (name.isEmpty() || name.length() > 100) throw new ConflictException("A sign-in profile needs a name of up to 100 characters");
        if (!METHODS.contains(method)) throw new ConflictException("Unsupported login method");
        if (env.classification().equals("PRODUCTION") && !p.organizationAdmin()) throw new AccessDeniedException("Production login needs Organization Admin");
        // A password must never be sent as a bearer token, and a token never typed into a login form.
        if (env.channel().equals("API") != method.equals("API_TOKEN")) throw new ConflictException("Login method does not fit this channel");
    }

    /**
     * A credential is bound to the first environment that uses it and refused everywhere else. Shared
     * credentials from before binding existed keep working until they are migrated.
     */
    private void bind(Principal p, UUID projectId, UUID environmentId, UUID credentialId) {
        record Owner(UUID environment, boolean shared) {}
        Owner owner = db.sql("SELECT environment_id, shared FROM credential WHERE organization_id = ? AND project_id = ? AND id = ?")
                .params(p.organizationId(), projectId, credentialId).query((rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getBoolean(2)))
                .optional().orElseThrow(() -> new NotFoundException("Credential"));
        if (owner.shared() || environmentId.equals(owner.environment())) return;
        if (owner.environment() == null && db.sql("UPDATE credential SET environment_id = ?, updated_at = now() WHERE organization_id = ? AND id = ? AND environment_id IS NULL AND NOT shared")
                .params(environmentId, p.organizationId(), credentialId).update() == 1) {
            audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_BOUND", credentialId.toString(), "environment " + environmentId);
            return;
        }
        throw new ConflictException("This credential belongs to another environment");
    }

    private void makeDefault(UUID org, UUID environmentId, UUID profileId) {
        db.sql("UPDATE auth_profile SET is_default = false WHERE organization_id = ? AND environment_id = ? AND is_default AND id <> ?").params(org, environmentId, profileId).update();
        db.sql("UPDATE auth_profile SET is_default = true, updated_at = now() WHERE organization_id = ? AND id = ?").params(org, profileId).update();
    }

    private boolean hasDefault(UUID org, UUID environmentId) {
        return db.sql("SELECT count(*) FROM auth_profile WHERE organization_id = ? AND environment_id = ? AND is_default").params(org, environmentId).query(Long.class).single() > 0;
    }

    public UUID create(Principal p, UUID projectId, UUID environmentId, Settings s) {
        Environment env = environment(p.organizationId(), projectId, environmentId);
        String name = text(s.name());
        String method = text(s.method());
        check(p, env, name, method);
        if (s.credentialId() == null) throw new ConflictException("Choose a credential");
        String login = path(s.loginPath()), submit = path(s.submitPath()), success = path(s.successPath());
        Flow flow = flow(s.locators(), s.timeoutSeconds());
        Code code = code(method, s.otp());
        if (!method.equals("PASSWORD_EMAIL_OTP") && s.mailbox() != null) throw new ConflictException("A mailbox is only used with Email OTP");
        if (s.otp() != null && Boolean.TRUE.equals(s.otp().typedByPerson()) && s.mailbox() != null) throw new ConflictException("A code typed by a person needs no mailbox");
        Mailbox mailbox = s.mailbox() == null ? null : mailbox(s.mailbox());
        bind(p, projectId, environmentId, s.credentialId());

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO auth_profile (organization_id, id, environment_id, name, method, credential_id, enabled, login_path, submit_path, success_path, locators, login_timeout_seconds,
                                          otp_locators, otp_submit_path, otp_resend_path, otp_max_resends)
                VALUES (?,?,?,?,?,?,?,?,?,?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
                """).params(p.organizationId(), id, environmentId, name, method, s.credentialId(), s.enabled() == null || s.enabled(), login, submit, success,
                flow.json(), flow.timeout(), code.json(), code.submitPath(), code.resendPath(), code.maxResends()).update();
        if (Boolean.TRUE.equals(s.isDefault()) || !hasDefault(p.organizationId(), environmentId)) makeDefault(p.organizationId(), environmentId, id);
        if (mailbox != null) saveMailbox(p, projectId, id, mailbox, s.mailbox(), null);
        audit.record(p.organizationId(), projectId, p.email(), "AUTH_PROFILE_CREATED", id.toString(), method + " " + name);
        return id;
    }

    /** Creates a credential bound to the environment, and a profile that uses it, in the caller's transaction. */
    public UUID createWithCredential(Principal p, UUID projectId, UUID environmentId, String credentialName, NewProfile n) {
        String method = text(n.method());
        if (!ONBOARDING_METHODS.contains(method)) throw new ConflictException("Choose username and password, with or without email OTP");
        if (!CredentialService.present(n.username()) || !CredentialService.present(n.password())) throw new ConflictException("Enter the username and password");
        environment(p.organizationId(), projectId, environmentId);
        String unique = credentialName;
        boolean taken = db.sql("SELECT count(*) FROM credential WHERE organization_id = ? AND project_id = ? AND name = ?").params(p.organizationId(), projectId, unique).query(Long.class).single() > 0;
        if (taken) unique = credentialName + " · " + environmentId.toString().substring(0, 8);
        UUID credential = credentials.store(p.organizationId(), projectId, environmentId, unique, new CredentialService.Material(n.username(), n.password(), null, null, null, null));
        audit.record(p.organizationId(), projectId, p.email(), "CREDENTIAL_CREATED", credential.toString(), unique);
        String name = text(n.name()).isEmpty() ? "Default" : text(n.name());
        return create(p, projectId, environmentId, new Settings(name, method, credential, n.loginPath(), n.submitPath(), n.successPath(), n.enabled(), true, n.mailbox(),
                n.locators(), n.timeoutSeconds(), n.otp()));
    }

    public void update(Principal p, UUID projectId, UUID id, Settings s) {
        View current = get(p.organizationId(), projectId, id);
        Environment env = environment(p.organizationId(), projectId, current.environmentId());
        String name = text(s.name());
        String method = text(s.method());
        check(p, env, name, method);
        UUID credential = s.credentialId() == null ? current.credentialId() : s.credentialId();
        String login = path(s.loginPath()), submit = path(s.submitPath()), success = path(s.successPath());
        Flow flow = flow(s.locators() == null ? current.locators() : s.locators(), s.timeoutSeconds() == null ? current.timeoutSeconds() : s.timeoutSeconds());
        boolean otp = method.equals("PASSWORD_EMAIL_OTP");
        if (!otp && s.mailbox() != null) throw new ConflictException("A mailbox is only used with Email OTP");
        Code code = code(method, s.otp() != null ? s.otp() : current.otp());
        Mailbox mailbox = s.mailbox() == null ? null : mailbox(s.mailbox());
        if (!credential.equals(current.credentialId())) bind(p, projectId, current.environmentId(), credential);

        db.sql("""
                UPDATE auth_profile SET name = ?, method = ?, credential_id = ?, login_path = ?, submit_path = ?, success_path = ?,
                       enabled = coalesce(?, enabled), locators = ?::jsonb, login_timeout_seconds = ?, otp_locators = ?::jsonb, otp_submit_path = ?, otp_resend_path = ?,
                       otp_max_resends = ?, updated_at = now()
                WHERE organization_id = ? AND id = ?
                """).params(name, method, credential, login, submit, success, s.enabled(), flow.json(), flow.timeout(), code.json(), code.submitPath(), code.resendPath(),
                code.maxResends(), p.organizationId(), id).update();
        if (Boolean.TRUE.equals(s.isDefault())) makeDefault(p.organizationId(), current.environmentId(), id);
        boolean byPerson = s.otp() != null && Boolean.TRUE.equals(s.otp().typedByPerson());
        if (otp && byPerson && s.mailbox() != null) throw new ConflictException("A code typed by a person needs no mailbox");
        if (!otp || byPerson) db.sql("DELETE FROM otp_mailbox WHERE organization_id = ? AND auth_profile_id = ?").params(p.organizationId(), id).update();
        else if (mailbox != null) saveMailbox(p, projectId, id, mailbox, s.mailbox(), current.mailbox());
        audit.record(p.organizationId(), projectId, p.email(), "AUTH_PROFILE_UPDATED", id.toString(), method + " " + name);
    }

    public void setEnabled(Principal p, UUID projectId, UUID id, boolean enabled) {
        get(p.organizationId(), projectId, id);
        db.sql("UPDATE auth_profile SET enabled = ?, updated_at = now() WHERE organization_id = ? AND id = ?").params(enabled, p.organizationId(), id).update();
        audit.record(p.organizationId(), projectId, p.email(), enabled ? "AUTH_PROFILE_ENABLED" : "AUTH_PROFILE_DISABLED", id.toString(), "");
    }

    /** Refused while an unfinished run signs in with it. A deleted default hands over to the oldest remaining profile. */
    public void delete(Principal p, UUID projectId, UUID id) {
        View current = get(p.organizationId(), projectId, id);
        long running = db.sql("SELECT count(*) FROM test_run WHERE organization_id = ? AND auth_profile_id = ? AND finished_at IS NULL")
                .params(p.organizationId(), id).query(Long.class).single();
        if (running > 0) throw new ConflictException("A run that has not finished uses this sign-in profile");
        db.sql("DELETE FROM auth_profile WHERE organization_id = ? AND id = ?").params(p.organizationId(), id).update();
        if (current.isDefault()) {
            db.sql("""
                    UPDATE auth_profile SET is_default = true WHERE organization_id = ? AND id = (
                        SELECT id FROM auth_profile WHERE organization_id = ? AND environment_id = ? ORDER BY created_at, id LIMIT 1)
                    """).params(p.organizationId(), p.organizationId(), current.environmentId()).update();
        }
        audit.record(p.organizationId(), projectId, p.email(), "AUTH_PROFILE_DELETED", id.toString(), current.name());
    }

    // ---- login flow

    private record Flow(String json, int timeout) {}

    /** Checked by the same rules the worker applies, so a profile the platform accepts is one the worker can follow. */
    private Flow flow(Locators given, Integer timeoutSeconds) {
        Locators l = given == null ? Locators.NONE : given;
        try {
            var form = new LoginForm(spec(l.username()), spec(l.password()), spec(l.submit()), spec(l.success()), spec(l.failure()), timeoutSeconds == null ? 0 : timeoutSeconds);
            var stored = new java.util.LinkedHashMap<String, LocatorView>();
            put(stored, "username", form.username());
            put(stored, "password", form.password());
            put(stored, "submit", form.submit());
            put(stored, "success", form.successElement());
            put(stored, "failure", form.failureElement());
            return new Flow(json.writeValueAsString(stored), form.timeoutSeconds());
        } catch (PolicyException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    private static LocatorSpec spec(LocatorView v) {
        if (v == null || v.value() == null || v.value().isBlank()) return null;
        LocatorSpec.Strategy strategy;
        try {
            strategy = LocatorSpec.Strategy.valueOf(String.valueOf(v.strategy()));
        } catch (IllegalArgumentException e) {
            throw new PolicyException("Unsupported locator strategy");
        }
        return new LocatorSpec(strategy, v.value(), v.name());
    }

    private static void put(java.util.Map<String, LocatorView> out, String key, LocatorSpec spec) {
        if (spec != null) out.put(key, new LocatorView(spec.strategy().name(), spec.value(), spec.name()));
    }

    // ---- one-time code

    private record Code(String json, String submitPath, String resendPath, int maxResends) {}

    /** Checked by the rules the worker follows. Other methods keep no code settings. */
    private Code code(String method, OtpSettings given) {
        if (!method.equals("PASSWORD_EMAIL_OTP")) return new Code("{}", null, null, 0);
        OtpSettings o = given == null ? new OtpSettings(OtpLocators.NONE, null, null, 0, null) : given;
        OtpLocators l = o.locators() == null ? OtpLocators.NONE : o.locators();
        String submit = path(o.submitPath()), resend = path(o.resendPath());
        int resends = o.maxResends() == null ? 0 : o.maxResends();
        if (resends > 0 && resend == null) throw new ConflictException("Set the resend path to allow resends");
        try {
            var step = new OtpStep(spec(l.field()), spec(l.submit()), spec(l.failure()), spec(l.resend()), resends, java.time.Duration.ofMinutes(5), "otp", resend == null ? null : "otp-resend");
            var stored = new java.util.LinkedHashMap<String, LocatorView>();
            put(stored, "field", step.field());
            put(stored, "submit", step.submit());
            put(stored, "failure", step.failureElement());
            put(stored, "resend", step.resend());
            return new Code(json.writeValueAsString(stored), submit, resend, resends);
        } catch (PolicyException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    // ---- mailbox

    private static Mailbox mailbox(MailboxSettings m) {
        String address = text(m.address());
        if (!ADDRESS.matcher(address).matches() || address.length() > 254) throw new ConflictException("Enter the mailbox address");
        String host;
        try {
            host = BaseUrl.host(m.host());
        } catch (PolicyException e) {
            throw new ConflictException("Mail server: " + e.getMessage());
        }
        String security = m.security() == null || m.security().isBlank() ? "IMAPS" : m.security();
        if (!security.equals("IMAPS") && !security.equals("STARTTLS")) throw new ConflictException("Mailbox security must be IMAPS or STARTTLS");
        String folder = text(m.folder()).isEmpty() ? "INBOX" : text(m.folder());
        String sender = text(m.senderFilter()), subject = text(m.subjectFilter());
        if (folder.length() > 100 || sender.length() > 254 || subject.length() > 200 || CONTROL.matcher(folder + sender + subject).find()) {
            throw new ConflictException("Mailbox folder or filters are not valid");
        }
        String pattern = text(m.codePattern()).isEmpty() ? DEFAULT_CODE_PATTERN : text(m.codePattern());
        if (pattern.length() > 200) throw new ConflictException("The code pattern is too long");
        try {
            if (Pattern.compile(pattern).matcher("").groupCount() > 1) throw new ConflictException("The code pattern may capture at most one group");
        } catch (PatternSyntaxException e) {
            throw new ConflictException("The code pattern is not a valid regular expression");
        }
        int timeout = m.timeoutSeconds() == null ? 120 : m.timeoutSeconds();
        int poll = m.pollSeconds() == null ? 5 : m.pollSeconds();
        if (timeout < 10 || timeout > 600 || poll < 2 || poll > 60 || poll >= timeout) throw new ConflictException("Mailbox timing is outside the allowed range");
        return new Mailbox(address, host, security, security.equals("IMAPS") ? 993 : 143, folder, sender, subject, pattern, timeout, poll);
    }

    private static String mailboxOwner(UUID org, UUID profile) {
        return "mailbox/" + org + "/" + profile;
    }

    private void saveMailbox(Principal p, UUID projectId, UUID profile, Mailbox m, MailboxSettings given, MailboxView current) {
        boolean newPlace = current == null || !current.address().equalsIgnoreCase(m.address()) || !current.host().equals(m.host());
        if (newPlace && !Boolean.TRUE.equals(given.dedicatedMailboxConfirmed())) throw new ConflictException("Confirm this is a dedicated test mailbox you are authorized to use");
        boolean newPassword = CredentialService.present(given.password());
        if (current == null && !newPassword) throw new ConflictException("Enter the mailbox password");
        if (current == null) {
            var sealed = box.seal(given.password(), mailboxOwner(p.organizationId(), profile));
            db.sql("""
                    INSERT INTO otp_mailbox (organization_id, auth_profile_id, address, host, security, port, folder, sender_filter, subject_filter, code_pattern,
                                             timeout_seconds, poll_seconds, wrapped_key, ciphertext, dedicated_confirmed_by, dedicated_confirmed_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
                    """).params(p.organizationId(), profile, m.address(), m.host(), m.security(), m.port(), m.folder(), m.sender(), m.subject(), m.pattern(),
                    m.timeout(), m.poll(), sealed.wrappedKey(), sealed.ciphertext(), p.email()).update();
        } else {
            db.sql("""
                    UPDATE otp_mailbox SET address = ?, host = ?, security = ?, port = ?, folder = ?, sender_filter = ?, subject_filter = ?, code_pattern = ?,
                           timeout_seconds = ?, poll_seconds = ?, updated_at = now(),
                           dedicated_confirmed_by = CASE WHEN ? THEN ? ELSE dedicated_confirmed_by END,
                           dedicated_confirmed_at = CASE WHEN ? THEN now() ELSE dedicated_confirmed_at END
                    WHERE organization_id = ? AND auth_profile_id = ?
                    """).params(m.address(), m.host(), m.security(), m.port(), m.folder(), m.sender(), m.subject(), m.pattern(), m.timeout(), m.poll(),
                    newPlace, p.email(), newPlace, p.organizationId(), profile).update();
            if (newPassword) {
                var sealed = box.seal(given.password(), mailboxOwner(p.organizationId(), profile));
                db.sql("UPDATE otp_mailbox SET wrapped_key = ?, ciphertext = ? WHERE organization_id = ? AND auth_profile_id = ?")
                        .params(sealed.wrappedKey(), sealed.ciphertext(), p.organizationId(), profile).update();
            }
        }
        audit.record(p.organizationId(), projectId, p.email(), "OTP_MAILBOX_SET", profile.toString(), m.host() + (newPassword ? ", password replaced" : ""));
    }
}
