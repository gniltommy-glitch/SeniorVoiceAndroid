package com.khkt.seniorvoice.agent

import com.khkt.seniorvoice.context.GroundingAnalysisResult
import java.time.LocalDate
import java.time.LocalTime

/** The model never decides which tools are executable or who receives a call. */
object PlanValidator {
    fun validate(tool: String, p: Map<String, String>, g: GroundingAnalysisResult): Boolean {
        fun nonblank(key: String) = !p[key].isNullOrBlank()
        fun date(key: String) = runCatching { LocalDate.parse(p[key]) }.isSuccess
        fun time(key: String) = runCatching { LocalTime.parse(p[key]) }.isSuccess
        return when (tool) {
            "check_weather" -> date("date") && time("hour") &&
                p["latitude"]?.toDoubleOrNull()?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                p["longitude"]?.toDoubleOrNull()?.let { it.isFinite() && it in -180.0..180.0 } == true
            "set_medication_alarm" -> p["hour"]?.toIntOrNull() in 0..23 && p["minute"]?.toIntOrNull() in 0..59 && nonblank("label")
            "insert_calendar_event" -> date("date") && time("time") && nonblank("title")
            "call_or_message_family" -> {
                val candidates = g.matchedContacts.filter { c ->
                    c.full_name == p["contact_name"] || c.nicknames.any { it == p["nickname"] }
                }
                p["channel"] in setOf("CALL", "ZALO", "SMS") && candidates.size == 1 &&
                    candidates.single().phone.isNotBlank() && p["phone"] == candidates.single().phone &&
                    (p["channel"] == "CALL" || nonblank("message"))
            }
            "navigate_or_book_ride" -> p["mode"] in setOf("MAPS", "RIDE") && nonblank("address") &&
                (g.matchedPlaces.any { it.address == p["address"] } ||
                 g.matchedContacts.any { it.address.isNotBlank() && it.address == p["address"] } ||
                 g.rawUtterance.contains(p["address"].orEmpty(), ignoreCase = true))
            else -> false
        }
    }
}
