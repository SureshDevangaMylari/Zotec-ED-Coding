package com.wl.zotecAgent;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Page;

/**
 * After coding fill is done, applies either Move to Issue or RFI — never both.
 * JSON is expected to contain at most one of {@code issue} / {@code rfi}
 * (legacy {@code move_to_issue} still accepted).
 * If both somehow have data, Move to Issue wins and RFI is skipped (buttons disable each other).
 */
public final class IssueOrRfiApplier {

    private static final Logger log = LogManager.getLogger(IssueOrRfiApplier.class);

    private IssueOrRfiApplier() {
    }

    public static void applyAfterCodingFill(Page page, Map<String, Object> resumePayload) {
	boolean move = ResumePayloadMapper.shouldApplyMoveToIssue(resumePayload);
	boolean rfi = ResumePayloadMapper.shouldApplyRfi(resumePayload);

	if (!move && !rfi) {
	    log.info("Neither issue nor rfi present in JSON — skipping both");
	    PlayTestActionLog.skip("Move to Issue / RFI", "neither issue nor rfi has data in JSON");
	    return;
	}

	if (move && rfi) {
	    log.warn("JSON has both issue and rfi — applying Move to Issue only (RFI button would be disabled)");
	    PlayTestActionLog.skip("RFI", "skipped because issue is also present");
	}

	if (move) {
	    new MoveToIssueService(page).applyFromResume(resumePayload);
	    return;
	}

	new RfiService(page).applyFromResume(resumePayload);
    }
}
