package com.wl.zotecAgent;

/**
 * @deprecated Use {@link BotApplication} — Flow requires Spring-injected beans
 *             (DocumentProcessingService, resume endpoint, etc.).
 */
@Deprecated
public class Init {

    public static void main(String[] args) {
	BotApplication.main(args);
    }
}
