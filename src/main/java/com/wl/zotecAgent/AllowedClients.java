package com.wl.zotecAgent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allowlist for Select client(s). Matching is case-insensitive and supports:
 * <ul>
 *   <li>name contains either way (handles truncated UI labels with {@code ...})</li>
 *   <li>codes in parentheses, e.g. {@code (CVF1BJH)}, {@code (MEDS-102)}</li>
 * </ul>
 * <p>
 * Flow iterates {@link #orderedEntries()} in order and selects matching UI checkboxes;
 * allowlist entries with no Select client(s) match are skipped.
 */
public final class AllowedClients {

    private static final Pattern PARENS_CODE = Pattern.compile("\\(([^)]+)\\)");
    private static final Pattern LEADING_COUNT = Pattern.compile("^\\d+\\s*");

    private static final String[] RAW = {
    		"CAPE FEAR VALLEY MEDICAL CENTER",
    		"4Sarasota Memorial Hosp Veni... (GPNL-S103)",
    	    "Sarasota Memorial Hospital",
    	    "5Sarasota Memorial Hospital 23 (GPNL-S101)",
    		"63Madison County Memorial Rep...",
	    "54Carson Tahoe RMC Reports",
	    "4Missing Carson Tahoe RMC Re...",
	    "Carson Tahoe Regional",
	    "0Carson Tahoe Regional Med C... (CTEP-11)",
	    "Minden Emergent Care",
	    "Besty Johnson Hospital",
	    "0Betsy Johnson Hospital ED (CVF1BJH)",
	    "BLADEN COUNTY HOSPITAL",
	    "0BLADEN COUNTY HOSPITAL (BCH23-CFV1)",
	    "CAPE FEAR VALLEY HOKE HOSPITAL",
	    "0CAPE FEAR VALLEY HEALTHCARE... (CFV1CFHH)",
	    "CAPE FEAR VALLEY MEDICAL CENTER",
	    "104CAPE FEAR VALLEY MEDICAL CE... (CFVNC1)",
	    "Central Harnett Hospital",
	    "1Central Harnett Hospital ED (CVF1CHH)",
	    "1Missing Indiana Reports",
	    "FRANCISCAN ALLIANCE INDIANAPOLIS",
	    "0Franciscan Alliance Indiana... (EPI1-101)",
	    "FRANCISCAN ALLIANCE MOORESVILLE",
	    "0Franciscan Alliance Mooresv... (EPI1-103)",
	    "SMH ER at Lakewood",
	    "1Sarasota Mem ER Lakewood Ra... (GPNL-S104)",
	    "SMH North Port",
	    "0Sarasota Memorial Hosp N Po... (GPNL-S102)",
	    "SMH Venice",
	    "13Comm Hosp East Reports",
	    "Community Heart and Vascular",
	    "0Community Heart Vascular Hosp ER(COMHVHER)",
	    "Community Hospital East",
	    "0Community Hospital East ER (COMHOPEER)",
	    "Community Hospital North",
	    "0Community Hospital North ER (COMHOPNER)",
	    "Community Hospital South",
	    "0Community Hospital South ER (COMHOPSER)",
	    "Community Howard Regional Health",
	    "0Community Howard Regional H... (COMHRHER)",
	    "11Missing SSM Good Sam Reports",
	    "2Missing SSSM St Mary Reports",
	    "7SSM Good Sam Reports",
	    "63SSM St Mary Reports",
	    "ANDERSON HOSPITAL",
	    "66Anderson Hospital ER (MEDS-102)",
	    "CLAY COUNTY HOSPITAL",
	    "0Clay County Hospital ER (MEDS-114)",
	    "COMMUNITY HOSPITAL OF STAUNTON",
	    "21Community Hospital of Staun... (MEDS-105)",
	    "SPARTA COMMUNITY HOSPITAL",
	    "10Sparta Community Hospital ER (MEDS-117)",
	    "SSM GOOD SAMARITAN",
	    "0SSM Good Samaritan ER (MEDS-111)",
	    "SSM ST MARYS HOSPITAL",
	    "0SSM St Marys Hospital ER (MEDS-108)",
	    "21Beckley Reports",
	    "8Berrien Reports",
	    "16Brooks Reports",
	    "2Burke ED Reports",
	    "7Chatuge RNH reports",
	    "26Grady Reports",
	    "3Jefferson Reports",
	    "8Jenkins Reports",
	    "19Missing Bleckley ED Downtime",
	    "3Missing Burke Hosp Reports",
	    "3Missing Dodge ED Reports",
	    "6Missing Grady ED Reports",
	    "1Missing JeffDavis Reports",
	    "23Mitchell Reports",
	    "34Monroe ED Reports",
	    "15Screvin Reports",
	    "20Tattnall Reports",
	    "38Taylor ED Reports",
	    "1Union General Reports",
	    "8Washington CO Reports",
	    "57Wayne Memorial Reports",
	    "149Wills ED Reports",
	    "Appling Healthcare ED",
	    "0ED Appling Healthcare ER (SLMDAHER)",
	    "Bainbridge ED",
	    "137ED MEMORIAL HOSPITAL AND MA... (SLMDMHM23)",
	    "BLECKLEY ED",
	    "0ED BLECKLEY MEMORIAL HOSPIT... (SLMDBBMH23)",
	    "Brooks County Hospital ED",
	    "0ED BROOKS COUNTY HOSPITAL ED (SLMDBCHE23)",
	    "Burke ED",
	    "0ED Burke Medical Center ER (SLMDBMC23)",
	    "CLINCH ED",
	    "0ED CLINCH MEMORIAL HOSPITAL ER (SLMDCH23)",
	    "DODGE COUNTY ED",
	    "0ED DODGE COUNTY HOSPITAL ER (SLMDDC23)",
	    "Elbert ED",
	    "0ED ELBERT MEMORIAL HOSPITAL ED (ELBERTMH23)",
	    "EVANS ED",
	    "0ED EVANS MEMORIAL HOSPITAL ER (SLMDEE23)",
	    "0HSP EVANS HOSPITAL 23 (SLMDEH23)",
	    "Grady General Hospital ED",
	    "0ED GRADY GENERAL HOSPITAL ED (SLMDGGHE23)",
	    "0IRWIN COUNTY HOSPITAL ED (SLMDICHE23)",
	    "JEFF DAVIS ED",
	    "0ED Jeff Davis Hospital ER (SLMDJDH23)",
	    "Jefferson ED",
	    "0ED Jefferson Hospital ER (SLMDJH23)",
	    "Jenkins Co ED",
	    "0ED JENKINS COUNTY HOSPITAL ED (SLMDJCHE23)",
	    "LIBERTY REGIONAL ED",
	    "0ED LIBERTY EMERG MEDICAL SE... (SLMDLR23)",
	    "Madison County Memorial",
	    "0DoNotUseXMadison County Mem... (SLMDMCM23)",
	    "0SMD EMS Madison ER (SLMDSMD23)",
	    "Mitchell County ED",
	    "0ED MITCHELL COUNTY ED (SLMDMCE23)",
	    "Monroe County ED",
	    "0Monroe County Hospital ER (SLMDMONH23)",
	    "0NORTHRIDGE MEDICAL CENTER ER (SLMDNRMC23)",
	    "dff",
	    "0ED SCREVEN COUNTY HOSPITAL ED (SLMDSCHE23)",
	    "0Optim Medical Center Screven (SLMDOMCSER)",
	    "2ED CHATUGE REGIONAL HOSPITAL (SLMDCRH23)",
	    "SOUTHLAND LAKELAND EMERGENCY MEDICAL SERVICES, LLC",
	    "0ED SGMC LANIER CAMPUS LAKEL... (SLMDSLCL23)",
	    "SOUTHLAND NASHVILLE EMERGENCY SERVICES, LLC",
	    "0ED SGMC BERRIEN CAMPUS (SLMDSBC23)",
	    "Southland Union",
	    "17ED UNION GENERAL HOSPITAL (SLMDUG23)",
	    "Southland Wills",
	    "0ED WILLS MEMORIAL HOSPITAL (SLMDWMH23)",
	    "STEPHENS ED",
	    "0STEPHENS COUNTY HOSPITAL ER (SLMDSC23)",
	    "Tattnall ED",
	    "0ED TATTNALL ER (SLMDOPCT23)",
	    "Taylor Regional ED",
	    "0ED TAYLOR REGIONAL HOSPITAL ED (SLMDTRHE23)",
	    "Washington County Regional ED",
	    "0ED Washington Co Reg Medica... (SLMDEWCR23)",
	    "1Missing Uvalde Reports",
	    "6Val_Verde Missing Reports",
	    "CHOSA STONE OAK",
	    "0CHOSA Stone Oak ED (VEA1-01)",
	    "CHOSA WEST OVER HILLS",
	    "0CHOSA West Over Hills ED (VEA1-02)",
	    "CONNALLY MEMORIAL",
	    "1Connally Memorial ED (VEA1-10)",
	    "CSRH NEW BRAUNFELS",
	    "0Christus SR Hosp New Braunf... (VEA1-06)",
	    "CSRH WESTOVER HILLS",
	    "0Christus SR Hosp Westover H... (VEA1-08)",
	    "CSRH ALAMO HEIGHTS",
	    "0Christus SR Hosp Alamo Heig... (VEA1-03)",
	    "CSRH ALON",
	    "0Christus SR Hospital Alon ED (VEA1-04)",
	    "CSRH CREEKSIDE",
	    "0Christus SR Hosp Creekside ED (VEA1-05)",
	    "CSRH MEDICAL CENTER",
	    "0Christus SR Hosp Med Center ED (VEA1-09)",
	    "CSRH SAN MARCOS",
	    "0Christus SR Hosp San Marcos ED (VEA1-07)",
	    "DETAR HOSPITAL NORTH",
	    "0DeTar Hospital North ED (VEA1-13)",
	    "DETAR NAVARRO",
	    "0DeTar Navarro ED (VEA1-14)",
	    "DETAR NAVARRO CHEST PAIN UNIT",
	    "MATAGORDA REGIONAL MED CTR",
	    "0Matagorda Regional Medical ... (VEA1-17)",
	    "MEDINA REGIONAL HOSPITAL",
	    "0Medina Regional Hospital ED (VEA1-18)",
	    "UVALDE MEMORIAL",
	    "1Uvalde Memorial ED (VEA1-20)",
	    "0Uvalde Memorial HM21 (VEA1-19)",
	    "VAL VERDE REGIONAL MED CTR",
	    "0Val Verde Regional Medical ... (VEA1-22)",
	    "YOAKUM COMMUNITY HOSPITAL",
	    "0Yoakum Community Hospital ED (VEA1-28)",
    };

    private AllowedClients() {
    }

    /**
     * Allowlist entries in declared order (same as {@code RAW}).
     * Flow/FlowText walk this list and skip entries missing from Select client(s).
     */
    public static List<String> orderedEntries() {
	return Collections.unmodifiableList(Arrays.asList(RAW));
    }

    /**
     * Index of the first UI checkbox label that matches {@code allowlistEntry},
     * skipping indexes in {@code alreadyUsed}. Returns {@code -1} if none.
     */
    public static int findMatchingUiIndex(String allowlistEntry, List<String> uiLabels,
	    Set<Integer> alreadyUsed) {
	if (allowlistEntry == null || allowlistEntry.isBlank() || uiLabels == null) {
	    return -1;
	}
	for (int i = 0; i < uiLabels.size(); i++) {
	    if (alreadyUsed != null && alreadyUsed.contains(i)) {
		continue;
	    }
	    if (matchesEntry(allowlistEntry, uiLabels.get(i))) {
		return i;
	    }
	}
	return -1;
    }

    /** Case-insensitive allow check using display text and/or checkbox id. */
    public static boolean isAllowed(String uiText) {
	if (uiText == null || uiText.isBlank()) {
	    return false;
	}
	for (String entry : RAW) {
	    if (matchesEntry(entry, uiText)) {
		return true;
	    }
	}
	return false;
    }

    /**
     * Whether one allowlist entry matches a Select client(s) checkbox label
     * (name / truncation / paren codes).
     */
    public static boolean matchesEntry(String allowlistEntry, String uiText) {
	if (allowlistEntry == null || allowlistEntry.isBlank() || uiText == null || uiText.isBlank()) {
	    return false;
	}

	Set<String> allowedCodes = extractCodes(allowlistEntry);
	for (String code : extractCodes(uiText)) {
	    if (allowedCodes.contains(code)) {
		return true;
	    }
	}
	for (String token : uiText.split("[\\s_\\-]+")) {
	    String t = token.trim().toLowerCase(Locale.ROOT);
	    if (t.length() >= 4 && allowedCodes.contains(t)) {
		return true;
	    }
	}

	String allowed = normalizeName(allowlistEntry);
	String uiName = normalizeName(uiText);
	if (allowed.isBlank() || uiName.isBlank()) {
	    return false;
	}
	if (uiName.equals(allowed) || uiName.contains(allowed) || allowed.contains(uiName)) {
	    return true;
	}
	String allowedStem = stripEllipsis(allowed);
	String uiStem = stripEllipsis(uiName);
	return !allowedStem.isBlank() && !uiStem.isBlank()
		&& (uiStem.contains(allowedStem) || allowedStem.contains(uiStem));
    }

    static String normalizeName(String raw) {
	if (raw == null) {
	    return "";
	}
	String s = raw.toLowerCase(Locale.ROOT).trim();
	s = LEADING_COUNT.matcher(s).replaceFirst("");
	s = s.replace('\u00a0', ' ');
	s = s.replaceAll("\\s+", " ").trim();
	return s;
    }

    private static String stripEllipsis(String s) {
	return s.replace("...", "").trim();
    }

    static Set<String> extractCodes(String raw) {
	Set<String> codes = new HashSet<>();
	if (raw == null) {
	    return codes;
	}
	Matcher m = PARENS_CODE.matcher(raw);
	while (m.find()) {
	    String code = m.group(1).trim().toLowerCase(Locale.ROOT);
	    if (!code.isBlank()) {
		codes.add(code);
	    }
	}
	// also "ER(COMHVHER)" without space before paren
	return codes;
    }
}
