package wl.ai;

import com.google.gson.Gson;
import java.util.Map;

/** Simple example showing how to use ClinicalExtractionService. */
public class ClinicalExtractionServiceExample {
    public static void main(String[] args) {
	String clinicalText = """
		        	BJH

		Patient: PARSONS, JEREMY B
		DOB: 7/10/1996
		Patient Medical Record
		                ID: 3600515
		                Account #:
		                Payor:
		                ICD CODES:
		                ACCIDENT CODE:
		                ACCIDENT DATE:
		                ADMIT DATE: 2/7/2026

		                 Encounter: 10146767245Arial;SEGOE UI;SEGOE UI;Arial;;;;;;;;;;;;;;;mk
		                ? ED Provider Notes y Wayne A Rummings, MD at 2/7/2026  7:31 PM

		                Author: Wayne A Rummings, MD

		                Service: ???

		                Author Type: Physician

		                Filed: 2/7/2026  8:53 PM

		                Date of

		                Service: 2/7/2026  7:31 PM

		                Status: SignedEditor: Wayne A Rummings, MD (Physician)
		                ? History
		                ? Chief ComplaintPatient presents withLumar X_rayPatient was supposed to have a ray of ack efore follow up with his neurosurgeon on Monday. Registration has been unable to locate the order on the computer system and paper order has expired.

		                HPI
		                29_year_old male presents to have an _ray performed.  He had an injury that occurred last year and is followed by neurosurgery.  They have requested that a lumbar x-ray be performed prior to his follow_up appointment this week; however there is been some difficulty tracking this order and thus he came to the ED to have this done.  He has no complaints.  He has no pain.  He has no weakness.  He reports that he wants this done so he can return to work.  Otherwise normal state of health
		                ? Past Medical History:DiagnosisDateAsthmaHemorrhoids

		                ? Past Surgical History:ProcedureLateralityDateRECTAL EXAMINATION UNDER ANESTHESIA5/37/21with dilation of anal sphincter RECTAL EXAMINATION UNDER ANESTHESIA12/02/2021with lateral internal sphincterotomy

		                No family history on file.

		                Review of Systems
		                10 point complete review of systems obtained and at patient baseline or negative unless otherwise specified in HPI.
		                ? Physical Exam? Visit Vitals
		                BP146/72 (BP Location: Left arm, Patient Position: Sitting)Pulse90Temp36.2  (97.2 ) (Oral)Resp14Ht1.829 m (6)Wt120 kg (265 l)SpO298%BMI35.94 kg/m2 Smoking StatusFormerBSA2.4 m2

		                ? PHYSICAL EXAM:
		                I have reviewed the triage vital signs.
		                GENERAL: No acute distress. Alert and oriented x3. Well nourished, well developed, appears stated age. Ambulatory. Moving all extremities.

		                ED Course
		                Clinical Impressions as of 02/07/26 2053: X-ray performed
		                Radiology: ordered.

		                Impression: Vital signs within normal limits, afebrile, non-toxic appearing, no acute distress. Encounter for lumbar x-ray. He is able to show me an email which has requested a lumbar AP and lateral film be performed. He has no complaints. He has no pain. I will order an x-ray per his request so we can have the appropriate follow_up so he can return to work. He understands that this will need to be reviewed by his neurosurgeon and he will follow_up this week at his scheduled appointment for interpretation and guidance.

		                Wayne A Rummings, MD
		                02/07/26 2053
		                """;

	ClinicalExtractionService svc = new ClinicalExtractionService();
	try {
	    Map<String, Object> result = svc.extract(clinicalText);
	    System.out.println("Extraction result (JSON):");
	    System.out.println(new Gson().toJson(result));
	} catch (Exception e) {
	    System.err.println("Extraction failed: " + e.getMessage());
	    e.printStackTrace();
	}
    }
}
