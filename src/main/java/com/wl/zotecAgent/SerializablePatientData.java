package com.wl.zotecAgent;

import java.io.Serializable;
import java.util.Map;

/**
 * Serializable object to store patient data for later use
 * This object can be serialized and deserialized for persistent storage
 */
public class SerializablePatientData implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private int totalImagesFound;
    private int totalProcessed;
    private String processingDate;
    private Map<String, Object> patientsData;
    
    // Default constructor
    public SerializablePatientData() {
    }
    
    // Constructor with parameters
    public SerializablePatientData(int totalImagesFound, int totalProcessed, 
                                 String processingDate, Map<String, Object> patientsData) {
        this.totalImagesFound = totalImagesFound;
        this.totalProcessed = totalProcessed;
        this.processingDate = processingDate;
        this.patientsData = patientsData;
    }
    
    // Getters and Setters
    public int getTotalImagesFound() {
        return totalImagesFound;
    }
    
    public void setTotalImagesFound(int totalImagesFound) {
        this.totalImagesFound = totalImagesFound;
    }
    
    public int getTotalProcessed() {
        return totalProcessed;
    }
    
    public void setTotalProcessed(int totalProcessed) {
        this.totalProcessed = totalProcessed;
    }
    
    public String getProcessingDate() {
        return processingDate;
    }
    
    public void setProcessingDate(String processingDate) {
        this.processingDate = processingDate;
    }
    
    public Map<String, Object> getPatientsData() {
        return patientsData;
    }
    
    public void setPatientsData(Map<String, Object> patientsData) {
        this.patientsData = patientsData;
    }
    
    @Override
    public String toString() {
        return "SerializablePatientData{" +
                "totalImagesFound=" + totalImagesFound +
                ", totalProcessed=" + totalProcessed +
                ", processingDate='" + processingDate + '\'' +
                ", patientsDataCount=" + (patientsData != null ? patientsData.size() : 0) +
                '}';
    }
}