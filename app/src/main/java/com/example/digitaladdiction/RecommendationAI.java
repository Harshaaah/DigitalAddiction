package com.example.digitaladdiction;

public class RecommendationAI {

    /**
     * HIERARCHICAL DECISION TREE ALGORITHM
     *
     * Level 1: Biological Risk (Sleep)
     * Level 2: Behavioral Risk (Tolerance / Trend)
     * Level 3: Psychological Risk (App Category)
     */
    public static String generateRecommendation(boolean isLateNight, boolean isTrendUp, String dominantCategory, long totalUsageMs) {

        long hours = totalUsageMs / (1000 * 60 * 60);

        // --- LEVEL 1: BIOLOGICAL SAFETY (Root Node - Highest Priority) ---
        // If sleep is threatened, ignore everything else.
        if (isLateNight) {
            return "🌙 Sleep Hygiene Alert: Usage detected during sleep hours (11 PM - 5 AM). Blue light suppresses melatonin. \n👉 Action: Switch to Audio-only content immediately.";
        }

        // --- LEVEL 2: BEHAVIORAL TREND (Middle Node) ---
        // If addiction is worsening day-by-day.
        if (isTrendUp && hours >= 3) {
            return "📈 Tolerance Build-up: Your usage is higher today than yesterday. Your brain is craving more stimulation. \n👉 Action: Take a 1-hour complete digital detox to reset.";
        }

        // --- LEVEL 3: SEMANTIC CONTEXT (Leaf Nodes) ---
        // Give advice based on WHAT they are doing most today.
        switch (dominantCategory) {
            case "Games":
                if (hours >= 2) {
                    return "🎮 High Adrenaline: Extended gaming keeps the nervous system in 'Fight or Flight' mode. \n👉 Action: Try the 20-20-20 rule to reduce eye strain and rest.";
                }
                break;
            case "Social Media":
                if (hours >= 2) {
                    return "🧠 Doom Scrolling Risk: High social media usage links to anxiety and FOMO. \n👉 Action: Set a strict 15-minute timer before opening social apps.";
                }
                break;
            case "Entertainment":
                if (hours >= 3) {
                    return "📺 Sedentary Behavior: Passive video consumption reduces physical activity. \n👉 Action: Take a 5-minute walk around the room.";
                }
                break;
        }

        // --- DEFAULT (Positive Reinforcement) ---
        if (hours < 2) {
            return "✅ Healthy Balance: You are maintaining a low-risk digital lifestyle. Keep it up!";
        }

        return "ℹ️ Status: Moderate Usage. Keep an eye on your screen time to stay in the healthy zone.";
    }
}
