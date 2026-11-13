package qaengine.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/** An API with no spec and one broken endpoint, reachable only by following links. */
final class NoSpecFixture implements AutoCloseable {

    private final HttpServer server;

    NoSpecFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            String body = switch (path) {
                case "/root" -> "{\"links\":{\"a\":\"/child\",\"b\":\"/broken\",\"c\":\"http://blocked.invalid/x\"}}";
                case "/child" -> "{\"ok\":true}";
                case "/broken" -> "{not json";
                default -> null;
            };
            if (body == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length());
            x.getResponseBody().write(body.getBytes());
            x.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
