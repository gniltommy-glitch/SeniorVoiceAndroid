import com.khkt.seniorvoice.agent.PlanValidator
import com.khkt.seniorvoice.agent.PrivatePlanPrompt
import com.khkt.seniorvoice.context.*
import java.time.LocalDate
import java.time.LocalTime

/** Standalone JVM smoke checks; no Android service, real provider or user data required. */
fun main() {
    val contact = FamilyContact("c1", listOf("con Thảo"), "Người mẫu A", "con", "0000000000", "0000000000")
    val day = LocalDate.of(2026, 11, 11)
    val g = GroundingAnalysisResult("gọi con Thảo", "gọi con Thảo", ResolvedDateTime(day,
        LunarDate(3, 10, 2026), LocalTime.of(7, 30), "", ""), 7, 30,
        listOf(contact), emptyList(), emptyList(), emptyList(), "")
    val call = mapOf("contact_name" to contact.full_name, "phone" to contact.phone, "channel" to "CALL")
    check(PlanValidator.validate("call_or_message_family", call, g))
    check(!PlanValidator.validate("call_or_message_family", call + ("phone" to "1234567890"), g))
    check(!PlanValidator.validate("call_or_message_family", call + ("channel" to "DELETE"), g))
    check(!PlanValidator.validate("call_or_message_family", call, g.copy(matchedContacts = listOf(contact, contact.copy(id = "c2")))))
    check(!PlanValidator.validate("delete_all", emptyMap(), g))
    val alarm = mapOf("hour" to "7", "minute" to "30", "label" to "Nhắc uống nước")
    check(PlanValidator.validate("set_medication_alarm", alarm, g))
    check(!PlanValidator.validate("set_medication_alarm", alarm + ("hour" to "24"), g))
    check(!PlanValidator.validate("set_medication_alarm", alarm + ("minute" to "60"), g))
    val calendar = mapOf("title" to "Hẹn", "date" to "2026-11-11", "time" to "07:30")
    check(PlanValidator.validate("insert_calendar_event", calendar, g))
    check(!PlanValidator.validate("insert_calendar_event", calendar + ("date" to "2026-02-30"), g))
    val weather = mapOf("date" to "2026-11-11", "hour" to "07:30", "latitude" to "10", "longitude" to "106")
    check(PlanValidator.validate("check_weather", weather, g))
    check(!PlanValidator.validate("check_weather", weather + ("latitude" to "NaN"), g))
    check(!PlanValidator.validate("navigate_or_book_ride", mapOf("mode" to "MAPS", "address" to "Địa chỉ bịa"), g))
    val prompt = PrivatePlanPrompt.create(g, FamilyMemoryData(contacts = mutableListOf(contact)))
    check(!prompt.text.contains(contact.full_name))
    check(!prompt.text.contains(contact.phone))
    check(!prompt.text.contains("con Thảo"))
    check(prompt.text.contains("P1"))
    println("17 agent validation/privacy checks passed")
}
