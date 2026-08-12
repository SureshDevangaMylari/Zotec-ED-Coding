package com.wl.zotecAgent.selection;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Updates the ED EM Supplemental form HTML with selections from the LLM. Sets
 * radio values and checkbox states based on the selection map.
 */
public class ED_EMFormHtmlUpdater {

    private static final Logger log = LoggerFactory.getLogger(ED_EMFormHtmlUpdater.class);

    /**
     * Updates the HTML file at the given path with the selections (overwrites in
     * place).
     *
     * @param htmlPath   path to autoCoderForm.html
     * @param selections map of field name -> value (String "0"/"1"/"2"/"3" for
     *                   radios, Boolean for checkboxes)
     * @return true if update succeeded and file was written
     */
    public static boolean update(String htmlPath, Map<String, Object> selections) {
	return updateToFile(htmlPath, htmlPath, selections);
    }

    /**
     * Reads template from source path, applies selections, writes to target path.
     * Keeps the template unchanged.
     *
     * @param sourcePath path to template autoCoderForm.html
     * @param targetPath path where filled form will be saved (e.g.
     *                   autoCoderForm_Maes_Johnny_Jose.html)
     * @param selections map of field name -> value
     * @return true if saved successfully
     */
    public static boolean updateToFile(String sourcePath, String targetPath, Map<String, Object> selections) {
	Path source = resolvePath(sourcePath);
	if (!Files.exists(source)) {
	    log.warn("HTML template not found: {}", sourcePath);
	    return false;
	}

	Path target = resolvePath(targetPath);
	try {
	    String html = Files.readString(source);
	    String updated = applySelections(html, selections);
	    Files.createDirectories(target.getParent());
	    Files.writeString(target, updated);
	    log.info("Saved form HTML: {} ({} selections applied)", target, countApplied(selections));
	    return true;
	} catch (IOException e) {
	    log.warn("Failed to save HTML: {}", e.getMessage());
	    return false;
	}
    }

    private static Path resolvePath(String path) {
	Path p = Path.of(path);
	if (!Files.exists(p)) {
	    p = Path.of(System.getProperty("user.dir"), path);
	}
	return p;
    }

    /**
     * Applies selections to the HTML content and returns updated HTML. Also inserts
     * a summary section (h2/p tags) with the selection values at the top.
     */
    public static String applySelections(String html, Map<String, Object> selections) {
	Document doc = Jsoup.parse(html);

	for (Map.Entry<String, Object> e : selections.entrySet()) {
	    String name = e.getKey();
	    Object value = e.getValue();
	    if (value == null)
		continue;

	    if (value instanceof Boolean bool) {
		applyCheckbox(doc, name, bool);
	    } else {
		String strVal = String.valueOf(value);
		if (strVal.matches("[0-3]")) {
		    applyRadio(doc, name, strVal);
		}
	    }
	}

	insertSelectionSummary(doc, selections);
	return doc.html();
    }

    /**
     * Inserts a summary div with h1/h2 section headings and h3/p tags for each
     * selection at top of body.
     */
    private static void insertSelectionSummary(Document doc, Map<String, Object> selections) {
	if (selections.isEmpty())
	    return;

	Map<String, List<Map.Entry<String, Object>>> bySection = new LinkedHashMap<>();
	bySection.put("PROBLEM(S) ADDRESSED", selections.entrySet().stream()
		.filter(e -> e.getKey().startsWith("problem.")).collect(Collectors.toList()));
	bySection.put("DATA", selections.entrySet().stream().filter(e -> e.getKey().startsWith("data."))
		.collect(Collectors.toList()));
	bySection.put("RISK", selections.entrySet().stream().filter(e -> e.getKey().startsWith("risk."))
		.collect(Collectors.toList()));

	Element body = doc.body();
	if (body == null)
	    return;

	Element summaryDiv = new Element("div").attr("class", "selection-summary").attr("style",
		"padding:15px;margin:10px 0;border:1px solid #ccc;background:#f9f9f9;border-radius:4px;");
	summaryDiv.appendElement("h1").attr("style", "margin-top:0;font-size:18px;")
		.text("ED EM Supplemental – Selection Summary");

	for (Map.Entry<String, List<Map.Entry<String, Object>>> section : bySection.entrySet()) {
	    if (section.getValue().isEmpty())
		continue;
	    summaryDiv.appendElement("h2").attr("style", "font-size:16px;margin:12px 0 6px;").text(section.getKey());
	    for (Map.Entry<String, Object> e : section.getValue()) {
		String label = FormFieldConstants.getLabel(e.getKey());
		String id = FormFieldConstants.getId(e.getKey());
		String valueStr = formatValueForDisplay(e.getValue());
		summaryDiv.appendElement("h3").attr("style", "font-size:14px;margin:8px 0 2px;font-weight:600;")
			.text(label);
		summaryDiv.appendElement("p").attr("id", id).attr("style", "margin:0 0 8px 20px;font-size:14px;")
			.text(valueStr);
	    }
	}

	body.prependChild(summaryDiv);
    }

