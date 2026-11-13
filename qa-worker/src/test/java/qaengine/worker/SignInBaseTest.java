package qaengine.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Sign-in paths are paths on the site, whether the application lives at its root or under a path. */
class SignInBaseTest {

    @Test
    void anApplicationUnderAPathSignsInAtTheSitesOwnPaths() {
        assertEquals("http://fixture.qa.internal:8090", WorkerMain.origin("http://fixture.qa.internal:8090/clinic/"),
                "so /clinic/login is opened once, not /clinic/clinic/login");
        assertEquals("https://host.example", WorkerMain.origin("https://host.example/app/"));
    }

    @Test
    void anApplicationAtItsRootIsUnchanged() {
        assertEquals("http://fixture.qa.internal:8090", WorkerMain.origin("http://fixture.qa.internal:8090/"));
        assertEquals("http://127.0.0.1:41234", WorkerMain.origin("http://127.0.0.1:41234/"));
    }
}
