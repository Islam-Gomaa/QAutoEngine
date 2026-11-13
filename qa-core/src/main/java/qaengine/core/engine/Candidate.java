package qaengine.core.engine;

import qaengine.core.model.Control;

/** A place to go. The control is null for the start URL and for redirect targets. */
public record Candidate(String url, int depth, Control via, int parentStep) {}
