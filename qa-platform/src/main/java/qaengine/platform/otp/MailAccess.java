package qaengine.platform.otp;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether the platform may connect to a mail server. Off unless a deployment names its mail
 * servers, like the egress allowlist for targets. A server that resolves to a private, loopback or
 * link-local address is refused unless it is approved as private, which closes the SSRF path through
 * a mailbox setting.
 */
@Component
public class MailAccess {

    private final Set<String> allowed;
    private final Set<String> privateApproved;

    public MailAccess(@Value("${qa.otp-mail.allowed-hosts:}") String allowed, @Value("${qa.otp-mail.private-hosts:}") String privateApproved) {
        this.allowed = split(allowed);
        this.privateApproved = split(privateApproved);
    }

    private static Set<String> split(String list) {
        return Arrays.stream(list.split(",")).map(String::strip).map(s -> s.toLowerCase(java.util.Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** Why the platform will not connect to this mail server, or empty when it may. Fixed text only. */
    public Optional<String> refusal(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (!allowed.contains(h)) return Optional.of("Mailbox reading is not enabled for this mail server");
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            return Optional.of("The mail server name did not resolve");
        }
        if (!privateApproved.contains(h) && Arrays.stream(addresses).anyMatch(MailAccess::isPrivate)) {
            return Optional.of("The mail server resolves to a private address");
        }
        return Optional.empty();
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) return (b[0] & 0xfe) == 0xfc; // unique local fc00::/7
        int first = b[0] & 0xff, second = b[1] & 0xff;
        return first == 0 || (first == 100 && second >= 64 && second <= 127) || (first == 169 && second == 254) || (first == 198 && (second == 18 || second == 19)) || first >= 224;
    }
}
