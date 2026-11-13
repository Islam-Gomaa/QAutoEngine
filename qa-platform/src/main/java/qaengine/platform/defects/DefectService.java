package qaengine.platform.defects;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Groups findings into defects by fingerprint, so repeated runs do not create duplicates. */
@Component
public class DefectService {

    public record Observed(UUID organizationId, UUID projectId, UUID runId, String fingerprint, String type, String severity,
                           String title, String url, String detail) {}

    private final JdbcClient db;

    public DefectService(JdbcClient db) {
        this.db = db;
    }

    /** What should have happened, stated from the rule that fired. */
    private static String expected(String type) {
        return switch (type) {
            case "SERVER_ERROR" -> "The page loads without a server error.";
            case "BROKEN_PAGE" -> "The link leads to an existing page.";
            case "FAILED_REQUEST" -> "The request made by the page succeeds.";
            case "BROKEN_RESOURCE" -> "The resource loads.";
            case "SCRIPT_ERROR" -> "The page runs without uncaught script errors.";
            case "CONSOLE_ERROR" -> "The page logs no console errors.";
            case "NAVIGATION_FAILURE" -> "The page can be reached.";
            case "API_SERVER_ERROR" -> "The API handles the request without a 5xx response.";
            case "API_UNDECLARED_STATUS" -> "The response status is declared in the API contract.";
            case "API_CONTENT_TYPE" -> "The content type matches the API contract.";
            case "API_SCHEMA" -> "The response body matches the declared schema.";
            case "API_AUTH_BYPASS" -> "The API refuses requests without valid credentials.";
            case "API_AUTHORIZATION" -> "The API refuses credentials without the required role.";
            case "API_INPUT_VALIDATION" -> "The API rejects invalid input.";
            case "API_INFO_LEAK" -> "Responses do not expose internal details.";
            case "API_SLOW" -> "The API responds within the configured limit.";
            case "API_HEADERS" -> "The response carries the expected security headers.";
            default -> "";
        };
    }

    /** A fixed defect that appears again reopens. */
    public UUID upsert(Observed o) {
        return db.sql("""
                INSERT INTO defect (organization_id, id, project_id, fingerprint, type, severity, priority, title, url, detail, expected_result, first_seen_run, last_seen_run)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (organization_id, project_id, fingerprint) DO UPDATE SET
                    occurrences = defect.occurrences + CASE WHEN defect.last_seen_run = EXCLUDED.last_seen_run THEN 0 ELSE 1 END,
                    last_seen_run = EXCLUDED.last_seen_run,
                    status = CASE WHEN defect.status = 'FIXED' THEN 'OPEN' ELSE defect.status END,
                    closed_at = CASE WHEN defect.status = 'FIXED' THEN NULL ELSE defect.closed_at END
                RETURNING id
                """).params(o.organizationId(), UUID.randomUUID(), o.projectId(), o.fingerprint(), o.type(), o.severity(), o.severity(), o.title(), o.url(),
                o.detail(), expected(o.type()), o.runId(), o.runId()).query(UUID.class).single();
    }
}
