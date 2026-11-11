package qaengine.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import qaengine.contracts.WorkerContracts.AuthReport;
import qaengine.contracts.WorkerContracts.OtpReply;
import qaengine.core.auth.AuthenticationResult.Failure;
import qaengine.core.auth.OtpSource;
import qaengine.core.evidence.Redactor;

/**
 * One-time codes from the platform, which reads the test mailbox or takes a code a person typed. The
 * worker never learns which. A code is taught to the redactor before it is used, so nothing that leaves
 * the worker afterwards can carry it.
 */
final class PlatformCodes implements OtpSource {

    private final PlatformClient platform;
    private final Redactor redactor;
    private final int signIn;
    private boolean announced;

    /** @param signIn which sign-in of the run this is, so the dashboard asks for the right one */
    PlatformCodes(PlatformClient platform, Redactor redactor, int signIn) {
        this.platform = platform;
        this.redactor = redactor;
        this.signIn = signIn;
    }

    @Override
    public void requested() {
        call(platform::otpRequested);
    }

    /** Says so once, so the dashboard can ask a person for the code while the browser session waits. */
    @Override
    public void waiting() {
        if (announced) return;
        announced = true;
        call(() -> platform.auth(new AuthReport("WAITING_FOR_OTP", null, "OTP", "Waiting for the one-time code", 0, java.util.Map.of(), signIn)));
    }

    @Override
    public Reply poll() {
        OtpReply r;
        try {
            r = platform.otp();
        } catch (IOException e) {
            // A missed poll is not a missed code: ask again shortly. The platform's own limits still end the wait.
            return Reply.waitFor(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.stop(Failure.OTP_NOT_RECEIVED, "Waiting for the one-time code was interrupted");
        }
        if (r.code() != null) {
            redactor.remember(r.code());
            return Reply.code(r.code());
        }
        if (r.failure() != null) {
            Failure f;
            try {
                f = Failure.valueOf(r.failure());
            } catch (IllegalArgumentException e) {
                f = Failure.OTP_NOT_RECEIVED;
            }
            return Reply.stop(f, redactor.redact(r.detail()));
        }
        return Reply.waitFor(Duration.ofSeconds(Math.max(1, Math.min(60, r.retryAfterSeconds()))));
    }

    private interface Call { void run() throws IOException, InterruptedException; }

    private static void call(Call c) {
        try {
            c.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
