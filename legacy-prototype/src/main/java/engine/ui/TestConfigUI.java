package engine.ui;

import javax.swing.*;

public class TestConfigUI {

    public static class Config {
        public String baseUrl;
        public String browser;
        public String username;
        public String password;
    }

    public static Config show() {

        JTextField urlField = new JTextField("https://example.com");
        JTextField browserField = new JTextField("chrome");
        JTextField userField = new JTextField();
        JPasswordField passField = new JPasswordField();

        Object[] message = {
                "Base URL:", urlField,
                "Browser:", browserField,
                "Username:", userField,
                "Password:", passField
        };

        int option = JOptionPane.showConfirmDialog(
                null,
                message,
                "🚀 Test Configuration",
                JOptionPane.OK_CANCEL_OPTION
        );

        if (option != JOptionPane.OK_OPTION) {
            System.exit(0);
        }

        Config config = new Config();
        config.baseUrl = urlField.getText();
        config.browser = browserField.getText();
        config.username = userField.getText();
        config.password = new String(passField.getPassword());

        return config;
    }
}