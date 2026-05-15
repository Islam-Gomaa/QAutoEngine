package ai.learning;

import java.util.Objects;

public class State {

    // ===================================================
    // 🧠 CORE FEATURES
    // ===================================================
    public final String url;
    public final int domSize;
    public final String goal;

    // ===================================================
    // 🔥 DERIVED FEATURES
    // ===================================================
    public final boolean hasForm;
    public final boolean hasLinks;

    // ===================================================
    // 💣 OPTIMIZED FIELDS (cached)
    // ===================================================
    private final int domBucket;
    private String cachedSignature;

    // ===================================================
    public State(String url,
                 int domSize,
                 String goal,
                 boolean hasForm,
                 boolean hasLinks) {

        this.url = normalize(url);
        this.domSize = domSize;
        this.goal = goal != null ? goal : "UNKNOWN";
        this.hasForm = hasForm;
        this.hasLinks = hasLinks;

        this.domBucket = bucket(domSize);
    }

    // ===================================================
    // 🔥 NORMALIZATION
    // ===================================================
    private static String normalize(String url) {

        if (url == null) return "NULL";

        int i = url.indexOf("?");
        return (i > 0) ? url.substring(0, i) : url;
    }

    // ===================================================
    // 🧠 SIGNATURE (cached)
    // ===================================================
    public String signature() {

        if (cachedSignature != null) return cachedSignature;

        cachedSignature =
                url + "|" +
                        domBucket + "|" +
                        goal + "|" +
                        (hasForm ? "F" : "0") +
                        (hasLinks ? "L" : "0");

        return cachedSignature;
    }

    // ===================================================
    private int bucket(int size) {
        return size / 50;
    }

    // ===================================================
    // 🔁 EQUALITY (stable + fast)
    // ===================================================
    @Override
    public boolean equals(Object o) {

        if (this == o) return true;
        if (!(o instanceof State)) return false;

        State s = (State) o;

        return domBucket == s.domBucket
                && hasForm == s.hasForm
                && hasLinks == s.hasLinks
                && Objects.equals(url, s.url)
                && Objects.equals(goal, s.goal);
    }

    // ===================================================
    @Override
    public int hashCode() {

        return Objects.hash(
                url,
                domBucket,
                goal,
                hasForm,
                hasLinks
        );
    }

    // ===================================================
    // 🧠 DEBUG VIEW
    // ===================================================
    @Override
    public String toString() {

        return "State{" +
                "url='" + url + '\'' +
                ", dom=" + domSize +
                ", bucket=" + domBucket +
                ", goal='" + goal + '\'' +
                ", form=" + hasForm +
                ", links=" + hasLinks +
                '}';
    }
}