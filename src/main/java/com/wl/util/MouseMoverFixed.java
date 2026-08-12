package com.wl.util;
import java.awt.*;
import java.util.Timer;
import java.util.TimerTask;

public class MouseMoverFixed {
    public static void main(String[] args) throws AWTException {
        Robot robot = new Robot();
        Timer timer = new Timer();

        TimerTask moveMouseTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    System.out.println("Current mouse: " + p);

                    // Move mouse 10 pixels right and back
                    robot.mouseMove(p.x + 10, p.y);
                    robot.mouseMove(p.x, p.y);

                    System.out.println("Mouse moved at " + java.time.LocalTime.now());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };

        // Schedule every 5 minutes
        timer.schedule(moveMouseTask, 0, 2 * 60 * 1000);
        System.out.println("Mouse mover started...");
    }
}
