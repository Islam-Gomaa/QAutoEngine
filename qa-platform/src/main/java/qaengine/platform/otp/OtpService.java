package qaengine.platform.otp;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import qaengine.platform.audit.AuditLog;
import qaengine.platform.identity.SecretStore;
import qaengine.platform.shared.ConflictException;
import qaengine.platform.shared.NotFoundException;

/**
 * Where a waiting sign-in gets its one-time code. A code a person typed for the run comes first; then
 * the dedicated test mailbox, read no more often than its polling interval. When the mailbox cannot be
 * used, the wait goes on for a person to type the code. Every code is used once: a typed one is sealed
 * until used and then deleted, and an emailed one is never stored, only the hash of its message.
 */
@Component
public class OtpService {

    /** How long a person has to type a code, from the moment the target was asked to send it. */
    public static final Duration MANUAL_WAIT = Duration.ofMinutes(5);
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9]{4,12}$");

    /** @param failure an AuthenticationResult.Failure name; detail is fixed text, never a code or message content */
    public record Reply(String code, String failure, String detail, int retryAfterSeconds, boolean waitingForPerson) {}

    private record Waiting(OffsetDateTime requestedAt, OffsetDateTime checkedAt, String mailboxError, OffsetDateTime now) {}

    private record Mailbox(UUID profileId, String address, String host, String security, int port, String folder, String sender, String subject,
                           String pattern, int timeoutSeconds, int pollSeconds, byte[] wrappedKey, byte[] ciphertext) {}

    private final JdbcClient db;
    private final SecretStore box;
    private final AuditLog audit;
    private final MailAccess access;
    private final OtpMailbox mailbox;

    public OtpService(JdbcClient db, SecretStore box, AuditLog audit, MailAccess access, OtpMailbox mailbox) {
        this.db = db;
        this.box = box;
        this.audit = audit;
        this.access = access;
        this.mailbox = mailbox;
    }

    /** The target is about to send a code. Older mail and any code typed before now no longer count. */
    public void requested(UUID org, UUID run, int attempt) {
        db.sql("""
                INSERT INTO otp_request (organization_id, run_id, attempt) VALUES (?,?,?)
                ON CONFLICT (organization_id, run_id, attempt) DO UPDATE SET requested_at = now(), mailbox_checked_at = NULL, mailbox_error = NULL
                """).params(org, run, attempt).update();
        db.sql("DELETE FROM otp_submission WHERE organization_id = ? AND run_id = ? AND attempt = ?").params(org, run, attempt).update();
        audit.record(org, "worker", "OTP_REQUESTED", run.toString(), "attempt " + attempt);
    }

    /** One check, for the worker. Never blocks for longer than one mailbox read. */
    public Reply poll(UUID org, UUID run, int attempt) {
        Optional<Waiting> found = db.sql("""
                SELECT requested_at, mailbox_checked_at, mailbox_error, now() FROM otp_request WHERE organization_id = ? AND run_id = ? AND attempt = ?
                """).params(org, run, attempt).query((rs, n) -> new Waiting(rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class))).optional();
        if (found.isEmpty()) return new Reply(null, "OTP_NOT_RECEIVED", "No code was requested for this sign-in", 0, false);
        Waiting w = found.get();

        Optional<String> typed = takeTyped(org, run, attempt);
        if (typed.isPresent()) {
            audit.record(org, "worker", "OTP_RELEASED", run.toString(), "entered by a person");
            return new Reply(typed.get(), null, null, 0, false);
        }

        String mailboxError = w.mailboxError();
        Mailbox m = mailboxOf(org, run);
        if (m != null && mailboxError == null) {
            if (!w.now().isBefore(w.requestedAt().plusSeconds(m.timeoutSeconds()))) {
                return new Reply(null, "OTP_NOT_RECEIVED", "No matching email arrived within " + m.timeoutSeconds() + " seconds", 0, false);
            }
            if (w.checkedAt() == null || !w.now().isBefore(w.checkedAt().plusSeconds(m.pollSeconds()))) {
                db.sql("UPDATE otp_request SET mailbox_checked_at = now() WHERE organization_id = ? AND run_id = ? AND attempt = ?").params(org, run, attempt).update();
                Optional<String> refused = access.refusal(m.host());
                OtpMailbox.Result result = refused.isPresent() ? new OtpMailbox.Failed(refused.get()) : read(org, m, w);
                switch (result) {
                    case OtpMailbox.Found f -> {
                        db.sql("INSERT INTO otp_consumed_message (organization_id, auth_profile_id, message_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                                .params(org, m.profileId(), f.messageHash()).update();
                        audit.record(org, "worker", "OTP_RELEASED", run.toString(), "read from the test mailbox");
                        return new Reply(f.code(), null, null, 0, false);
                    }
                    case OtpMailbox.Unreadable u -> {
                        return new Reply(null, "OTP_EXTRACTION_FAILED", u.detail(), 0, false);
                    }
                    case OtpMailbox.Failed f -> {
                        mailboxError = f.detail();
                        db.sql("UPDATE otp_request SET mailbox_error = ? WHERE organization_id = ? AND run_id = ? AND attempt = ?").params(mailboxError, org, run, attempt).update();
                        audit.record(org, "worker", "OTP_MAILBOX_UNAVAILABLE", run.toString(), mailboxError);
                    }
                    case OtpMailbox.NotYet n -> { }
                }
            }
            if (mailboxError == null) return new Reply(null, null, "Waiting for the email with the code", m.pollSeconds(), false);
        }

        String why = mailboxError == null ? "" : mailboxError + ". ";
        if (!w.now().isBefore(w.requestedAt().plus(MANUAL_WAIT))) {
            return new Reply(null, "OTP_NOT_RECEIVED", why + "No code was entered within " + MANUAL_WAIT.toMinutes() + " minutes", 0, false);
        }
        return new Reply(null, null, why + "Waiting for someone to enter the code", 2, true);
    }

    private OtpMailbox.Result read(UUID org, Mailbox m, Waiting w) {
        String password = box.open(new SecretStore.Sealed(m.wrappedKey(), m.ciphertext()), "mailbox/" + org + "/" + m.profileId());
        var consumed = new HashSet<>(db.sql("SELECT message_hash FROM otp_consumed_message WHERE organization_id = ? AND auth_profile_id = ?")
                .params(org, m.profileId()).query(String.class).list());
        return mailbox.read(new OtpMailbox.Request(m.host(), m.port(), m.security(), m.address(), password, m.folder(), m.sender(), m.subject(),
                Pattern.compile(m.pattern()), w.requestedAt().toInstant(), consumed));
    }

    private Mailbox mailboxOf(UUID org, UUID run) {
        return db.sql("""
                SELECT m.auth_profile_id, m.address, m.host, m.security, m.port, m.folder, m.sender_filter, m.subject_filter, m.code_pattern, m.timeout_seconds,
                       m.poll_seconds, m.wrapped_key, m.ciphertext
                FROM test_run r JOIN otp_mailbox m ON m.organization_id = r.organization_id AND m.auth_profile_id = r.auth_profile_id
                WHERE r.organization_id = ? AND r.id = ?
                """).params(org, run).query((rs, n) -> new Mailbox(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10), rs.getInt(11), rs.getBytes(12), rs.getBytes(13))).optional().orElse(null);
    }

    private static String owner(UUID org, UUID run, int attempt) {
        return "otp/" + org + "/" + run + "/" + attempt;
    }

    private Optional<String> takeTyped(UUID org, UUID run, int attempt) {
        return db.sql("DELETE FROM otp_submission WHERE organization_id = ? AND run_id = ? AND attempt = ? RETURNING wrapped_key, ciphertext").params(org, run, attempt)
                .query((rs, n) -> new SecretStore.Sealed(rs.getBytes(1), rs.getBytes(2))).optional()
                .map(sealed -> box.open(sealed, owner(org, run, attempt)));
    }

    /**
     * A person types the code for a run that is waiting for one. Sealed until the worker takes it, which it
     * does once. The caller has checked the person may act on the run.
     */
    public void typed(UUID org, UUID projectId, UUID run, String actor, String code) {
        record Current(int attempt, String status) {}
        List<Current> current = db.sql("""
                SELECT a.attempt, a.status FROM auth_attempt a JOIN test_run r ON r.organization_id = a.organization_id AND r.id = a.run_id
                WHERE a.organization_id = ? AND a.run_id = ? AND r.project_id = ? AND r.finished_at IS NULL ORDER BY a.attempt DESC, a.sign_in DESC LIMIT 1
                """).params(org, run, projectId).query((rs, n) -> new Current(rs.getInt(1), rs.getString(2))).list();
        if (current.isEmpty() || !current.getFirst().status().equals("WAITING_FOR_OTP")) throw new ConflictException("This run is not waiting for a code");
        String value = code == null ? "" : code.strip();
        if (!CODE.matcher(value).matches()) throw new ConflictException("Enter the code: 4 to 12 letters or digits");
        int attempt = current.getFirst().attempt();
        int requested = db.sql("SELECT count(*) FROM otp_request WHERE organization_id = ? AND run_id = ? AND attempt = ?").params(org, run, attempt).query(Integer.class).single();
        if (requested == 0) throw new NotFoundException("Run");
        var sealed = box.seal(value, owner(org, run, attempt));
        db.sql("""
                INSERT INTO otp_submission (organization_id, run_id, attempt, wrapped_key, ciphertext, submitted_by) VALUES (?,?,?,?,?,?)
                ON CONFLICT (organization_id, run_id, attempt) DO UPDATE SET wrapped_key = EXCLUDED.wrapped_key, ciphertext = EXCLUDED.ciphertext,
                    submitted_by = EXCLUDED.submitted_by, submitted_at = now()
                """).params(org, run, attempt, sealed.wrappedKey(), sealed.ciphertext(), actor).update();
        audit.record(org, projectId, actor, "OTP_ENTERED", run.toString(), "attempt " + attempt);
    }
}
