package qaengine.worker;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import qaengine.contracts.WorkerContracts.FindingReport;
import qaengine.contracts.WorkerContracts.HeartbeatResponse;
import qaengine.contracts.WorkerContracts.JobAssignment;
import qaengine.contracts.WorkerContracts.LoginSecret;
import qaengine.contracts.WorkerContracts.RunCompletion;
import qaengine.contracts.WorkerContracts.StepReport;

/** The worker's only link to the platform. A 409 means this attempt lost the job and must stop. */
final class PlatformClient {

    /** This attempt no longer owns the job. */
    static final class LostLease extends RuntimeException { }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long RETRY_WINDOW_MILLIS = 40_000;
    private static final long RETRY_DELAY_MILLIS = 2_000;

    private final HttpClient http;
    private final Gson gson = new Gson();
    private final String base;
    private final String workerKey;
    private volatile String jobToken;
    private UUID jobId;

    PlatformClient(String base, String workerKey, javax.net.ssl.SSLContext tls) {
        if (!base.startsWith("https://")) throw new SecurityException("The platform must be reached over TLS");
        this.base = base;
        this.workerKey = workerKey;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).version(HttpClient.Version.HTTP_1_1).sslContext(tls).build();
    }

    Optional<JobAssignment> claim(String workerId) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/jobs/claim")).timeout(TIMEOUT)
                .header("X-Worker-Key", workerKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(Map.of("workerId", workerId)))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) return Optional.empty();
        if (response.statusCode() != 200) throw new IOException("claim failed: " + response.statusCode());
        JobAssignment job = gson.fromJson(response.body(), JobAssignment.class);
        jobId = job.jobId();
        jobToken = job.jobToken();
        return Optional.of(job);
    }

    /**
     * Rides out a platform restart. Every write is idempotent, so a retry is safe. Stays inside the
     * worker's self-stop limit: past this, the job is left for lease recovery.
     */
    private HttpResponse<String> post(String path, String contentType, HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        long giveUp = System.currentTimeMillis() + RETRY_WINDOW_MILLIS;
        while (true) {
            try {
                return send(path, contentType, body);
            } catch (LostLease e) {
                throw e;
            } catch (IOException e) {
                if (System.currentTimeMillis() >= giveUp) throw e;
                Thread.sleep(RETRY_DELAY_MILLIS);
            }
        }
    }

    private HttpResponse<String> send(String path, String contentType, HttpRequest.BodyPublisher body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/jobs/" + jobId + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + jobToken).header("Content-Type", contentType).POST(body).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409 || response.statusCode() == 401) throw new LostLease();
        if (response.statusCode() >= 400) throw new IOException("platform returned " + response.statusCode() + " for " + path);
        return response;
    }

    private void postJson(String path, Object body) throws IOException, InterruptedException {
        post(path, "application/json", HttpRequest.BodyPublishers.ofString(gson.toJson(body)));
    }

    LoginSecret login() throws IOException, InterruptedException {
        return gson.fromJson(post("/login", "application/json", HttpRequest.BodyPublishers.noBody()).body(), LoginSecret.class);
    }

    void start() throws IOException, InterruptedException {
        post("/start", "application/json", HttpRequest.BodyPublishers.noBody());
    }

    /** Returns whether cancellation was requested, and renews the token. */
    boolean heartbeat() throws IOException, InterruptedException {
        var response = send("/heartbeat", "application/json", HttpRequest.BodyPublishers.noBody());
        HeartbeatResponse beat = gson.fromJson(response.body(), HeartbeatResponse.class);
        jobToken = beat.jobToken();
        return beat.cancelRequested();
    }

    void steps(List<StepReport> steps) throws IOException, InterruptedException {
        postJson("/steps", steps);
    }

    void finding(FindingReport finding) throws IOException, InterruptedException {
        postJson("/findings", finding);
    }

    void complete(RunCompletion completion) throws IOException, InterruptedException {
        postJson("/complete", completion);
    }

    void evidence(int step, String type, String sha256, byte[] content) throws IOException, InterruptedException {
        String query = "?step=" + step + "&type=" + type + "&sha256=" + URLEncoder.encode(sha256, StandardCharsets.UTF_8);
        post("/evidence" + query, "application/octet-stream", HttpRequest.BodyPublishers.ofByteArray(content));
    }
}
