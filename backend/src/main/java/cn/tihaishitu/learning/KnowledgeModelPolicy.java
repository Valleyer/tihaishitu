package cn.tihaishitu.learning;

/** Versioned V1 policy; changes to these semantics require a new model version. */
public final class KnowledgeModelPolicy {
    private KnowledgeModelPolicy() {}
    public static final String MODEL_VERSION = "v1";
    public static final double MIN_MASTERY = 0, MAX_MASTERY = 100;
    public static final double LEARNING_THRESHOLD = 40, READY_THRESHOLD = 70, PROFICIENT_THRESHOLD = 85;
    public static final double INITIAL_STABILITY = 1, MIN_STABILITY = .5, MAX_STABILITY = 365;
    public static final int INITIAL_DIFFICULTY = 2, MIN_DIFFICULTY = 1, MAX_DIFFICULTY = 5;
    public static final int PROMOTION_STREAK = 2;
    public static final double BASE_ALPHA = .20, MIN_ALPHA = .05, MAX_ALPHA = .30;
    public static final double AUTOMATIC_FACTOR = 1, SELF_FACTOR = .85;
    public static final double NORMAL_FACTOR = 1, TRAINING_FACTOR = .55;
    public static final double AUTOMATIC_CORRECT_QUALITY = 1, SELF_CORRECT_QUALITY = .90,
            PARTIAL_QUALITY = .50, WRONG_QUALITY = 0;
    public static final double POSITIVE_QUALITY = .75, NEGATIVE_QUALITY = .25;
    public static final double POSITIVE_BASE = .75, NEGATIVE_BASE = 1.35, DIFFICULTY_STEP = .10;
    public static final double MIN_SPACING = .25, MAX_SPACING = 1, SPACING_STABILITY_FLOOR = 1;
    public static final double SUCCESS_GAIN_BASE = .75, SUCCESS_GAIN_STEP = .15;
    public static final double PARTIAL_RETENTION = .90, WRONG_RETENTION_BASE = .45, WRONG_RETENTION_STEP = .05;
    public static final double SECONDS_PER_DAY = 86400;

    public static double clamp(double min, double max, double value) { return Math.max(min, Math.min(max, value)); }
    public static double round(double value) { return Math.round(value * 100d) / 100d; }
}
