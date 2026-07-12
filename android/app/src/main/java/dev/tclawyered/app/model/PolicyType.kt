package dev.tclawyered.app.model

/**
 * The two policy kinds the hive recognises. Wire values MUST match the
 * extension + backend enum exactly ("privacy_policy" | "terms_of_service").
 */
enum class PolicyType(val wire: String) {
    PRIVACY("privacy_policy"),
    TERMS("terms_of_service");

    companion object {
        fun fromWire(value: String?): PolicyType? = entries.firstOrNull { it.wire == value }

        /** Cheap heuristic when the source only gives us text/URL, no explicit type. */
        fun guess(text: String, url: String?): PolicyType {
            val hay = ((url ?: "") + " " + text.take(400)).lowercase()
            val privacyHits = listOf("privacy", "data policy", "personal data", "cookie")
            val termsHits = listOf("terms of service", "terms of use", "terms and conditions", "eula", "user agreement")
            val p = privacyHits.count { hay.contains(it) }
            val t = termsHits.count { hay.contains(it) }
            return if (p >= t) PRIVACY else TERMS
        }
    }
}
