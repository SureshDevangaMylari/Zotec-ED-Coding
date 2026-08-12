package com.wl.util;

import org.sikuli.script.*;
import java.time.LocalDateTime;
import java.lang.reflect.Method;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PopUp utility class for displaying agent status messages using DTO pattern
 */
public class PopUp {
    
    /**
     * Status type enum for agent messages
     */
    public enum StatusType {
        SUCCESS,
        ERROR,
        WARNING,
        INFO
    }
    
    /**
     * DTO class for agent status messages
     */
    public static class AgentStatusDTO {
        private StatusType statusType;
        private String message;
        private int durationSeconds;
        private LocalDateTime timestamp;
        private String agentName;
        
        public AgentStatusDTO() {
            this.timestamp = LocalDateTime.now();
            this.durationSeconds = 3;
        }
        
        public AgentStatusDTO(StatusType statusType, String message) {
            this();
            this.statusType = statusType;
            this.message = message;
        }
        
        public AgentStatusDTO(StatusType statusType, String message, int durationSeconds) {
            this(statusType, message);
            this.durationSeconds = durationSeconds;
        }
        
        public AgentStatusDTO(StatusType statusType, String message, int durationSeconds, String agentName) {
            this(statusType, message, durationSeconds);
            this.agentName = agentName;
        }
        
        // Getters and Setters
        public StatusType getStatusType() {
            return statusType;
        }
        
        public void setStatusType(StatusType statusType) {
            this.statusType = statusType;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
        
        public int getDurationSeconds() {
            return durationSeconds;
        }
        
        public void setDurationSeconds(int durationSeconds) {
            this.durationSeconds = durationSeconds;
        }
        
        public LocalDateTime getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
        
        public String getAgentName() {
            return agentName;
        }
        
        public void setAgentName(String agentName) {
            this.agentName = agentName;
        }
        
        /**
         * Formats the message with status type prefix
         */
        public String getFormattedMessage() {
            String prefix = "";
            switch (statusType) {
                case SUCCESS:
                    prefix = "✓ SUCCESS";
                    break;
                case ERROR:
                    prefix = "✗ ERROR";
                    break;
                case WARNING:
                    prefix = "⚠ WARNING";
                    break;
                case INFO:
                    prefix = "ℹ INFO";
                    break;
            }
            
            String agentPrefix = (agentName != null && !agentName.isEmpty()) 
                ? "[" + agentName + "] " 
                : "";
                
            return String.format("%s: %s%s", prefix, agentPrefix, message);
        }
    }
    
    // Popup configuration constants
    private static final int DEFAULT_WIDTH = 400;
    private static final int DEFAULT_HEIGHT = 100;
    private static final int MARGIN = 10;
    
    // Snackbar configuration constants
    private static final int SNACKBAR_WIDTH = 350;
    private static final int SNACKBAR_HEIGHT = 60;
    private static final int SNACKBAR_MARGIN = 20;
    private static final int SNACKBAR_DEFAULT_DURATION = 3000; // 3 seconds in milliseconds
    
    // Current snackbar instance for on/off control
    private static volatile SnackbarWindow currentSnackbar = null;
    
