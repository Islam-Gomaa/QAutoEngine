package qaengine.platform.shared;

/** Also used for objects in other organizations, so their existence is not revealed. */
public final class NotFoundException extends RuntimeException {
    public NotFoundException(String what) {
        super(what + " not found");
    }
}
