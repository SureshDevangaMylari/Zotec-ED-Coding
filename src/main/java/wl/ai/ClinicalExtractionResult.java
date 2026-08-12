package wl.ai;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Serializable POJO that matches the extraction JSON schema.
 * Top-level has a single field `raw` which contains the structured data.
 */
public class ClinicalExtractionResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private Raw raw;

    public ClinicalExtractionResult() {}

    public Raw getRaw() {
        return raw;
    }

    public void setRaw(Raw raw) {
        this.raw = raw;
    }

    public static class Raw implements Serializable {
        private static final long serialVersionUID = 1L;

        public Identifiers identifiers;
        public Patient patient;
        public Encounter encounter;
        public Visit visit;
        public MedicalHistory medical_history;
        public List<SurgicalHistoryEntry> surgical_history;
        public Vitals vitals;
        public Object physical_exam;
        public List<ImagingEntry> imaging;
        public List<Object> procedures;
        public List<Object> diagnoses;
        public List<Object> medications;
        public List<Provider> providers;
        public Administrative administrative;
        public Billing billing;
        public Object impression;
        public Object follow_up;
        public Notes notes;
        public String raw_text;

        public Raw() {}
    }

    public static class Identifiers implements Serializable {
        private static final long serialVersionUID = 1L;
        public String id;
        public String mrn;
        public String account_number;
        public String encounter_number;
    }

    public static class Patient implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String age;
        public String sex;
        public String dob;
        public Map<String, Object> other_identifiers;
    }

    public static class Encounter implements Serializable {
        private static final long serialVersionUID = 1L;
        public String admit_date;
        public String encounter_date_time;
        public String encounter_location;
        public String service;
        public String status;
    }

    public static class Visit implements Serializable {
        private static final long serialVersionUID = 1L;
        public String chief_complaint;
        public String hpi;
    }

    public static class MedicalHistory implements Serializable {
        private static final long serialVersionUID = 1L;
        public List<String> past_medical;
        public String family_history;
        public String social_history;
    }

    public static class SurgicalHistoryEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public String procedure;
        public String laterality;
        public String date;
        public String notes;
    }

    public static class Vitals implements Serializable {
        private static final long serialVersionUID = 1L;
        public Integer bp_systolic;
        public Integer bp_diastolic;
        public String bp_location;
        public Integer pulse;
        public Integer respiration;
        public String temp;
        public String temp_method;
        public String spo2;
        public String height;
        public String weight;
        public String bmi;
        public String vitals_raw;
    }

    public static class ImagingEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public String study;
        public String views;
        public String result;
        public String status;
    }

    public static class Provider implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String role;
        public String timestamp;
    }

    public static class Administrative implements Serializable {
        private static final long serialVersionUID = 1L;
        public String filed;
        public String author_type;
    }

    public static class Billing implements Serializable {
        private static final long serialVersionUID = 1L;
        public String payor;
        public List<String> icd_codes;
        public String accident_code;
        public String accident_date;
    }

    public static class Notes implements Serializable {
        private static final long serialVersionUID = 1L;
        public String unparsed_text;
        public String other;
    }
}