    /**
     * Displays a popup status message using the provided DTO
     * 
     * @param statusDTO The DTO containing status message information
     */
    public static void showStatusMessage(AgentStatusDTO statusDTO) {
        if (statusDTO == null || statusDTO.getMessage() == null) {
            System.err.println("Invalid status DTO: null or missing message");
            return;
        }
        
        try {
            Screen screen = new Screen();
            int screenH = screen.getH();
            
            // Calculate popup position (bottom-left corner)
            int x = MARGIN;
            int y = screenH - DEFAULT_HEIGHT - MARGIN;
            
            // Create region for popup
            Region popupRegion = new Region(x, y, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            popupRegion.setROI(x, y, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            
            // Format the message with status type
            String formattedMessage = statusDTO.getFormattedMessage();
            
            // Get duration from DTO (default to 3 seconds if not set)
            int duration = statusDTO.getDurationSeconds() > 0 
                ? statusDTO.getDurationSeconds() 
                : 3;
            
            // Display the popup using reflection (Sikuli's popup method)
            try {
                Method popupMethod = popupRegion.getClass().getMethod("popup", String.class, int.class);
                popupMethod.invoke(popupRegion, formattedMessage, duration);
            } catch (NoSuchMethodException e) {
                // If popup method doesn't exist, try using Screen's popup method
                try {
                    Method screenPopupMethod = screen.getClass().getMethod("popup", String.class, int.class);
                    screenPopupMethod.invoke(screen, formattedMessage, duration);
                } catch (Exception ex) {
                    System.err.println("Failed to invoke popup method: " + ex.getMessage());
                    System.err.println("Popup message: " + formattedMessage);
                }
            } catch (Exception e) {
                System.err.println("Failed to invoke popup method: " + e.getMessage());
                System.err.println("Popup message: " + formattedMessage);
            }
            
            // Log the status message
            System.out.println("Agent Status: " + formattedMessage);
            
        } catch (Exception e) {
            System.err.println("Failed to display status popup: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Convenience method to show a success message
     */
    public static void showSuccess(String message) {
        AgentStatusDTO dto = new AgentStatusDTO(StatusType.SUCCESS, message);
        showStatusMessage(dto);
    }
    
    /**
     * Convenience method to show an error message
     */
    public static void showError(String message) {
        AgentStatusDTO dto = new AgentStatusDTO(StatusType.ERROR, message);
        showStatusMessage(dto);
    }
    
    /**
     * Convenience method to show a warning message
     */
    public static void showWarning(String message) {
        AgentStatusDTO dto = new AgentStatusDTO(StatusType.WARNING, message);
        showStatusMessage(dto);
    }
    
    /**
     * Convenience method to show an info message
     */
    public static void showInfo(String message) {
        AgentStatusDTO dto = new AgentStatusDTO(StatusType.INFO, message);
        showStatusMessage(dto);
    }
    
    /**
     * Shows status message with agent name and custom duration
     */
    public static void showStatusMessage(String agentName, StatusType statusType, 
                                        String message, int durationSeconds) {
        AgentStatusDTO dto = new AgentStatusDTO(statusType, message, durationSeconds, agentName);
        showStatusMessage(dto);
    }
    
    /**
     * Show a Yes/No popup dialog and store the result in NotepadService.
     * If user clicks Yes, stores "yes" in NotepadService messages.
     * If user clicks No, stores "no" in NotepadService messages.
     * 
     * @param message The message/question to display in the popup
     * @param title The title of the popup dialog
     * @return true if user clicked Yes, false if user clicked No or closed the dialog
     */
    public static boolean showYesNoPopupAndStore(String message, String title) {
        if (message == null || message.trim().isEmpty()) {
            message = "Do you want to continue?";
        }
        if (title == null || title.trim().isEmpty()) {
            title = "Confirmation";
        }
        
        // Show Yes/No dialog
        int response = JOptionPane.showConfirmDialog(
            null,
            message,
            title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        
        // Store result in NotepadService based on user choice
        if (response == JOptionPane.YES_OPTION) {
            System.out.println("User selected Yes - stored 'yes' in NotepadService");
            return true;
        } else {
            System.out.println("User selected No - stored 'no' in NotepadService");
            return false;
        }
    }
    
    /**
     * Show a Yes/No popup dialog and store the result in NotepadService (with default title).
     * 
     * @param message The message/question to display in the popup
     * @return true if user clicked Yes, false if user clicked No or closed the dialog
     */
    public static boolean showYesNoPopupAndStore(String message) {
        return showYesNoPopupAndStore(message, "Confirmation");
    }
    
    /**
     * Show a Yes/No popup dialog, store the result in NotepadService, and print it immediately.
     * 
     * @param message The message/question to display in the popup
     * @param title The title of the popup dialog
     * @return true if user clicked Yes, false if user clicked No or closed the dialog
     */
     
    
    /**
     * Show a Yes/No popup dialog, store the result in NotepadService, and print it immediately (with default title).
     * 
     * @param message The message/question to display in the popup
     * @return true if user clicked Yes, false if user clicked No or closed the dialog
     */
    
    
    /**
     * Shows a snackbar notification at the bottom of the screen
     * 
     * @param message The message to display
     * @param statusType The status type (determines color)
     * @param durationMs Duration in milliseconds (0 = permanent until dismissed)
     */
    private static void showSnackbar(String message, StatusType statusType, int durationMs) {
        SwingUtilities.invokeLater(() -> {
            // Dismiss existing snackbar if any
            dismissSnackbar();
            SnackbarWindow snackbar = new SnackbarWindow(message, statusType, durationMs);
            if (durationMs <= 0) {
                // Store reference for permanent snackbars
                currentSnackbar = snackbar;
            }
            snackbar.show();
        });
    }
    
    /**
     * Shows a snackbar that stays visible until explicitly dismissed
     * 
     * @param message The message to display
     * @param statusType The status type (determines color)
     */
    public static void showSnackbarOn(String message, StatusType statusType) {
        showSnackbar(message, statusType, 0); // 0 = permanent
    }
    
    /**
     * Dismisses the current snackbar if one is visible
     */
    public static void showSnackbarOff() {
        SwingUtilities.invokeLater(() -> {
            dismissSnackbar();
        });
    }
    
    /**
     * Internal method to dismiss the current snackbar
     */
    private static void dismissSnackbar() {
        if (currentSnackbar != null) {
            currentSnackbar.close();
            currentSnackbar = null;
        }
    }
    
    /**
     * Shows a snackbar with an action button
     * 
     * @param message The message to display
     * @param statusType The status type (determines color)
     * @param actionText The text for the action button
     * @param actionListener The action to perform when button is clicked
     * @param durationMs Duration in milliseconds (0 = permanent until dismissed)
     */
    private static void showSnackbarWithAction(String message, StatusType statusType, 
                                              String actionText, Runnable actionListener, int durationMs) {
        SwingUtilities.invokeLater(() -> {
            // Dismiss existing snackbar if any
            dismissSnackbar();
            SnackbarWindow snackbar = new SnackbarWindow(message, statusType, durationMs, actionText, actionListener);
            if (durationMs <= 0) {
                // Store reference for permanent snackbars
                currentSnackbar = snackbar;
            }
            snackbar.show();
        });
    }
    
    /**
     * Shows a permanent snackbar with an action button that stays until dismissed
     * 
     * @param message The message to display
     * @param statusType The status type (determines color)
     * @param actionText The text for the action button
     * @param actionListener The action to perform when button is clicked
     */
    private static void showSnackbarOnWithAction(String message, StatusType statusType, 
                                               String actionText, Runnable actionListener) {
        showSnackbarWithAction(message, statusType, actionText, actionListener, 0); // 0 = permanent
    }
    
    /**
     * Convenience method to show a success snackbar
     */
    private static void showSnackbarSuccess(String message) {
        showSnackbar(message, StatusType.SUCCESS, SNACKBAR_DEFAULT_DURATION);
    }
    
    /**
     * Convenience method to show an error snackbar
     */
    private static void showSnackbarError(String message) {
        showSnackbar(message, StatusType.ERROR, SNACKBAR_DEFAULT_DURATION);
    }
    
    /**
     * Convenience method to show a warning snackbar
     */
    private static void showSnackbarWarning(String message) {
        showSnackbar(message, StatusType.WARNING, SNACKBAR_DEFAULT_DURATION);
    }
    
    /**
     * Convenience method to show an info snackbar
     */
    private static void showSnackbarInfo(String message) {
        showSnackbar(message, StatusType.INFO, SNACKBAR_DEFAULT_DURATION);
    }
    
    /**
     * Convenience methods for permanent snackbars (on/off feature)
     */
    private static void showSnackbarOnSuccess(String message) {
        showSnackbarOn(message, StatusType.SUCCESS);
    }
    
    private static void showSnackbarOnError(String message) {
        showSnackbarOn(message, StatusType.ERROR);
    }
    
    private static void showSnackbarOnWarning(String message) {
        showSnackbarOn(message, StatusType.WARNING);
    }
    
    private static void showSnackbarOnInfo(String message) {
        showSnackbarOn(message, StatusType.INFO);
    }
    
    /**
     * Snackbar window class - creates a modern snackbar-style notification
     */
    private static class SnackbarWindow {
        private JWindow window;
        private JLabel messageLabel;
        private JButton actionButton;
        private JButton closeButton;
        private Timer slideTimer;
        private Timer autoCloseTimer;
        private AtomicBoolean isClosing = new AtomicBoolean(false);
        private int targetY;
        private int startY;
        
        public SnackbarWindow(String message, StatusType statusType, int durationMs) {
            this(message, statusType, durationMs, null, null);
        }
        
        public SnackbarWindow(String message, StatusType statusType, int durationMs, 
                             String actionText, Runnable actionListener) {
            initializeWindow();
            createContent(message, statusType, actionText, actionListener);
            setupAnimation();
            if (durationMs > 0) {
                setupAutoClose(durationMs);
            }
        }
        
        private void initializeWindow() {
            window = new JWindow();
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            window.setLayout(new BorderLayout());
            
            // Get screen dimensions
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = ge.getDefaultScreenDevice();
            Rectangle bounds = gd.getDefaultConfiguration().getBounds();
            
            // Position at bottom center
            int x = bounds.x + (bounds.width - SNACKBAR_WIDTH) / 2;
            targetY = bounds.y + bounds.height - SNACKBAR_HEIGHT - SNACKBAR_MARGIN;
            startY = bounds.y + bounds.height; // Start off-screen
            
            window.setSize(SNACKBAR_WIDTH, SNACKBAR_HEIGHT);
            window.setLocation(x, startY);
        }
        
        private void createContent(String message, StatusType statusType, 
                                  String actionText, Runnable actionListener) {
            // Main panel with rounded corners effect
            JPanel mainPanel = new JPanel(new BorderLayout(10, 0));
            mainPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 12));
            
            // Set background color based on status type
            Color bgColor = getStatusColor(statusType);
            mainPanel.setBackground(bgColor);
            
            // Message label
            messageLabel = new JLabel("<html><body style='width: " + (SNACKBAR_WIDTH - 100) + "px;'>" 
                                     + escapeHtml(message) + "</body></html>");
            messageLabel.setForeground(Color.WHITE);
            messageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
            mainPanel.add(messageLabel, BorderLayout.CENTER);
            
            // Button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
            buttonPanel.setOpaque(false);
            
            // Action button (if provided)
            if (actionText != null && actionListener != null) {
                actionButton = new JButton(actionText);
                actionButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
                actionButton.setForeground(Color.WHITE);
                actionButton.setBackground(new Color(0, 0, 0, 0)); // Transparent
                actionButton.setBorderPainted(false);
                actionButton.setFocusPainted(false);
                actionButton.setContentAreaFilled(false);
                actionButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
                actionButton.addActionListener(e -> {
                    actionListener.run();
                    close();
                });
                buttonPanel.add(actionButton);
            }
            
            // Close button
            closeButton = new JButton("X");
            closeButton.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            closeButton.setForeground(Color.WHITE);
            closeButton.setBackground(new Color(0, 0, 0, 0));
            closeButton.setBorderPainted(false);
            closeButton.setFocusPainted(false);
            closeButton.setContentAreaFilled(false);
            closeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
            closeButton.addActionListener(e -> close());
            buttonPanel.add(closeButton);
            
            mainPanel.add(buttonPanel, BorderLayout.EAST);
            window.add(mainPanel);
        }
        
        private Color getStatusColor(StatusType statusType) {
            switch (statusType) {
                case SUCCESS:
                    return new Color(76, 175, 80); // Green
                case ERROR:
                    return new Color(244, 67, 54); // Red
                case WARNING:
                    return new Color(255, 152, 0); // Orange
                case INFO:
                    return new Color(60, 150, 243); // Blue
                default:
                    return new Color(97, 97, 97); // Gray
            }
        }
        
        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\"", "&quot;")
                      .replace("'", "&#39;");
        }
        
        private void setupAnimation() {
            final int steps = 20;
            final int delay = 15; // milliseconds per step
            final int totalDistance = startY - targetY;
            
            slideTimer = new Timer(delay, new ActionListener() {
                private int step = 0;
                
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (step >= steps) {
                        slideTimer.stop();
                        return;
                    }
                    
                    double progress = (double) step / steps;
                    // Easing function (ease-out)
                    progress = 1 - Math.pow(1 - progress, 3);
                    
                    int currentY = (int) (startY - (totalDistance * progress));
                    window.setLocation(window.getX(), currentY);
                    step++;
                }
            });
        }
        
        private void setupAutoClose(int durationMs) {
            autoCloseTimer = new Timer(durationMs, e -> close());
            autoCloseTimer.setRepeats(false);
        }
        
        public void show() {
            window.setVisible(true);
            slideTimer.start();
            if (autoCloseTimer != null) {
                autoCloseTimer.start();
            }
        }
        
        private void close() {
            if (isClosing.getAndSet(true)) {
                return; // Already closing
            }
            
            // Clear the reference if this is the current snackbar
            if (currentSnackbar == this) {
                currentSnackbar = null;
            }
            
            if (autoCloseTimer != null) {
                autoCloseTimer.stop();
            }
            
            // Slide out animation
            final int steps = 15;
            final int delay = 15;
            final int startYPos = window.getY();
            final int endY = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds().height;
            final int totalDistance = endY - startYPos;
            
            final Timer[] closeTimerRef = new Timer[1];
            closeTimerRef[0] = new Timer(delay, new ActionListener() {
                private int step = 0;
                
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (step >= steps) {
                        closeTimerRef[0].stop();
                        window.dispose();
                        return;
                    }
                    
                    double progress = (double) step / steps;
                    progress = Math.pow(progress, 2); // Ease-in
                    
                    int currentY = (int) (startYPos + (totalDistance * progress));
                    window.setLocation(window.getX(), currentY);
                    step++;
                }
            });
            
            closeTimerRef[0].start();
        }
    }
    
