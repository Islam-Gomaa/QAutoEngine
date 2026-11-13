package qaengine.core.verdict;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * One observed problem. The fingerprint is stable across runs, so repeated observations group
 * into one defect.
 */
public record Finding(String fingerprint, Type type, Severity severity, String url, String title, String detail) {

    public enum Type {
        SERVER_ERROR, BROKEN_PAGE, FAILED_REQUEST, BROKEN_RESOURCE, SCRIPT_ERROR, CONSOLE_ERROR, NAVIGATION_FAILURE,
        API_SERVER_ERROR, API_UNDECLARED_STATUS, API_CONTENT_TYPE, API_SCHEMA, API_AUTH_BYPASS, API_AUTHORIZATION,
        API_INPUT_VALIDATION, API_INFO_LEAK, API_SLOW, API_HEADERS
    }

    public static Finding of(Type type, Severity severity, String url, String title, String detail) {
        return new Finding(fingerprint(type, url, detail), type, severity, url, title, detail);
    }

    static String fingerprint(Type type, String url, String detail) {
        String stablePath = url == null ? "" : url.replaceAll("^[a-z]+://[^/]+", "").replaceAll("\\?.*$", "").replaceAll("\\d+", "{n}");
        String stableDetail = detail == null ? "" : detail.replaceAll("\\d+", "{n}");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((type + "|" + stablePath + "|" + stableDetail).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
