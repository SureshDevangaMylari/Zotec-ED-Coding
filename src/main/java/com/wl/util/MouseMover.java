package com.wl.util;

import java.awt.*;
import java.util.concurrent.TimeUnit;

public class MouseMover {
    public static void main(String[] args) throws Exception {

        Robot robot = new Robot();
        int move = 10; // small movement

        while (true) {

            // Get current mouse position
            Point p = MouseInfo.getPointerInfo().getLocation();
            int x = (int) p.getX();
            int y = (int) p.getY();

            // Move a little to avoid screen lock
            robot.mouseMove(x + move, y + move);
            Thread.sleep(300);
            robot.mouseMove(x, y);

            System.out.println("Mouse moved at: " + new java.util.Date());

            // Sleep 5 minutes
            TimeUnit.MINUTES.sleep(2);
        }
    }
}

