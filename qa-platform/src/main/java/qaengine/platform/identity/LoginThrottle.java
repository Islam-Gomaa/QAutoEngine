package qaengine.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Slows password guessing per account. In memory, which is enough for one platform instance. */
@Component
public class LoginThrottle {

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private record Failures(int count, Instant since) {}

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    public boolean blocked(String email) {
        Failures f = failures.get(email);
        if (f == null) return false;
        if (clock.instant().isAfter(f.since().plus(WINDOW))) {
            failures.remove(email);
            return false;
        }
        return f.count() >= MAX_FAILURES;
    }

    public void failed(String email) {
        failures.merge(email, new Failures(1, clock.instant()), (a, b) -> new Failures(a.count() + 1, a.since()));
    }

    public void succeeded(String email) {
        failures.remove(email);
    }
}
