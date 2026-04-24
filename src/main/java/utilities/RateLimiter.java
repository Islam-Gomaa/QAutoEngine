package utilities;

import java.util.concurrent.Semaphore;

public class RateLimiter {

    private final Semaphore semaphore;

    public RateLimiter(int maxRequests) {
        this.semaphore = new Semaphore(maxRequests);
    }

    public void acquire() throws InterruptedException {
        semaphore.acquire();
    }

    public void release() {
        semaphore.release();
    }
}