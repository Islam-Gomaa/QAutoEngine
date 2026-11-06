package qaengine.platform.identity;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 time-based codes, accepting one step either side for clock drift. */
public final class Totp {

    private Totp() {}

    public static String code(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] key, String code, Instant now) {
        if (code == null) return false;
        long counter = now.getEpochSecond() / 30;
        boolean ok = false;
        for (long c = counter - 1; c <= counter + 1; c++) {
            ok |= MessageDigest.isEqual(code(key, c).getBytes(), code.getBytes());
        }
        return ok;
    }
}
