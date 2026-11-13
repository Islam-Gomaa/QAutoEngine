package qaengine.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;
import org.springframework.scheduling.annotation.EnableScheduling;

@Modulith(sharedModules = "shared")
@SpringBootApplication
@EnableScheduling
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
