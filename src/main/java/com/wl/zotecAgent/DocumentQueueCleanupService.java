package com.wl.zotecAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Clears leftover documents via {@code GET /documents} and
 * {@code DELETE /documents/{document_id}} before review polling.
 * Authenticates first with {@code POST /auth/login}. Subsequent calls use desk
 * headers only ({@code x-desktop-id}, {@code x-bot-secret}) — no session cookie.
 */
@Service
public class DocumentQueueCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DocumentQueueCleanupService.class);

    private final RestTemplate restTemplate;

    @Value("${document.review.base-url}")
    private String baseUrl;

    @Value("${document.auth.login-url}")
    private String authLoginUrl;

    /** Hardcoded document-API credentials (not Zotec portal login). */
    @Value("${document.auth.username}")
    private String authUsername;

    @Value("${document.auth.password}")
    private String authPassword;

    @Value("${bot.poll.desktop-id}")
    private String desktopId;

    @Value("${bot.poll.secret}")
    private String botSecret;

    public DocumentQueueCleanupService(RestTemplate restTemplate) {
	this.restTemplate = restTemplate;
    }

    /**
     * Lists documents in the queue; deletes each id one-by-one.
     * Skips {@code keepDocumentId} (the record about to be reviewed) if present.
     * If the queue is empty, does nothing — caller continues to review.
     */
    @SuppressWarnings("unchecked")
    public void clearQueuedDocumentsExcept(String keepDocumentId) {
	try {
	    loginForDocumentApi();
	} catch (Exception e) {
	    log.warn("POST /auth/login failed — continuing to review without document cleanup: {}",
		    e.getMessage());
	    return;
	}

	String listUrl = baseUrl.replaceAll("/+$", "") + "/documents";
	HttpHeaders headers = DocumentProcessorAuth.uploadHeaders(desktopId, botSecret);

	log.info("Checking document queue: GET {} ({}={})",
		listUrl, DocumentProcessorAuth.DESKTOP_ID_HEADER, desktopId);

	List<String> documentIds = new ArrayList<>();
	try {
	    ResponseEntity<Map> response = restTemplate.exchange(
		    listUrl,
		    HttpMethod.GET,
		    new HttpEntity<>(headers),
		    Map.class);
	    Map<String, Object> body = response.getBody();
	    log.info("GET /documents response: {}", body);

	    if (body != null) {
		Object docsObj = body.get("documents");
		if (docsObj instanceof List<?> docs) {
		    for (Object item : docs) {
			if (!(item instanceof Map<?, ?> m)) {
			    continue;
			}
			Object id = m.get("document_id");
			if (id == null) {
			    id = m.get("documentId");
			}
			if (id == null) {
			    id = m.get("id");
			}
			if (id != null) {
			    String s = String.valueOf(id).trim();
			    if (!s.isEmpty()) {
				documentIds.add(s);
			    }
			}
		    }
		}
	    }
	} catch (Exception e) {
	    log.warn("GET /documents failed — continuing to review without cleanup: {}", e.getMessage());
	    return;
	}

	if (documentIds.isEmpty()) {
	    log.info("No documents in queue — continue to review API");
	    return;
	}

	log.info("Found {} document(s) in queue — deleting one by one: {}", documentIds.size(), documentIds);

	for (String documentId : documentIds) {
	    if (keepDocumentId != null && keepDocumentId.equalsIgnoreCase(documentId)) {
		log.info("Skipping DELETE for current upload document_id={}", documentId);
		continue;
	    }
	    deleteDocument(documentId, headers);
	}
    }

    /**
     * {@code POST /auth/login} — keeps the login step. Session cookie from the
     * server is not read, stored, logged, or forwarded on later requests.
     */
    @SuppressWarnings("unchecked")
    private void loginForDocumentApi() {
	HttpHeaders headers = new HttpHeaders();
	headers.setContentType(MediaType.APPLICATION_JSON);
	headers.setAccept(List.of(MediaType.APPLICATION_JSON));

	Map<String, String> body = new LinkedHashMap<>();
	body.put("username", authUsername);
	body.put("password", authPassword);

	log.info("Document API login: POST {} (username={})", authLoginUrl, authUsername);

	ResponseEntity<Map> response = restTemplate.exchange(
		authLoginUrl,
		HttpMethod.POST,
		new HttpEntity<>(body, headers),
		Map.class);

	if (!response.getStatusCode().is2xxSuccessful()) {
	    throw new IllegalStateException("auth/login failed status=" + response.getStatusCode().value());
	}
	log.info("Document API login succeeded status={}", response.getStatusCode().value());
    }

    private void deleteDocument(String documentId, HttpHeaders headers) {
	String deleteUrl = baseUrl.replaceAll("/+$", "") + "/documents/" + documentId;
	try {
	    log.info("DELETE {}", deleteUrl);
	    ResponseEntity<String> response = restTemplate.exchange(
		    deleteUrl,
		    HttpMethod.DELETE,
		    new HttpEntity<>(headers),
		    String.class);
	    log.info("Deleted document_id={} status={} body={}",
		    documentId, response.getStatusCode().value(), response.getBody());
	} catch (Exception e) {
	    log.warn("Failed to DELETE document_id={}: {}", documentId, e.getMessage());
	}
    }
}
