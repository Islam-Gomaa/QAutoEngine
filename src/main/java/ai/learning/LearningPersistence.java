package ai.learning;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class LearningPersistence {

    private static final String FILE = "learning_v6.dat";

    // ===================================================
    // 🧠 DATA MODEL (V6)
    // ===================================================
    public static class LearningData implements Serializable {

        public Map<String, Double> qTable = new HashMap<>();
        public Map<String, Double> rewards = new HashMap<>();

        public long timestamp;

        public LearningData() {
            this.timestamp = System.currentTimeMillis();
        }
    }

    // ===================================================
    // 💾 SAVE (SAFE)
    // ===================================================
    public static void save(LearningData data) {

        if (data == null) return;

        File tmp = new File(FILE + ".tmp");

        try (ObjectOutputStream out =
                     new ObjectOutputStream(new FileOutputStream(tmp))) {

            out.writeObject(data);
            out.flush();

            // 🔥 safe replace
            File main = new File(FILE);
            if (main.exists()) main.delete();

            tmp.renameTo(main);

            System.out.println("💾 Learning saved");

        } catch (Exception e) {

            System.out.println("❌ Save failed: " + e.getMessage());
        }
    }

    // ===================================================
    // 📂 LOAD (SAFE)
    // ===================================================
    public static LearningData load() {

        File file = new File(FILE);

        if (!file.exists()) return new LearningData();

        try (ObjectInputStream in =
                     new ObjectInputStream(new FileInputStream(file))) {

            Object obj = in.readObject();

            if (obj instanceof LearningData) {
                System.out.println("📂 Learning loaded");
                return (LearningData) obj;
            }

        } catch (Exception e) {

            System.out.println("⚠️ Load failed, starting fresh");
        }

        return new LearningData();
    }

    // ===================================================
    // 🔄 AUTO SAVE HOOK
    // ===================================================
    public static void autoSave(Map<String, Double> qTable,
                                Map<String, Double> rewards) {

        LearningData data = new LearningData();
        data.qTable.putAll(qTable);
        data.rewards.putAll(rewards);

        save(data);
    }
}