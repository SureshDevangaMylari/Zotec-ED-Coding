package com.wl.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Utility class for saving Map<String, Object> to JSON files.
 * Supports nested tree structures (Map within Map, List, etc.)
 * Default save location: resources/jsonfolder
 */
public class JsonFileUtil {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonFileUtil.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DEFAULT_DIRECTORY = "resources/jsonfolder";
    
    static {
        // Ensure default directory exists on class load
        ensureDefaultDirectoryExists();
    }
    
    /**
     * Ensure the default directory exists, create if it doesn't.
     * 
     * 
     */
    private static void ensureDefaultDirectoryExists() {
        try {
            File defaultDir = new File(DEFAULT_DIRECTORY);
            if (!defaultDir.exists()) {
                defaultDir.mkdirs();
                logger.info("Created default directory: {}", defaultDir.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Could not create default directory: {}", e.getMessage());
        }
    }
    
    /**
     * Save Map<String, Object> to a JSON file with pretty printing.
     * Uses default directory: resources/jsonfolder
     * The Object can be a nested tree structure (Map, List, primitive types, etc.)
     * 
     * @param data The Map data to save (can contain nested Maps, Lists, etc.)
     * @param fileName The name of the JSON file (will be saved in default directory)
     * @return The absolute path of the saved file
     * @throws IOException If there's an error writing the file
     */
    public static String saveToJsonFile(Map<String, Object> data, String fileName) throws IOException {
        return saveToJsonFile(data, DEFAULT_DIRECTORY, fileName, true);
    }
    
    /**
     * Save Map<String, Object> to a JSON file with pretty printing at specified path.
     * The Object can be a nested tree structure (Map, List, primitive types, etc.)
     * 
     * @param data The Map data to save (can contain nested Maps, Lists, etc.)
     * @param filePath The full path where the JSON file should be saved
     * @return The absolute path of the saved file
     * @throws IOException If there's an error writing the file
     */
    public static String saveToJsonFileAtPath(Map<String, Object> data, String filePath) throws IOException {
        return saveToJsonFileAtPath(data, filePath, true);
    }
    
    /**
     * Save Map<String, Object> to a JSON file at specified path.
     * The Object can be a nested tree structure (Map, List, primitive types, etc.)
     * 
     * @param data The Map data to save (can contain nested Maps, Lists, etc.)
     * @param filePath The full path where the JSON file should be saved
     * @param prettyPrint If true, formats JSON with indentation; if false, compact format
     * @return The absolute path of the saved file
     * @throws IOException If there's an error writing the file
     */
    public static String saveToJsonFileAtPath(Map<String, Object> data, String filePath, boolean prettyPrint) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
        
        // Ensure directory exists
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
            logger.info("Created directory: {}", parentDir.getAbsolutePath());
        }
        
        // Write JSON to file
        if (prettyPrint) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, data);
        } else {
            objectMapper.writeValue(file, data);
        }
        
        String absolutePath = file.getAbsolutePath();
        logger.info("Successfully saved JSON to file: {}", absolutePath);
        return absolutePath;
    }
    
    
    /**
     * Save Map<String, Object> to a JSON file in a specific directory.
     * 
     * @param data The Map data to save
     * @param directory The directory where the file should be saved (if null, uses default directory)
     * @param fileName The name of the JSON file (will add .json extension if not present)
     * @return The absolute path of the saved file
     * @throws IOException If there's an error writing the file
     */
    public static String saveToJsonFile(Map<String, Object> data, String directory, String fileName) throws IOException {
        if (directory == null || directory.trim().isEmpty()) {
            directory = DEFAULT_DIRECTORY;
        }
        return saveToJsonFile(data, directory, fileName, true);
    }
    
    /**
     * Save Map<String, Object> to a JSON file in a specific directory.
     * 
     * @param data The Map data to save
     * @param directory The directory where the file should be saved (if null, uses default directory)
     * @param fileName The name of the JSON file (will add .json extension if not present)
     * @param prettyPrint If true, formats JSON with indentation; if false, compact format
     * @return The absolute path of the saved file
     * @throws IOException If there's an error writing the file
     */
    public static String saveToJsonFile(Map<String, Object> data, String directory, String fileName, boolean prettyPrint) throws IOException {
        if (directory == null || directory.trim().isEmpty()) {
            directory = DEFAULT_DIRECTORY;
        }
        
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("File name cannot be null or empty");
        }
        
        // Ensure .json extension
        if (!fileName.toLowerCase().endsWith(".json")) {
            fileName = fileName + ".json";
        }
        
        // Ensure directory exists
        File dir = new File(directory);
        if (!dir.exists()) {
            dir.mkdirs();
            logger.info("Created directory: {}", dir.getAbsolutePath());
        }
        
        // Build full path
        String filePath = directory + File.separator + fileName;
        return saveToJsonFileAtPath(data, filePath, prettyPrint);
    }
    
    /**
     * Get the default directory path.
     * 
     * @return The default directory path
     */
    public static String getDefaultDirectory() {
        return DEFAULT_DIRECTORY;
    }
    
    /**
     * Convert Map<String, Object> to JSON string.
     * 
     * @param data The Map data to convert
     * @return JSON string representation
     * @throws IOException If there's an error converting to JSON
     */
    public static String toJsonString(Map<String, Object> data) throws IOException {
        return toJsonString(data, true);
    }
    
    /**
     * Convert Map<String, Object> to JSON string.
     * 
     * @param data The Map data to convert
     * @param prettyPrint If true, formats JSON with indentation; if false, compact format
     * @return JSON string representation
     * @throws IOException If there's an error converting to JSON
     */
    public static String toJsonString(Map<String, Object> data, boolean prettyPrint) throws IOException {
        if (data == null) {
            return "null";
        }
        
        if (prettyPrint) {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } else {
            return objectMapper.writeValueAsString(data);
        }
    }
    
    /**
     * Read JSON file and convert to Map<String, Object>.
     * 
     * @param filePath The path to the JSON file
     * @return Map containing the parsed JSON data
     * @throws IOException If there's an error reading the file
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readFromJsonFile(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File does not exist: " + filePath);
        }
        
        Map<String, Object> data = objectMapper.readValue(file, Map.class);
        logger.info("Successfully read JSON from file: {}", file.getAbsolutePath());
        return data;
    }
    
    /**
     * Read JSON string and convert to Map<String, Object>.
     * 
     * @param jsonString The JSON string to parse
     * @return Map containing the parsed JSON data
     * @throws IOException If there's an error parsing the JSON
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readFromJsonString(String jsonString) throws IOException {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON string cannot be null or empty");
        }
        
        Map<String, Object> data = objectMapper.readValue(jsonString, Map.class);
        return data;
    }
    
    /**
     * Example usage method demonstrating nested tree structures.
     */
    public static void example() {
        try {
            // Create a nested Map structure (tree)
            Map<String, Object> data = new java.util.HashMap<>();
            
            // Simple values
            data.put("name", "John Doe");
            data.put("age", 30);
            data.put("active", true);
            
            // Nested Map
            Map<String, Object> address = new java.util.HashMap<>();
            address.put("street", "123 Main St");
            address.put("city", "New York");
            address.put("zip", "10001");
            
            // Nested Map within nested Map
            Map<String, Object> coordinates = new java.util.HashMap<>();
            coordinates.put("lat", 40.7128);
            coordinates.put("lon", -74.0060);
            address.put("coordinates", coordinates);
            
            data.put("address", address);
            
            // List of Maps
            java.util.List<Map<String, Object>> phones = new java.util.ArrayList<>();
            Map<String, Object> phone1 = new java.util.HashMap<>();
            phone1.put("type", "home");
            phone1.put("number", "555-0100");
            phones.add(phone1);
            
            Map<String, Object> phone2 = new java.util.HashMap<>();
            phone2.put("type", "work");
            phone2.put("number", "555-0200");
            phones.add(phone2);
            
            data.put("phones", phones);
            
            // Save to file (uses default directory: resources/jsonfolder)
            String filePath = saveToJsonFile(data, "example.json");
            logger.info("Example JSON saved to: {}", filePath);
            
            // Read back
            Map<String, Object> readData = readFromJsonFile(filePath);
            logger.info("Read back data: {}", toJsonString(readData));
            
        } catch (Exception e) {
            logger.error("Error in example: {}", e.getMessage(), e);
        }
    }
}

