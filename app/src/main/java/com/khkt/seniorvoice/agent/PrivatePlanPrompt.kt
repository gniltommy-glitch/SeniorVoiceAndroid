package com.khkt.seniorvoice.agent

import com.khkt.seniorvoice.context.FamilyMemoryData
import com.khkt.seniorvoice.context.GroundingAnalysisResult
import org.json.JSONObject

/** Keep identity substitutions on the phone; neither private server nor API gets the address book. */
data class PrivatePlanPrompt(val text: String, private val originals: Map<String, String>) {
    fun restore(json: String): String {
        // Escape substitutions as JSON string contents, so quotes in names cannot break JSON.
        val pattern = originals.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        if (pattern.isEmpty()) return json
        return Regex(pattern).replace(json) { match ->
            JSONObject.quote(originals.getValue(match.value)).drop(1).dropLast(1)
        }
    }

    companion object {
        fun create(g: GroundingAnalysisResult, memory: FamilyMemoryData): PrivatePlanPrompt {
            val aliases = mutableMapOf<String, String>()
            val originals = linkedMapOf<String, String>()
            fun register(token: String, original: String, variants: List<String>) {
                originals[token] = original
                variants.filter { it.isNotBlank() }.forEach { aliases[it] = token }
            }
            memory.contacts.forEachIndexed { i, c ->
                register("P${i + 1}", c.full_name, c.nicknames + c.full_name)
                register("PHONE${i + 1}", c.phone, listOf(c.phone, c.zalo))
                register("HOME${i + 1}", c.address, listOf(c.address))
            }
            memory.places.forEachIndexed { i, p ->
                register("PLACE${i + 1}", p.full_name, p.nicknames + p.full_name)
                register("ADDRESS${i + 1}", p.address, listOf(p.address))
            }
            register("USER_NAME", memory.senior_profile.full_name, listOf(memory.senior_profile.full_name))
            register("USER_HOME", memory.senior_profile.home_address, listOf(memory.senior_profile.home_address))
            val pattern = aliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            val anonymized = if (pattern.isBlank()) g.normalizedUtterance else
                Regex(pattern, RegexOption.IGNORE_CASE).replace(g.normalizedUtterance) { match ->
                    aliases.entries.first { it.key.equals(match.value, ignoreCase = true) }.value
                }
            // Remove unrecognized phone numbers too. Free-form text is pseudonymized, not
            // guaranteed anonymous; cloud use still requires explicit consent.
            val clean = anonymized.replace(Regex("(?<![0-9])(?:\\+?84|0)[0-9 .()-]{8,14}[0-9](?![0-9])"), "[PHONE_REDACTED]")
            val contacts = memory.contacts.mapIndexedNotNull { i, c ->
                if (c in g.matchedContacts.take(3)) "P${i + 1}: ${c.preferred_channel}" else null
            }.joinToString(", ")
            val places = memory.places.mapIndexedNotNull { i, p ->
                if (p in g.matchedPlaces.take(3)) "PLACE${i + 1}, ADDRESS${i + 1}" else null
            }.joinToString(", ")
            return PrivatePlanPrompt("""
                Ngày mục tiêu do code tính: ${g.resolvedDateTime.solarDate}
                Giờ sự kiện: ${g.resolvedDateTime.time}; giờ báo thức: ${g.alarmTimeHour}:${g.alarmTimeMinute}
                Người nhận hợp lệ: $contacts
                Nơi hợp lệ: $places
                Yêu cầu: $clean
            """.trimIndent(), originals)
        }
    }
}
