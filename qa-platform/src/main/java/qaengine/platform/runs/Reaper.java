package qaengine.platform.runs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Waits one lease after startup, so running jobs can reconnect after a platform restart. */
@Component
class Reaper {

    private final JobQueue queue;

    Reaper(JobQueue queue) {
        this.queue = queue;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 15_000)
    void tick() {
        queue.reap();
    }
}
