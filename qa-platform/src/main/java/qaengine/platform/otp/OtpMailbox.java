package qaengine.platform.otp;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads one-time codes from a dedicated test mailbox. The provider abstraction: IMAP today, an email API
 * later. Implementations never log, store or return a message body, and never read mail older than the request.
 */
public interface OtpMailbox {

    /**
     * @param since    only mail received at or after this moment, less a small clock allowance, is considered
     * @param consumed hashes of messages a code was already taken from; those are skipped
     */
    record Request(String host, int port, String security, String username, String password, String folder, String senderFilter,
                   String subjectFilter, Pattern codePattern, Instant since, Set<String> consumed) {}

    sealed interface Result permits Found, NotYet, Unreadable, Failed {}

    /** @param messageHash identifies the email, so it is never used again */
    record Found(String code, String messageHash) implements Result {}

    record NotYet() implements Result {}

    /** A matching email arrived, but the code pattern found nothing in it. */
    record Unreadable(String detail) implements Result {}

    /** The mailbox could not be reached or read. The detail is fixed text. */
    record Failed(String detail) implements Result {}

    Result read(Request request);
}
