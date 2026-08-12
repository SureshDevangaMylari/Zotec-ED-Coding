package wl.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wl.util.JsonFileUtil;

import io.github.cdimascio.dotenv.Dotenv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Smart Image/Screenshot Reader Automatically detects content type (table,
 * form, document, text) and converts to JSON Exact Java equivalent of
 * smart_image_reader.py
 */
public class SmartImageReader {
    private static final Logger logger = LoggerFactory.getLogger(SmartImageReader.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Read image or screenshot and convert to JSON format. Automatically detects
     * content type and structures data accordingly.
     * 
     * @throws IOException
     */
    public static Map<String, Object> readImageToJson(String imagePath) throws IOException {
	Dotenv dotenv = Dotenv.load();
	String apiUrl = dotenv.get("SELF_HOSTED_API_URL");
	logger.error("Reading image: {}", imagePath);
	logger.error("Analyzing content type and extracting data...\n");

	Map<String, Object> result = LLMServiceImageToJson.readImageSmartJson(imagePath, apiUrl);

	System.out.println(result);
	try {
	    JsonFileUtil.saveToJsonFileAtPath(result, "resources/jsonfolder/output.json", true);
	} catch (Exception e) {
	    // TODO: handle exception
	    e.printStackTrace();
	}

	return result;
    }

    public static void main(String[] args) throws IOException {
	logger.error("=" + "=".repeat(69));
	logger.error("Smart Image/Screenshot to JSON Converter");
	logger.error("=" + "=".repeat(69));

	String imagePath = null;

	// Get image path from command line or use default
	if (args.length > 0) {
	    imagePath = args[0];
	} else {
	    // Try common image files
	    String[] commonImages = { "1.png", "2.png", "screenshot.png" };
	    for (String img : commonImages) {
		if (Files.exists(Paths.get(img))) {
		    imagePath = img;
		    logger.error("\nUsing default image: {}", imagePath);
		    break;
		}
	    }

	    if (imagePath == null) {
		logger.error("\nEnter image path (or press Enter to take screenshot): ");
		// In real implementation, would read from System.in
		imagePath = "screenshot.png"; // Placeholder
	    }
	}

	if (!Files.exists(Paths.get(imagePath))) {
	    logger.error("❌ Error: Image file not found: {}", imagePath);
	    return;
	}

	// Read and convert to JSON
	Map<String, Object> result = readImageToJson(imagePath);

	// Display results
	try {
	    logger.error("\n" + "=".repeat(70));
	    logger.error("EXTRACTED DATA (JSON FORMAT):");
	    logger.error("=".repeat(70));
	    logger.error("{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
	    logger.error("=".repeat(70));

	    // Display summary
	    if (result.containsKey("error")) {
		logger.error("\n❌ Error: {}", result.get("error"));
	    } else {
		String contentType = (String) result.getOrDefault("content_type", "unknown");
		logger.error("\n✅ Content Type Detected: {}", contentType.toUpperCase());

		if ("table".equals(contentType)) {
		    @SuppressWarnings("unchecked")
		    java.util.List<String> headers = (java.util.List<String>) result.getOrDefault("headers",
			    new java.util.ArrayList<>());
		    @SuppressWarnings("unchecked")
		    java.util.List<Map<String, Object>> rows = (java.util.List<Map<String, Object>>) result
			    .getOrDefault("rows", new java.util.ArrayList<>());
		    logger.error("   📊 Table Structure:");
		    logger.error("      - Headers: {} columns", headers.size());
		    logger.error("      - Rows: {} data rows", rows.size());
		    if (!headers.isEmpty()) {
			int showCount = Math.min(5, headers.size());
			logger.error("      - Columns: {}",
				String.join(", ", headers.subList(0, showCount)) + (headers.size() > 5 ? "..." : ""));
		    }
		} else if ("form".equals(contentType)) {
		    @SuppressWarnings("unchecked")
		    java.util.List<Map<String, Object>> fields = (java.util.List<Map<String, Object>>) result
			    .getOrDefault("fields", new java.util.ArrayList<>());
		    logger.error("   📝 Form Structure:");
		    logger.error("      - Fields: {} form fields", fields.size());
		    if (!fields.isEmpty()) {
			logger.error("      - First few fields:");
			int showCount = Math.min(3, fields.size());
			for (int i = 0; i < showCount; i++) {
			    Map<String, Object> field = fields.get(i);
			    logger.error("        • {}: {}", field.getOrDefault("label", "N/A"),
				    field.getOrDefault("value", "N/A"));
			}
		    }
		} else if ("document".equals(contentType)) {
		    @SuppressWarnings("unchecked")
		    java.util.List<Map<String, Object>> sections = (java.util.List<Map<String, Object>>) result
			    .getOrDefault("sections", new java.util.ArrayList<>());
		    logger.error("   📄 Document Structure:");
		    logger.error("      - Sections: {} sections", sections.size());
		    if (!sections.isEmpty()) {
			int showCount = Math.min(3, sections.size());
			java.util.List<String> titles = new java.util.ArrayList<>();
			for (int i = 0; i < showCount; i++) {
			    titles.add((String) sections.get(i).getOrDefault("title", "N/A"));
			}
			logger.error("      - Section titles: {}", String.join(", ", titles));
		    }
		} else if ("text".equals(contentType)) {
		    String text = (String) result.getOrDefault("text", "");
		    @SuppressWarnings("unchecked")
		    java.util.List<String> textElements = (java.util.List<String>) result.getOrDefault("text_elements",
			    new java.util.ArrayList<>());
		    logger.error("   📝 Text Content:");
		    logger.error("      - Text length: {} characters", text.length());
		    logger.error("      - Text elements: {} items", textElements.size());
		    if (!text.isEmpty()) {
			String preview = text.length() > 100 ? text.substring(0, 100) + "..." : text;
			logger.error("      - Preview: {}", preview);
		    }
		}
	    }
	} catch (Exception e) {
	    logger.error("Error displaying results: {}", e.getMessage(), e);
	}
    }
}
