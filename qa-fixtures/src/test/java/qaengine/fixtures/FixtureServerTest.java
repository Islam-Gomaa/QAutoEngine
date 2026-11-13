package qaengine.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class FixtureServerTest {

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private int status(FixtureServer f, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(f.url(path))).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void bindsToLoopbackOnly() throws Exception {
        try (var f = FixtureServer.start()) {
            assertTrue(f.baseUrl().startsWith("http://127.0.0.1:"));
        }
    }

    @Test
    void plantedStatusesAreServed() throws Exception {
        try (var f = FixtureServer.start()) {
            assertEquals(200, status(f, "/"));
            assertEquals(404, status(f, "/missing-page"));
            assertEquals(500, status(f, "/crash"));
            assertEquals(302, status(f, "/redirect-external"));
        }
    }

    @Test
    void recordsEveryRequest() throws Exception {
        try (var f = FixtureServer.start()) {
            assertFalse(f.reached("/m/x"));
            status(f, "/m/x?a=1");
            assertTrue(f.reached("/m/x"));
            assertEquals("a=1", f.requests().getLast().query());
        }
    }

    @Test
    void cleanPagesAreServed() throws Exception {
        try (var f = FixtureServer.start()) {
            for (String p : Scorecard.CLEAN_PAGES) assertEquals(200, status(f, p), p);
        }
    }

    @Test
    void scorecardIsMachineReadable() {
        assertTrue(Scorecard.toJson().contains("\"id\":\"D-404\""));
        assertEquals(7, Scorecard.of(Scorecard.Kind.DEFECT).size());
        assertEquals(1, Scorecard.of(Scorecard.Kind.RESIDUAL_RISK).size());
    }

    @Test
    void loginRequiresTheFixtureCredential() throws Exception {
        try (var f = FixtureServer.start()) {
            var bad = HttpRequest.newBuilder(URI.create(f.url("/session"))).POST(HttpRequest.BodyPublishers.ofString("username=x&password=y")).build();
            assertEquals(401, http.send(bad, HttpResponse.BodyHandlers.discarding()).statusCode());
            var good = HttpRequest.newBuilder(URI.create(f.url("/session")))
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + FixtureServer.TEST_USER + "&password=" + FixtureServer.TEST_PASSWORD)).build();
            assertEquals(302, http.send(good, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }

    @Test
    void apiCleanAndPlantedBehaviour() throws Exception {
        try (var f = FixtureServer.start()) {
            assertEquals(200, status(f, "/api/v1/items?limit=5"));
            assertEquals(400, status(f, "/api/v1/items?limit=0"));
            assertEquals(500, status(f, "/api/v1/search?q=" + "a".repeat(65)));
            assertEquals(401, status(f, "/api/v1/me"));
            assertEquals(200, status(f, "/api/v1/admin/users"));
            assertEquals(202, status(f, "/api/v1/reports/3"));
            assertEquals(200, status(f, "/openapi.json"));
            assertEquals(10, Scorecard.of(Scorecard.Kind.API_DEFECT).size());
        }
    }
}
