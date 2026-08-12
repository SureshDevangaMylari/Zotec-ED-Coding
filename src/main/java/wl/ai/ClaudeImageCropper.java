package wl.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Claude Image Cropper
 * Uses Claude Sonnet 4 to understand what to crop and returns cropped image
 * Exact Java equivalent of claude_image_cropper.py
 */
public class ClaudeImageCropper {
    private static final Logger logger = LoggerFactory.getLogger(ClaudeImageCropper.class);
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String MODEL = "claude-sonnet-4-20250514";
    
    public ClaudeImageCropper(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : System.getenv("ANTHROPIC_API_KEY");
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            throw new IllegalArgumentException(
                "API key not provided. Set ANTHROPIC_API_KEY environment variable " +
                "or pass apiKey parameter"
            );
        }
    }

    /**
     * Load image from file path
     */
    public BufferedImage loadImage(String imagePath) throws IOException {
        if (!Files.exists(Paths.get(imagePath))) {
            throw new IOException("Image file not found: " + imagePath);
        }
        return ImageIO.read(new File(imagePath));
    }

    /**
     * Convert BufferedImage to base64 string
     */
    public String imageToBase64(BufferedImage image) throws IOException {
        // Convert to base64 (simplified - would need proper PNG encoding)
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }

    /**
     * Use Claude to find the location of an element in the image
     */
    public Map<String, Object> findElementLocation(BufferedImage image, String description) {
        try {
            String imgBase64 = imageToBase64(image);
            String prompt = buildFindElementPrompt(description, image.getWidth(), image.getHeight());
            
            String responseText = callClaudeAPI(prompt, imgBase64, 1024, 0.1f);
            responseText = extractJsonFromResponse(responseText);
            
            if (responseText != null && !responseText.isEmpty()) {
                JsonNode result = objectMapper.readTree(responseText);
                Map<String, Object> location = objectMapper.convertValue(result, Map.class);
                
                if (Boolean.TRUE.equals(location.get("found"))) {
                    // Validate and clamp coordinates
                    int x = Math.max(0, Math.min((Integer) location.get("x"), image.getWidth() - 1));
                    int y = Math.max(0, Math.min((Integer) location.get("y"), image.getHeight() - 1));
                    int width = Math.max(1, Math.min((Integer) location.get("width"), image.getWidth() - x));
                    int height = Math.max(1, Math.min((Integer) location.get("height"), image.getHeight() - y));
                    
                    location.put("x", x);
                    location.put("y", y);
                    location.put("width", width);
                    location.put("height", height);
                }
                
                return location;
            }
        } catch (Exception e) {
            logger.error("Error finding element: {}", e.getMessage());
        }
        
        Map<String, Object> notFound = new HashMap<>();
        notFound.put("found", false);
        notFound.put("reason", "Failed to parse response");
        return notFound;
    }

    /**
     * Crop an element from an image based on description
     */
    public BufferedImage cropElement(String description, BufferedImage image, 
                                    String imagePath, int padding, String savePath) {
        try {
            // Load image if path provided
            if (imagePath != null) {
                image = loadImage(imagePath);
            }
            if (image == null) {
                throw new IllegalArgumentException("Either 'image' or 'imagePath' must be provided");
            }
            
            logger.info("🔍 Searching for: '{}'...", description);
            
            // Find element location
            Map<String, Object> location = findElementLocation(image, description);
            
            if (!Boolean.TRUE.equals(location.get("found"))) {
                logger.warn("❌ Element not found: {}", location.get("reason"));
                return null;
            }
            
            // Extract bounding box
            int x = (Integer) location.get("x");
            int y = (Integer) location.get("y");
            int width = (Integer) location.get("width");
            int height = (Integer) location.get("height");
            double confidence = ((Number) location.get("confidence")).doubleValue();
            
            logger.info("✓ Found: {}", location.get("description"));
            logger.info("  Location: ({}, {})", x, y);
            logger.info("  Size: {} x {} pixels", width, height);
            logger.info("  Confidence: {:.2f}", confidence);
            
            // Add padding
            x = Math.max(0, x - padding);
            y = Math.max(0, y - padding);
            width = Math.min(image.getWidth() - x, width + (padding * 2));
            height = Math.min(image.getHeight() - y, height + (padding * 2));
            
            // Crop the image
            BufferedImage cropped = image.getSubimage(x, y, width, height);
            
            // Save if path provided
            if (savePath != null) {
                ImageIO.write(cropped, "PNG", new File(savePath));
                logger.info("💾 Saved cropped image to: {}", savePath);
            }
            
            return cropped;
            
        } catch (Exception e) {
            logger.error("Error cropping element: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Take screenshot and crop element based on description
     */
    public BufferedImage cropFromScreenshot(String description, int padding, String savePath) {
        try {
            logger.info("📸 Taking screenshot...");
            // Use Robot to take screenshot
            java.awt.Robot robot = new java.awt.Robot();
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            java.awt.Dimension screenSize = toolkit.getScreenSize();
            java.awt.Rectangle screenRect = new java.awt.Rectangle(screenSize);
            BufferedImage screenshot = robot.createScreenCapture(screenRect);
            
            logger.info("✓ Screenshot captured: {}x{}", screenshot.getWidth(), screenshot.getHeight());
            
            return cropElement(description, screenshot, null, padding, savePath);
        } catch (Exception e) {
            logger.error("Error taking screenshot: {}", e.getMessage(), e);
            return null;
        }
    }

    private String buildFindElementPrompt(String description, int width, int height) {
        return String.format(
            "Find the element described as \"%s\" in this image and return its exact location.\n\n" +
            "Return ONLY a valid JSON object with this structure:\n" +
            "{\n" +
            "    \"found\": true,\n" +
            "    \"x\": <left coordinate in pixels>,\n" +
            "    \"y\": <top coordinate in pixels>,\n" +
            "    \"width\": <width in pixels>,\n" +
            "    \"height\": <height in pixels>,\n" +
            "    \"confidence\": <0.0 to 1.0>,\n" +
            "    \"description\": \"what you found\"\n" +
            "}\n\n" +
            "OR if not found:\n" +
            "{\n" +
            "    \"found\": false,\n" +
            "    \"reason\": \"why it wasn't found\"\n" +
            "}\n\n" +
            "CRITICAL RULES:\n" +
            "- Image dimensions: %d x %d pixels\n" +
            "- Coordinates: Top-left corner is (0, 0)\n" +
            "- x: left edge of the element\n" +
            "- y: top edge of the element\n" +
            "- Include some padding around the element (5-10 pixels)\n" +
            "- Be precise - the bounding box should tightly contain the element\n" +
            "- If multiple similar elements exist, return the most prominent/visible one\n" +
            "- Confidence should be high (>= 0.8) if element is clearly visible\n\n" +
            "Return ONLY valid JSON, no markdown, no explanations.",
            description, width, height
        );
    }

    private String extractJsonFromResponse(String responseText) {
        if (responseText == null) return "";
        
        responseText = responseText.trim();
        
        if (responseText.contains("```json")) {
            int jsonStart = responseText.indexOf("```json") + 7;
            int jsonEnd = responseText.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return responseText.substring(jsonStart, jsonEnd).trim();
            }
        } else if (responseText.contains("```")) {
            int jsonStart = responseText.indexOf("```") + 3;
            int jsonEnd = responseText.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return responseText.substring(jsonStart, jsonEnd).trim();
            }
        }
        
        int jsonStart = responseText.indexOf("{");
        int jsonEnd = responseText.lastIndexOf("}") + 1;
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return responseText.substring(jsonStart, jsonEnd).trim();
        }
        
        return responseText;
    }

    // Placeholder for Claude API call
    private String callClaudeAPI(String prompt, String imgBase64, int maxTokens, float temperature) {
        // TODO: Implement actual Anthropic API call
        logger.warn("Claude API call not implemented. Requires Anthropic Java SDK.");
        return "{\"found\": false, \"reason\": \"API not implemented\"}";
    }
}

