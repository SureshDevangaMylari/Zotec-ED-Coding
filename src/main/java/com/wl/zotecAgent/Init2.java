package com.wl.zotecAgent;

/**
 * @deprecated Use {@link BotApplication} — on startup {@link FlowStartupRunner} launches Chrome
 *             (same as this class) and calls {@link Flow#Start} via {@link BotService}.
 */
@Deprecated
public class Init2 {

    public static void main(String[] args) {
	BotApplication.main(args);
    }
}
