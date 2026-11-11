package qaengine.platform.identity;

import java.util.Base64;
import java.util.UUID;

/** TOTP seeds are secrets, so they are stored the same way as target credentials. */
final class TotpSeal {

    private TotpSeal() {}

    private static String owner(UUID user) {
        return "totp/" + user;
    }

    static String seal(SecretStore box, UUID user, String seedBase64) {
        var sealed = box.seal(seedBase64, owner(user));
        var b64 = Base64.getEncoder();
        return b64.encodeToString(sealed.wrappedKey()) + "." + b64.encodeToString(sealed.ciphertext());
    }

    static byte[] open(SecretStore box, UUID user, String stored) {
        String[] parts = stored.split("\\.");
        var b64 = Base64.getDecoder();
        return b64.decode(box.open(new SecretStore.Sealed(b64.decode(parts[0]), b64.decode(parts[1])), owner(user)));
    }
}
