package com.wl.zotecAgent;

import com.microsoft.playwright.Page;

/**
 * Reads a visible document / encounter number from the desktop review UI or Zotec workfile.
 */
public final class DocumentNumberReader {

    private DocumentNumberReader() {
    }

    /**
     * Best-effort document number from the current page (desktop review label, or Zotec Encounter #).
     */
    public static String read(Page page) {
	if (page == null) {
	    return "unknown";
	}
	try {
	    Object raw = page.evaluate("() => {"
		    + "  const pick = (s) => (s || '').replace(/\\s+/g, ' ').trim();"
		    + "  const labeled = () => {"
		    + "    const nodes = Array.from(document.querySelectorAll("
		    + "      'label, dt, th, span, div, p, h1, h2, h3, strong, td'));"
		    + "    for (const n of nodes) {"
		    + "      const t = pick(n.childNodes && n.childNodes.length === 1 ? n.textContent : "
		    + "        Array.from(n.childNodes).filter(c => c.nodeType === 3).map(c => c.textContent).join(' '));"
		    + "      if (!t || t.length > 120) continue;"
		    + "      const m = t.match(/^document\\s*(?:number|#|no\\.?|id)?\\s*[:#]?\\s*(.*)$/i);"
		    + "      if (!m) continue;"
		    + "      let v = pick(m[1]);"
		    + "      if (!v || /^(number|#|no\\.?|id)?$/i.test(v)) {"
		    + "        const next = n.nextElementSibling;"
		    + "        if (next) v = pick(next.value || next.textContent);"
		    + "        if (!v) {"
		    + "          const inp = n.parentElement && n.parentElement.querySelector('input, textarea');"
		    + "          if (inp) v = pick(inp.value || inp.textContent);"
		    + "        }"
		    + "      }"
		    + "      if (v && v.length < 80) return v;"
		    + "    }"
		    + "    const body = document.body ? document.body.innerText : '';"
		    + "    const bm = body.match(/Document\\s*(?:number|#|No\\.?|ID)\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9._-]*)/i);"
		    + "    return bm ? bm[1] : null;"
		    + "  };"
		    + "  const encounter = () => {"
		    + "    const chosen = document.querySelector('#s2id_patientencounternumber .select2-chosen');"
		    + "    if (chosen) { const t = pick(chosen.textContent); if (t) return t; }"
		    + "    const hidden = document.querySelector('#patientencounternumber, input[name=\"patientEncounterNumber\"]');"
		    + "    if (hidden && hidden.value) return pick(hidden.value);"
		    + "    return null;"
		    + "  };"
		    + "  return labeled() || encounter() || '';"
		    + "}");
	    if (raw instanceof String s && !s.isBlank()) {
		return s.trim();
	    }
	} catch (Exception ignored) {
	}
	return "unknown";
    }
}
