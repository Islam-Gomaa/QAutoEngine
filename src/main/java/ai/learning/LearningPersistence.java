package ai.learning;

import java.io.*;
import java.util.Map;

public class LearningPersistence {

    private static final String FILE = "learning.dat";

    public static void save(Map<String, Double> qTable) {

        try (ObjectOutputStream out =
                     new ObjectOutputStream(new FileOutputStream(FILE))) {

            out.writeObject(qTable);

        } catch (Exception ignored) {}
    }

    public static Map<String, Double> load() {

        try (ObjectInputStream in =
                     new ObjectInputStream(new FileInputStream(FILE))) {

            return (Map<String, Double>) in.readObject();

        } catch (Exception e) {
            return null;
        }
    }
}