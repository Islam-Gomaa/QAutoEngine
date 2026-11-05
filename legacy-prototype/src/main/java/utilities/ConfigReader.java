package utilities;

import java.io.FileInputStream;
import java.util.Properties;

public class ConfigReader {

    private static final Properties config = new Properties();
    private static final Properties envProperties = new Properties();

    static {
        try {
            // LOAD MAIN CONFIG
            FileInputStream configFile =
                    new FileInputStream("src/main/resources/Configuration.properties");

            config.load(configFile);

            // DETERMINE ENV
            String env = System.getProperty("env");

            if (env == null || env.isEmpty()) {
                env = config.getProperty("environment", "stage");
            }

            System.out.println(" Running on environment: " + env);

            // LOAD ENV FILE
            FileInputStream envFile =
                    new FileInputStream("src/main/resources/" + env + ".properties");

            envProperties.load(envFile);

        } catch (Exception e) {
            throw new RuntimeException("❌ Failed to load configuration files", e);
        }
    }

    //  GET STRING (MANDATORY)
    public static String get(String key) {

        String value = envProperties.getProperty(key);

        if (value == null) {
            value = config.getProperty(key);
        }

        if (value == null) {
            throw new RuntimeException("❌ Key not found: " + key);
        }

        return value;
    }

    //  GET STRING WITH DEFAULT
    public static String get(String key, String defaultValue) {

        String value = envProperties.getProperty(key);

        if (value == null) {
            value = config.getProperty(key);
        }

        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    //  GET INT
    public static int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }


    //  GET BOOLEAN

    public static boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        try {
            return Boolean.parseBoolean(get(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}