    /**
     * Test main method
     */
    public static void main(String[] args) {
        // Example 1: Using DTO directly
        AgentStatusDTO statusDTO = new AgentStatusDTO(
            StatusType.SUCCESS,
            "Task completed successfully",
            5,
            "VisionAgent"
        );
        showStatusMessage(statusDTO);
        
        // Example 2: Using convenience methods
        // showSuccess("Operation completed");
        // showError("Failed to process request");
        // showWarning("Low memory detected");
        // showInfo("Processing in progress...");
        
        // Example 3: Snackbar examples with auto-dismiss
        try {
            Thread.sleep(2000);
            showSnackbarSuccess("Operation completed successfully!");
            
            Thread.sleep(3000);
            showSnackbarError("An error occurred while processing");
            
            Thread.sleep(3000);
            showSnackbarWarning("Low memory detected");
            
            Thread.sleep(3000);
            showSnackbarInfo("Processing in progress...");
            
            Thread.sleep(3000);
            showSnackbarWithAction("File saved successfully", StatusType.SUCCESS, 
                                  "UNDO", () -> System.out.println("Undo clicked"), 5000);
            
            Thread.sleep(6000);
            
            // Example 4: On/Off feature - permanent snackbar
            System.out.println("Showing permanent snackbar...");
            showSnackbarOn("This snackbar will stay until you call showSnackbarOff()", StatusType.INFO);
            
            Thread.sleep(5000);
            System.out.println("Dismissing snackbar...");
            showSnackbarOff();
            
            Thread.sleep(2000);
            System.out.println("Showing another permanent snackbar...");
            showSnackbarOnSuccess("Success! This stays visible");
            
            Thread.sleep(3000);
            System.out.println("Turning off snackbar...");
            showSnackbarOff();
            
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}

