package com.wl.util;

import java.awt.event.KeyEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sikuli.api.robot.Keyboard;
import org.sikuli.api.robot.desktop.DesktopKeyboard;
import org.sikuli.hotkey.Keys;
import org.sikuli.script.FindFailed;
import org.sikuli.script.Screen;

public class SikuliUtility {
    public static final Logger logger = LogManager.getLogger(SikuliUtility.class);

    static Keyboard keyboard = new DesktopKeyboard();

    static Screen screen = new Screen();
 
    
    public void click(String path, int delay) {
	while (true) {
	    try {
		screen.click(path);
		break;
	    } catch (FindFailed e) {
		logger.info("not found " + path);
//			e.printStackTrace();
	    }

	}
    }

    
    public void backspace() {
	int keyCounter = 0;
	while (true) {
	    keyCounter++;
	    keyboard.keyDown(Keys.BACKSPACE);
	    keyboard.keyUp(Keys.BACKSPACE);
	    if (keyCounter == 50)
		break;
	}

    }

    
    public void select(String path) {

    }

    
    public void entertext(String text) {
	screen.type(text);
    }

    
    public void capture(int x, int y, int w, int h) {

    }

    
    public void preessAnyKey(Keys key, int delay) {

    }

    
    public void pressEnter() {
	keyboard.keyDown(KeyEvent.VK_ENTER);
	keyboard.keyUp(KeyEvent.VK_ENTER);

    }

    
    public void pressTab() {
	keyboard.keyDown(KeyEvent.VK_TAB);
	keyboard.keyUp(KeyEvent.VK_TAB);
    }

    
    public void pressPrintscreen() {

    }

    
    public void pressCAPS() {

    }

    
    public void pressShift() {

    }

    
    public void pressControl() {

    }

    
    public void pressShiftControl() {

    }

    
    public void clickNoDelay(String path) {

    }

    
    public void pressKeyWithCount(String path, int count) {

    }

    
    public void printpage() {
	keyboard.keyDown(KeyEvent.VK_CONTROL);
	keyboard.keyDown(KeyEvent.VK_P);
	keyboard.keyUp(KeyEvent.VK_P);
	keyboard.keyUp(KeyEvent.VK_CONTROL);

    }

    
    public void waituntilLoad(String path, int count) {
	try {
	    screen.wait(path, count);
	} catch (FindFailed e) {

	    e.printStackTrace();
	}

    }

    
    public boolean checkElement(String path) {
	if (screen.exists(path) != null) {
	    return true;
	} else {
	    return false;
	}

    }

    
    public void waituntilLoad() {
	// TODO Auto-generated method stub

    }

}
