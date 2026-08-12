package com.wl.zotecAgent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verbose per-field logging for {@link PlayTest2}. Enable at start of PlayTest2 main, disable in finally.
 */
public final class PlayTestActionLog {

    private static final Logger log = LogManager.getLogger("PlayTest2.Action");

    private static final ThreadLocal<Boolean> ENABLED = ThreadLocal.withInitial(() -> false);

    private PlayTestActionLog() {}

    public static void enable() {
        ENABLED.set(true);
        log.info("========== PlayTest2 action log START ==========");
    }

    public static void disable() {
        log.info("========== PlayTest2 action log END ==========");
        ENABLED.remove();
    }

    public static boolean isEnabled() {
        return Boolean.TRUE.equals(ENABLED.get());
    }

    public static void step(String step) {
        if (isEnabled()) {
            log.info("--- {} ---", step);
        }
    }

    public static void noData(String field) {
        if (isEnabled()) {
            log.info("[NO DATA] {}", field);
        }
    }

    public static void noData(String field, String reason) {
        if (isEnabled()) {
            log.info("[NO DATA] {} — {}", field, reason);
        }
    }

    public static void skip(String field, String reason) {
        if (isEnabled()) {
            log.info("[SKIP] {} — {}", field, reason);
        }
    }

    public static void skip(String field, String current, String expected) {
        if (isEnabled()) {
            log.info("[SKIP] {} — already matches (page='{}', expected='{}')", field, current, expected);
        }
    }

    public static void update(String field, String detail) {
        if (isEnabled()) {
            log.info("[UPDATE] {} — {}", field, detail);
        }
    }

    public static void add(String field, String value) {
        if (isEnabled()) {
            log.info("[ADD] {} — {}", field, value);
        }
    }

    public static void delete(String field, String value) {
        if (isEnabled()) {
            log.info("[DELETE] {} — {}", field, value);
        }
    }

    public static void info(String message, Object... args) {
        if (isEnabled()) {
            log.info(message, args);
        }
    }
}
