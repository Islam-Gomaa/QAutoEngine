package qaengine.platform.evidence;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class EvidenceJobs {

    private static final Logger log = LoggerFactory.getLogger(EvidenceJobs.class);

    private final EvidenceService evidence;

    EvidenceJobs(EvidenceService evidence) {
        this.evidence = evidence;
    }

    @Scheduled(cron = "0 15 3 * * *")
    void retention() {
        log.info("evidence retention removed {} items", evidence.deleteExpired());
    }

    @Scheduled(cron = "0 45 3 * * SUN")
    void integrity() {
        List<UUID> bad = evidence.verifySample(500);
        if (!bad.isEmpty()) log.error("evidence integrity mismatch for {} items: {}", bad.size(), bad);
    }
}
