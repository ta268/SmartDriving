package com.example.smartdriving;

import android.graphics.Color;

public class ScoreManager {
    private int score = 100;

    public ScoreManager() {
        reset();
    }

    public void reset() {
        score = 100;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = Math.max(0, Math.min(100, score));
    }

    /**
     * Process a drive event and apply corresponding points deduction.
     * Returns the deduction amount.
     */
    public int applyDeduction(String eventKey) {
        int deduction = 0;
        switch (eventKey) {
            case "s_braked":
                deduction = 6;
                break;
            case "s_accelerated":
            case "s_acceleration":
                deduction = 9;
                break;
            case "s_steered":
                deduction = 7;
                break;
            case "waved":
                deduction = 8;
                break;
            case "unstable_speed":
                deduction = 5;
                break;
        }
        score = Math.max(0, score - deduction);
        return deduction;
    }

    /**
     * Helper to get color according to score range.
     * 80+ : Green (#10B981)
     * 65-79: Yellow (#F59E0B)
     * <65 : Red (#EF4444)
     */
    public static int getScoreColor(int score) {
        if (score >= 80) {
            return Color.parseColor("#10B981");
        } else if (score >= 65) {
            return Color.parseColor("#F59E0B");
        } else {
            return Color.parseColor("#EF4444");
        }
    }

    /**
     * Get a human readable label for event keys.
     */
    public static String getEventLabel(String eventKey) {
        switch (eventKey) {
            case "s_braked":
                return "急ブレーキ";
            case "s_accelerated":
            case "s_acceleration":
                return "急加速";
            case "s_steered":
                return "急ハンドル";
            case "waved":
                return "ふらつき";
            case "unstable_speed":
                return "速度ふらつき";
            default:
                return "不明なイベント";
        }
    }
}
