package qaengine.fixtures;

import java.net.InetSocketAddress;

/** Runs the fixture as a container on the isolated test network, for end-to-end and attack tests. */
public final class FixtureMain {

    private FixtureMain() {}

    public static void main(String[] args) throws Exception {
        FixtureServer server = FixtureServer.start(new InetSocketAddress("0.0.0.0", 8090));
        server.exposeLog = true;
        System.out.println("fixture listening on 8090");
        Thread.currentThread().join();
    }
}
