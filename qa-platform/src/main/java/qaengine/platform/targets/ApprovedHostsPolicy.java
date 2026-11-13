package qaengine.platform.targets;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import qaengine.core.policy.ApprovedHosts;
import qaengine.core.policy.ExecutionLock;

/**
 * Loads the deployment's approved-hosts file into {@link ExecutionLock} as the platform starts, and records what
 * changed since the last start: each host approved or removed, by whom, under which change. The file is read once:
 * a change takes effect when the platform, the worker and the proxy restart together, so they never disagree for
 * longer than that. An invalid file stops the platform from starting.
 */
@Component
public class ApprovedHostsPolicy {

    public record Event(String host, int port, String event, String approvedBy, String change, String reason, OffsetDateTime recordedAt) {}
    public record View(String fingerprint, List<ApprovedHosts.Entry> hosts, List<Event> history) {}

    private final JdbcClient db;
    private final ApprovedHosts hosts;

    public ApprovedHostsPolicy(JdbcClient db, @Value("${qa.approved-hosts-file:}") String file) {
        this.db = db;
        this.hosts = ApprovedHosts.load(file == null || file.isBlank() ? null : Path.of(file));
        ExecutionLock.configure(hosts);
        // The same line the worker and the proxy print, so an operator can see the three loaded the same version.
        System.out.println("approved hosts " + hosts.fingerprint() + " (" + hosts.entries().size() + " real hosts)");
    }

    public ApprovedHosts hosts() {
        return hosts;
    }

    /** Records the difference between the file and what this database last recorded, once the schema is ready. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recordChanges() {
        Map<String, Boolean> known = new LinkedHashMap<>();
        db.sql("SELECT DISTINCT ON (host, port) host || ':' || port, event FROM approved_host_event ORDER BY host, port, id DESC")
                .query((rs, n) -> known.put(rs.getString(1), "APPROVED".equals(rs.getString(2)))).list();
        for (ApprovedHosts.Entry e : hosts.entries()) {
            if (Boolean.TRUE.equals(known.get(e.hostPort()))) continue;
            db.sql("INSERT INTO approved_host_event (host, port, event, approved_by, change_ref, reason, fingerprint) VALUES (?,?, 'APPROVED', ?,?,?,?)")
                    .params(e.host(), e.port(), e.approvedBy(), e.change(), e.reason(), hosts.fingerprint()).update();
        }
        known.forEach((hostPort, approved) -> {
            if (!approved || hosts.entries().stream().anyMatch(e -> e.hostPort().equals(hostPort))) return;
            int colon = hostPort.lastIndexOf(':');
            db.sql("INSERT INTO approved_host_event (host, port, event, fingerprint) VALUES (?,?, 'REMOVED', ?)")
                    .params(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)), hosts.fingerprint()).update();
        });
    }

    public View view() {
        List<Event> history = db.sql("SELECT host, port, event, approved_by, change_ref, reason, recorded_at FROM approved_host_event ORDER BY id DESC LIMIT 100")
                .query((rs, n) -> new Event(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getObject(7, OffsetDateTime.class))).list();
        return new View(hosts.fingerprint(), hosts.entries(), history);
    }
}