    private static String formatValueForDisplay(Object value) {
	if (value == null)
	    return "";
	if (value instanceof Boolean b)
	    return b ? "selected" : "unselected";
	return String.valueOf(value);
    }

    /**
     * Writes a standalone HTML file with selection summary using h1, h2, h3, p
     * tags. Each field has label in h3 and value in p with id attribute.
     */
    public static boolean writeSelectionSummaryHtml(String targetPath, Map<String, Object> selections,
	    String patientName) {
	if (selections.isEmpty())
	    return false;
	Path target = resolvePath(targetPath);
	try {
	    StringBuilder html = new StringBuilder();
	    html.append("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"><title>Selection Summary");
	    if (patientName != null && !patientName.isBlank())
		html.append(" - ").append(escapeHtml(patientName));
	    html.append("</title></head><body>\n");
	    html.append("<h1>ED EM Supplemental – Selection Summary</h1>\n");
	    if (patientName != null && !patientName.isBlank()) {
		html.append("<p><strong>Patient: ").append(escapeHtml(patientName)).append("</strong></p>\n");
	    }
	    appendSection(html, "PROBLEM(S) ADDRESSED", selections, "problem.");
	    appendSection(html, "DATA", selections, "data.");
	    appendSection(html, "RISK", selections, "risk.");
	    html.append("</body></html>");
	    Files.createDirectories(target.getParent());
	    Files.writeString(target, html.toString());
	    log.info("Saved selection summary HTML: {}", target);
	    return true;
	} catch (IOException e) {
	    log.warn("Failed to write selection summary HTML: {}", e.getMessage());
	    return false;
	}
    }

    private static void appendSection(StringBuilder html, String sectionTitle, Map<String, Object> selections,
	    String prefix) {
	html.append("<h2>").append(escapeHtml(sectionTitle)).append("</h2>\n");
	selections.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).forEach(e -> {
	    String label = FormFieldConstants.getLabel(e.getKey());
	    String id = FormFieldConstants.getId(e.getKey());
	    String valueStr = formatValueForDisplay(e.getValue());
	    html.append("<h3>").append(escapeHtml(label)).append("</h3>\n");
	    html.append("<p id=\"").append(escapeHtml(id)).append("\">").append(escapeHtml(valueStr)).append("</p>\n");
	});
    }

    private static String escapeHtml(String s) {
	if (s == null)
	    return "";
	return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void applyRadio(Document doc, String name, String targetValue) {
	Elements radios = doc.select("input[type=radio][name=" + escapeName(name) + "]");
	if (radios.isEmpty()) {
	    log.debug("Radio group not found: {}", name);
	    return;
	}

	for (Element radio : radios) {
	    String val = radio.attr("value");
	    boolean shouldCheck = val.equals(targetValue);
	    if (shouldCheck) {
		radio.attr("checked", "checked");
	    } else {
		radio.removeAttr("checked");
	    }
	    // Update parent label class (selected: "btn btn-default active btn-success")
	    Element label = radio.parent();
	    if (label != null && "label".equals(label.tagName())) {
		label.attr("class", shouldCheck ? "btn btn-default active btn-success" : "btn btn-default ");
	    }
	}
    }

    private static void applyCheckbox(Document doc, String name, boolean checked) {
	Element cb = doc.selectFirst("input[type=checkbox][name=" + escapeName(name) + "]");
	if (cb == null) {
	    log.debug("Checkbox not found: {}", name);
	    return;
	}
	if (checked) {
	    cb.attr("checked", "checked");
	} else {
	    cb.removeAttr("checked");
	}
    }

    /**
     * Escapes name for Jsoup attribute selector (handles dots in e.g.
     * problem.self_limited_minor_problems)
     */
    private static String escapeName(String name) {
	return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int countApplied(Map<String, Object> selections) {
	int n = 0;
	for (Object v : selections.values()) {
	    if (v != null)
		n++;
	}
	return n;
    }
}
