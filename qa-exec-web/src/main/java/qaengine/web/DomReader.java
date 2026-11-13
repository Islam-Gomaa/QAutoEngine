package qaengine.web;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import qaengine.core.model.Control;
import qaengine.core.model.PageSnapshot;
import qaengine.core.policy.ActionRequest;

/** Reads a page in one script call per frame, including open shadow roots. */
final class DomReader {

    private static final String SCRIPT = """
        () => {
          const out = []; const shape = {};
          const esc = s => (window.CSS && CSS.escape) ? CSS.escape(s) : s;
          const visit = root => {
            for (const el of root.querySelectorAll('*')) {
              const tag = el.tagName.toLowerCase();
              const role = el.getAttribute('role') || '';
              shape[tag + (role ? ':' + role : '')] = (shape[tag + (role ? ':' + role : '')] || 0) + 1;
              if (el.shadowRoot) visit(el.shadowRoot);
              const type = (el.getAttribute('type') || '').toLowerCase();
              const interactive = (tag === 'a' && el.getAttribute('href') !== null) || tag === 'button' || tag === 'summary'
                || tag === 'input' || tag === 'select' || tag === 'textarea' || role === 'tab' || role === 'button'
                || role === 'link' || role === 'menuitem' || el.hasAttribute('aria-expanded');
              if (!interactive) continue;
              const attrs = {};
              for (const n of ['aria-label','title','id','class','name','data-method','data-confirm','onclick','download','target','aria-expanded','data-testid','type'])
                if (el.getAttribute(n) !== null) attrs[n] = el.getAttribute(n);
              const text = (el.innerText || el.value || '').trim().slice(0, 120);
              attrs.text = text;
              const locators = [];
              if (el.id) locators.push('#' + esc(el.id));
              if (el.getAttribute('data-testid')) locators.push('[data-testid="' + el.getAttribute('data-testid') + '"]');
              if (tag === 'a' && el.getAttribute('href')) locators.push('a[href="' + el.getAttribute('href').replaceAll('"','\\\\"') + '"]');
              if (el.getAttribute('name')) locators.push(tag + '[name="' + el.getAttribute('name') + '"]');
              if (text) locators.push(tag + ':has-text("' + text.replaceAll('"','\\\\"').slice(0,60) + '")');
              out.push({tag, role, type, href: tag === 'a' ? el.href : null, inForm: !!el.closest('form'), text, attrs, locators});
            }
          };
          visit(document);
          return {controls: out, shape, title: document.title};
        }
        """;

    private DomReader() {}

    @SuppressWarnings("unchecked")
    static PageSnapshot read(Page page) {
        List<Control> controls = new ArrayList<>();
        Map<String, Object> shape = new java.util.TreeMap<>();
        String title = "";
        for (Frame frame : page.frames()) {
            Map<String, Object> result;
            try {
                result = (Map<String, Object>) frame.evaluate(SCRIPT);
            } catch (RuntimeException e) {
                continue; // A cross-origin or detached frame is not readable; it is not an error.
            }
            if (frame == page.mainFrame()) title = String.valueOf(result.get("title"));
            shape.putAll((Map<String, Object>) result.get("shape"));
            for (Object raw : (List<Object>) result.get("controls")) controls.add(control((Map<String, Object>) raw));
        }
        return new PageSnapshot(page.url(), title, controls, fingerprint(page.url(), title, shape));
    }

    @SuppressWarnings("unchecked")
    private static Control control(Map<String, Object> c) {
        String tag = (String) c.get("tag");
        String role = (String) c.get("role");
        String type = (String) c.get("type");
        String href = (String) c.get("href");
        boolean inForm = Boolean.TRUE.equals(c.get("inForm"));
        Map<String, Object> rawAttrs = (Map<String, Object>) c.get("attrs");
        Map<String, String> attrs = new LinkedHashMap<>();
        rawAttrs.forEach((k, v) -> attrs.put(k, String.valueOf(v)));
        ActionRequest.Kind kind = classify(tag, role, type, href, inForm, attrs);
        return new Control(kind, href, (String) c.get("text"), attrs, (List<String>) c.get("locators"));
    }

    static ActionRequest.Kind classify(String tag, String role, String type, String href, boolean inForm, Map<String, String> attrs) {
        if (tag.equals("a") && href != null) return ActionRequest.Kind.NAVIGATE_LINK;
        if (role.equals("tab") || role.equals("menuitem") || tag.equals("summary") || attrs.containsKey("aria-expanded")) {
            return ActionRequest.Kind.DISCLOSURE;
        }
        if (tag.equals("input") && type.equals("file")) return ActionRequest.Kind.UPLOAD;
        if (tag.equals("button") || role.equals("button")) {
            return inForm && !type.equals("button") ? ActionRequest.Kind.FORM_SUBMIT : ActionRequest.Kind.BUTTON;
        }
        if (tag.equals("input") && (type.equals("submit") || type.equals("image"))) return ActionRequest.Kind.FORM_SUBMIT;
        if (tag.equals("input") || tag.equals("select") || tag.equals("textarea")) return ActionRequest.Kind.FORM_FILL;
        return ActionRequest.Kind.UNKNOWN;
    }

    /** Structure, not content: numbers in the URL and title collapse so a paginated calendar is one state. */
    static String fingerprint(String url, String title, Map<String, Object> shape) {
        String normalizedUrl = url.replaceAll("#.*$", "").replaceAll("\\d+", "{n}");
        String hash = url.contains("#") ? url.substring(url.indexOf('#')) : "";
        Map<String, String> bucketed = new HashMap<>();
        shape.forEach((k, v) -> bucketed.put(k, String.valueOf(bucket(((Number) v).intValue()))));
        String canonical = normalizedUrl + "|" + hash + "|" + title.replaceAll("\\d+", "{n}") + "|" + new java.util.TreeMap<>(bucketed);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int bucket(int n) {
        return n <= 3 ? n : 32 - Integer.numberOfLeadingZeros(n) + 2;
    }
}
