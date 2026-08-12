package com.wl.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to read JSON files and return Map&lt;String, Object&gt; for testing.
 * Default path: resources/jsonfolder/output.json (same file written by ZotecService).
 * <p>
 * Example - test validatePatientDetails with saved output without running image extraction:
 * <pre>
 * JsonReadService reader = new JsonReadService();
 * Map&lt;String, Object&gt; data = reader.readOutputJson();
 * zotecService.validatePatientDetails(page, data);
 * </pre>
 */
public class JsonReadService {

    private static final Logger log = LoggerFactory.getLogger(JsonReadService.class);
    private static final String DEFAULT_OUTPUT_PATH = "resources/jsonfolder/output.json";

    /**
     * Reads the default output.json (from ZotecService extraction) and returns
     * the data as Map&lt;String, Object&gt;. Use this to test validatePatientDetails
     * without running full image extraction.
     *
     * @return Map of extracted data, or empty map if file missing/error
     */
    public Map<String, Object> readOutputJson() {
        return readFromPath(DEFAULT_OUTPUT_PATH);
    }

    /**
     * Reads a JSON file at the given path and returns Map&lt;String, Object&gt;.
     *
     * @param filePath path to JSON file (e.g. "resources/jsonfolder/output.json")
     * @return Map of parsed data, or empty map if file missing/error
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readFromPath(String filePath) {
        try {
            Map<String, Object> data = JsonFileUtil.readFromJsonFile(filePath);
            log.info("Read JSON from {} ({} keys)", filePath, data != null ? data.size() : 0);
            return data != null ? data : new HashMap<>();
        } catch (IOException e) {
            log.warn("Failed to read JSON from {}: {}", filePath, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Reads from a custom path. Same as readFromPath, for convenience.
     */
    public Map<String, Object> readFromFile(String filePath) {
        return readFromPath(filePath);
    }
}
