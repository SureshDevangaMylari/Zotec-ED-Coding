package wl.ai;

import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;

/**
 * Example: pass a base64 image string → get Map<String, Object> back.
 *
 * Flow:
 *   base64 string
 *     → ClinicalExtractionService.extractFromBase64()
 *       → LLMService.callWithBase64ToMap()
 *         → HTTP to local LLM
 *       ← Map<String, Object>
 *     ← Map<String, Object>
 */
public class Base64ExtractionExample {

    public static void main(String[] args) throws Exception {

        // ── 1. Load an image file and convert to base64 ──────────────────────
        //    (In production you'd already have the base64 string from your app)
        String imagePath = args.length > 0 ? args[0] : "screenshots/details.png";
        File imageFile = new File(imagePath);

        if (!imageFile.exists()) {
            System.out.println("Image not found: " + imagePath);
            System.out.println("Usage: Base64ExtractionExample <path-to-image>");
            System.out.println("\nRunning text-only demo instead...\n");
            runTextDemo();
            return;
        }

        byte[] imageBytes = Files.readAllBytes(imageFile.toPath());
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String mediaType = imagePath.endsWith(".png") ? "image/png" : "image/jpeg";

        System.out.println("Image loaded: " + imagePath);
        System.out.println("Base64 length: " + base64Image.length() + " chars");
        System.out.println("Media type: " + mediaType);

        // ── 2. Call ClinicalExtractionService (uses LLMService internally) ───
        ClinicalExtractionService svc = new ClinicalExtractionService();

        System.out.println("\n=== Extracting via ClinicalExtractionService.extractFromBase64() ===");
        Map<String, Object> result = svc.extractFromBase64(base64Image, mediaType);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));

        // ── 3. Or call LLMService directly with your own prompts ─────────────
        LLMService llm = new LLMService();

        System.out.println("\n=== Extracting via LLMService.callWithBase64ToMap() ===");
        Map<String, Object> result2 = llm.callWithBase64ToMap(
                base64Image,
                mediaType,
                "You are a document extraction assistant. Return ONLY valid JSON.",
                "Extract all visible text and data from this image as structured JSON."
        );
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result2));
    }

    private static void runTextDemo() throws Exception {
        String clinicalText = """
                Patient: PARSONS, JEREMY B
                DOB: 7/10/1996
                ID: 3600515
                ADMIT DATE: 2/7/2026
                Author: Wayne A Rummings, MD
                Chief Complaint: Lumbar X-ray
                HPI: 29 year old male presents for lumbar x-ray prior to neurosurgery follow-up.
                Vitals: BP 146/72, Pulse 90, Temp 36.2 (Oral), Resp 14, SpO2 98%
                Diagnosis: X-ray performed
                """;

        ClinicalExtractionService svc = new ClinicalExtractionService();

        System.out.println("=== Text Extraction via ClinicalExtractionService.extract() ===");
        Map<String, Object> result = svc.extract(clinicalText);
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
