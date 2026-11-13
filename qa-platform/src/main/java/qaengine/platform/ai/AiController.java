package qaengine.platform.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Whether AI is active, and if not, why, for anyone signed in. Never the key, never usage of other organizations. */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiGateway gateway;

    public AiController(AiGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/status")
    public AiGateway.Status status() {
        return gateway.status();
    }
}
