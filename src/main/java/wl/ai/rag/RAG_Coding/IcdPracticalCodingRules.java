package wl.ai.rag.RAG_Coding;

/**
 * Practical ICD-10-CM coding rules used with {@link RagAlphaService} lookup.
 * Apply together with retrieved excerpts from {@code resources/ICD-Guidlines.txt}
 * and navigation hints for {@code resources/icdalpha_2026.json} (alphabetic index).
 */
public final class IcdPracticalCodingRules {

    private IcdPracticalCodingRules() {}

    /**
     * Condensed rules for LLM context (must follow when selecting codes from candidates).
     */
    public static final String TEXT = """
            ICD CODING RULES (PRACTICAL SET — apply when searching / selecting codes)

            1 CONVENTIONS
            1.1 Includes — confirms what is part of the code; validate only.
            1.2 Excludes — Excludes1: mutually exclusive, never assign together. Excludes2: in standard ICD-10-CM both may be coded when both conditions exist; for THIS RAG workflow ignore Excludes2 as a blocker (do not drop a code solely because of an Excludes2 note).
            1.3 NEC vs NOS — NEC when no exact code; NOS when documentation insufficient.
            1.4 "With" — implies linkage; treat as associated unless explicitly separated (important for automation).
            1.5 "And" — may mean and/or by context.

            2 CODE SELECTION
            2.1 Most specific — deepest level available.
            2.2 Combination codes — prefer one code when it represents multiple conditions.
            2.3 Default — use default mapped code when no detail.
            2.4 Do not code symptoms if a definitive diagnosis exists (e.g. chest pain + MI → code MI, not symptom alone).

            3 INSTRUCTIONAL NOTES
            3.1 Code first — sequence underlying condition first.
            3.2 Use additional code — for severity, manifestation, cause when required.
            3.3 Code also — optional additional codes.

            4 SEQUENCING
            4.1 Primary condition first — main reason for encounter.
            4.2 Etiology then manifestation — cause first, effect second (e.g. diabetes then neuropathy).
            4.3 Code first / use additional — follow Tabular notes; overrides casual ordering.
            4.4 Combination code — if a valid combination exists, do not split into separate codes unnecessarily.

            5 MANIFESTATION — often not primary alone; needs underlying condition when rules require.

            6 LATERALITY — left / right / bilateral per documentation.

            7 ACUTE VS CHRONIC — if both, sequence per guideline (often acute then chronic where applicable).

            8 EXTERNAL CAUSE — injuries; usually not primary except special cases.

            9 PLACEHOLDER X — maintain code structure where required.

            10 7TH CHARACTER — injuries, obstetrics, etc.; A/D/S etc. per Tabular.

            11 BRACKETS [ ] — manifestation / synonyms per index.
            12 PARENTHESES ( ) — non-essential modifiers.
            13 COLON : — incomplete phrase; may need subterms from index.

            14 ALPHABETIC INDEX — See (must follow); See also (optional).

            15 DOCUMENTATION — code only what is documented; do not assume conditions (except "with" linkage rules).

            16 OUTPATIENT — confirmed diagnosis yes; suspected → symptoms not definitive dx.
            17 INPATIENT — probable/suspected/likely may be coded per setting rules.

            18 DUPLICATES — do not assign same condition twice or redundant codes.

            SUBSTANCE USE (F10-F19): abuse vs dependence vs unspecified per Tabular + guidelines; do not map "severe" alone to dependence without support.
            """;

    /**
     * How to mirror tools like CodeQuest: follow the alphabetic index chain (See → target term → subterms → default).
     */
    public static final String INDEX_TRAVERSAL = """
            ALPHABETIC INDEX TRAVERSAL (like CodeQuest — follow the book, not only vector similarity)

            0) USE (of) vs OTHER MAIN TERMS — If the diagnosis/query contains the word "use" or "with" (as words), navigate under "Use (of)" (See chain → stimulant NEC, etc.) and do NOT prefer alternate main terms such as "amphetamine-type substance use" unless the index path you are following requires it. If the query does NOT contain "use" or "with" as words, you may follow a more specific index main term when it matches (e.g. amphetamine-type substance use with severity).

            1) START at the correct main term (e.g. under "Use (of)" for substance use when step 0 applies, not only the drug name in isolation).

            2) "See" — mandatory. If the line says e.g. "amphetamine — See Use, stimulant NEC", you MUST move to that target entry. Do NOT pick a code from "amphetamine" alone if that line has no code and only a See reference.

            3) RESOLVE the See target (e.g. "Use, stimulant NEC" / stimulant NEC). That row is the anchor for the next step.

            4) SUBTERMS — only if documentation supports them (e.g. "harmful" may point to Abuse…; "in remission" may have its own code like F15.91). If the diagnosis does not mention that subterm, do not use that sub-code.

            5) DEFAULT AT LEVEL — when there is no matching sub-entry (no with/include/exclude that applies to this case at that level), use the main code shown on the resolved index line for that term (the "direct" code for stimulant NEC / use as shown in the index), then confirm 4th–6th digits in the Tabular List.

            6) TABULAR after index — instructional notes (code first, 7th character, excludes) override a wrong index pick.

            7) Candidates from RAG may include several codes; choose the one consistent with steps 0–5 and Tabular, not the semantically closest unrelated F15.x.
            """;

    /**
     * How official guidelines lead which path to take in {@code icdalpha_2026.json} (same structure as the book index).
     */
    public static final String ICDALPHA_JSON_LEAD = """
            HOW GUIDELINES LEAD THE ICD CODE IN icdalpha_2026.json (alphabetic index)

            What the file is:
            - icdalpha_2026.json is the ALPHABETIC INDEX in tree form: title, code, see, children[].
            - It is NOT the Tabular List; final digits and instructional notes still require Tabular rules.

            Order of work (always):
            1) ICD-10-CM Official Guidelines (ICD-Guidlines.txt) — chapter rules, sequencing, abuse vs dependence, outpatient vs inpatient; for this RAG workflow treat Excludes2 as non-blocking. Code first / use additional.
            2) Navigate THIS index (candidates below) — if the query contains "use" or "with" as words, lead with "Use (of)" navigation; otherwise follow "See" / children until the term that matches the diagnosis + guidelines.
            3) Tabular List — confirm the code from the index row (4th–7th characters, 7th character, combination codes).

            How JSON fields map to "leading" the code:
            - title — index line label; stack with parent path for context (e.g. Use (of) > amphetamine).
            - see — mandatory cross-reference; jump to the target term before choosing a code (may use ~ separators in data).
            - code — index lead code when present on that line; still verify with Tabular + guidelines.
            - children — subterms under that heading; pick a child only if documentation matches that subterm.

            Leading = guidelines tell you WHICH KIND of code (e.g. abuse vs dependence, primary vs additional); the index JSON tells you WHICH ROW/See chain gets you there. Do not pick a code from a random similar title without following See and guidelines.
            """;

}
