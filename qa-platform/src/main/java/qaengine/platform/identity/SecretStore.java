package qaengine.platform.identity;

/**
 * Where secret values are sealed and opened. Callers keep the sealed bytes with the record they belong
 * to and never see a key. {@link SecretBox} is the implementation today (envelope encryption with a
 * master key from a Docker secret, ADR-017); an external vault would implement this same contract,
 * returning its own opaque reference in place of a wrapped key.
 *
 * <p>The owner binds a sealed value to the record it was sealed for, so ciphertext copied from one
 * record to another will not open.
 */
public interface SecretStore {

    /** Opaque to callers. Both parts are stored together and handed back unchanged. */
    record Sealed(byte[] wrappedKey, byte[] ciphertext) {}

    Sealed seal(String value, String owner);

    /** Throws {@link IllegalStateException} with a fixed message when the value cannot be opened. */
    String open(Sealed sealed, String owner);
}
