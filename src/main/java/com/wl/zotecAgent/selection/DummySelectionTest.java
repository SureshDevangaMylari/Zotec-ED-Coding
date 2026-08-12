package com.wl.zotecAgent.selection;

import com.wl.util.JsonReadService;

import java.util.Map;

/**
 * Dummy test: runs SelectionTestRunner in a loop 10 times (like main).
 * Usage: mvn exec:java -Dexec.mainClass=com.wl.zotecAgent.selection.DummySelectionTest
 */
public class DummySelectionTest {

    private static final int LOOP_COUNT = 10;
    private static final String DEFAULT_EXCEL_PATH = "resources/ED_EM Supplemental Tool.xlsx";

    public static void main(String[] args) {
        JsonReadService reader = new JsonReadService();
        Map<String, Object> data = reader.readOutputJson();
        String excelPath = args.length > 0 ? args[0] : DEFAULT_EXCEL_PATH;

        System.out.println("=== Dummy Test: Running selection " + LOOP_COUNT + " times ===\n");

        for (int i = 1; i <= LOOP_COUNT; i++) {
            System.out.println("-------- Run " + i + " of " + LOOP_COUNT + " --------");
            SelectionTestRunner.run(data, excelPath);
            System.out.println();
        }

        System.out.println("=== Done: " + LOOP_COUNT + " runs completed ===");
    }
}
