package qaengine.web;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import qaengine.core.discovery.PageInventory;

/**
 * Reads a page's structure in one script call, without clicking, typing or submitting anything. Only the
 * main frame: a cross-origin frame belongs to someone else.
 */
final class DiscoveryReader {

    private static final String SCRIPT = """
        () => {
          const visible = el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
          const clean = s => (s || '').replace(/\\s+/g, ' ').trim().slice(0, 200);
          const named = el => clean(el.getAttribute('aria-label') || el.innerText || el.value || el.getAttribute('title') || '');
          const labelOf = f => {
            if (f.labels && f.labels.length) return clean(f.labels[0].innerText);
            if (f.getAttribute('aria-label')) return clean(f.getAttribute('aria-label'));
            const by = f.getAttribute('aria-labelledby');
            const t = by && document.getElementById(by);
            return t ? clean(t.innerText) : '';
          };
          const skip = ['hidden', 'submit', 'button', 'image', 'reset'];
          const headings = [...document.querySelectorAll('h1, h2, h3')].filter(visible).map(h => clean(h.innerText)).filter(Boolean);
          const navigation = [...document.querySelectorAll('nav, [role=navigation]')].map(n => ({
            name: clean(n.getAttribute('aria-label') || ''), links: [...n.querySelectorAll('a[href]')].map(named).filter(Boolean) }));
          const forms = [...document.querySelectorAll('form')].map(f => {
            const submit = f.querySelector('button[type=submit], input[type=submit], button:not([type])');
            return {
              name: clean(f.getAttribute('aria-label') || f.getAttribute('name') || f.id || ''), method: (f.getAttribute('method') || 'get').toLowerCase(),
              action: f.getAttribute('action') || '', submit: submit ? named(submit) : '',
              fields: [...f.querySelectorAll('input, select, textarea')].filter(i => !skip.includes((i.getAttribute('type') || '').toLowerCase())).map(i => {
                const label = labelOf(i);
                return { label, name: i.getAttribute('name') || '', type: i.tagName === 'INPUT' ? (i.getAttribute('type') || 'text').toLowerCase() : i.tagName.toLowerCase(),
                         required: i.required || i.getAttribute('aria-required') === 'true', labelled: !!label };
              }) };
          });
          const tables = [...document.querySelectorAll('table, [role=table], [role=grid]')].map(t => {
            const cap = t.querySelector('caption');
            return { caption: clean(cap ? cap.innerText : (t.getAttribute('aria-label') || '')),
                     headers: [...t.querySelectorAll('th, [role=columnheader]')].map(h => clean(h.innerText)),
                     rows: [...t.querySelectorAll('tr, [role=row]')].filter(r => r.querySelector('td, [role=cell], [role=gridcell]')).length };
          });
          const dialogs = [...document.querySelectorAll('dialog, [role=dialog], [role=alertdialog]')].map(d => {
            const h = d.querySelector('h1, h2, h3');
            return { name: clean(d.getAttribute('aria-label') || (h ? h.innerText : '')), open: d.tagName === 'DIALOG' ? d.open : visible(d) };
          });
          const interactive = 'a[href], button, input[type=submit], input[type=button], input[type=image], [role=button], [role=link], [role=tab], [role=menuitem], summary, [aria-expanded]';
          const actions = [...document.querySelectorAll(interactive)].filter(el => visible(el) || el.closest('dialog, [role=dialog]')).map(el => {
            const tag = el.tagName.toLowerCase(), role = el.getAttribute('role') || '', type = (el.getAttribute('type') || '').toLowerCase();
            const kind = tag === 'a' ? 'LINK'
              : (role === 'tab' || role === 'menuitem' || tag === 'summary' || el.hasAttribute('aria-expanded')) ? 'DISCLOSURE'
              : (el.closest('form') && (type === 'submit' || (tag === 'button' && !type) || type === 'image')) ? 'SUBMIT' : 'BUTTON';
            const name = named(el);
            const attrs = {};
            for (const n of ['aria-label', 'title', 'id', 'name', 'data-testid', 'data-method', 'data-confirm', 'download', 'onclick'])
              if (el.getAttribute(n) !== null) attrs[n] = el.getAttribute(n).slice(0, 200);
            const testid = el.getAttribute('data-testid');
            const ariaRole = role || (tag === 'a' ? 'link' : 'button');
            const locator = testid ? { strategy: 'TEST_ID', value: testid }
              : name ? { strategy: 'ROLE', value: ariaRole, name }
              : el.id ? { strategy: 'CSS', value: '#' + CSS.escape(el.id) } : { strategy: 'CSS', value: tag };
            return { kind, role: ariaRole, name, href: tag === 'a' ? el.href : null, locator, attrs };
          });
          return { title: document.title, headings, navigation, forms, tables, dialogs, actions };
        }
        """;

    private DiscoveryReader() {}

    @SuppressWarnings("unchecked")
    static PageInventory read(Page page) {
        Map<String, Object> r;
        try {
            r = (Map<String, Object>) page.evaluate(SCRIPT);
        } catch (PlaywrightException e) {
            return null;
        }
        List<PageInventory.Navigation> navigation = new ArrayList<>();
        for (Map<String, Object> n : list(r.get("navigation"))) navigation.add(new PageInventory.Navigation(str(n.get("name")), strings(n.get("links"))));
        List<PageInventory.Form> forms = new ArrayList<>();
        for (Map<String, Object> f : list(r.get("forms"))) {
            List<PageInventory.Field> fields = new ArrayList<>();
            for (Map<String, Object> x : list(f.get("fields"))) {
                fields.add(new PageInventory.Field(str(x.get("label")), str(x.get("name")), str(x.get("type")), Boolean.TRUE.equals(x.get("required")), Boolean.TRUE.equals(x.get("labelled"))));
            }
            forms.add(new PageInventory.Form(str(f.get("name")), str(f.get("method")), str(f.get("action")), fields, str(f.get("submit"))));
        }
        List<PageInventory.Table> tables = new ArrayList<>();
        for (Map<String, Object> t : list(r.get("tables"))) tables.add(new PageInventory.Table(str(t.get("caption")), strings(t.get("headers")), ((Number) t.get("rows")).intValue()));
        List<PageInventory.Dialog> dialogs = new ArrayList<>();
        for (Map<String, Object> d : list(r.get("dialogs"))) dialogs.add(new PageInventory.Dialog(str(d.get("name")), Boolean.TRUE.equals(d.get("open"))));
        List<PageInventory.Action> actions = new ArrayList<>();
        for (Map<String, Object> a : list(r.get("actions"))) {
            actions.add(new PageInventory.Action(str(a.get("kind")), str(a.get("role")), str(a.get("name")), (String) a.get("href"), stringMap(a.get("locator")), stringMap(a.get("attrs"))));
        }
        return new PageInventory(page.url(), str(r.get("title")), strings(r.get("headings")), navigation, forms, tables, dialogs, actions);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static Map<String, String> stringMap(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v != null) out.put(String.valueOf(k), String.valueOf(v)); });
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
