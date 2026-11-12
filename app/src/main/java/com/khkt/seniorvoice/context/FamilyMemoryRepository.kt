package com.khkt.seniorvoice.context

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.khkt.seniorvoice.agent.BenchmarkQueryItem
import com.khkt.seniorvoice.agent.GroundingItem
import java.time.LocalDate

data class SeniorProfile(
    val full_name: String = "",
    val preferred_title: String = "Bác",
    val age: Int = 70,
    val home_address: String = "TP. Hồ Chí Minh",
    val latitude: Double = 10.7626,
    val longitude: Double = 106.6602,
    val medical_notes: String = ""
)

data class FamilyContact(
    val id: String,
    val nicknames: List<String>,
    val full_name: String,
    val relationship: String,
    val phone: String,
    val zalo: String,
    val preferred_channel: String = "ZALO",
    val address: String = "",
    val notes: String = ""
)

data class FamiliarPlace(
    val id: String,
    val nicknames: List<String>,
    val full_name: String,
    val address: String,
    val latitude: Double = 10.7626,
    val longitude: Double = 106.6602,
    val category: String = "PLACE",
    val reminder_note: String = ""
)

data class MedicationSchedule(
    val id: String,
    val keywords: List<String>,
    val name: String,
    val default_hour: Int,
    val default_minute: Int,
    val dosage: String
)

data class FamilyMemoryData(
    var senior_profile: SeniorProfile = SeniorProfile(),
    val contacts: MutableList<FamilyContact> = mutableListOf(),
    val places: MutableList<FamiliarPlace> = mutableListOf(),
    val medications: MutableList<MedicationSchedule> = mutableListOf(),
    val dialect_dictionary: MutableMap<String, String> = mutableMapOf()
)

data class GroundingAnalysisResult(
    val rawUtterance: String,
    val normalizedUtterance: String,
    val resolvedDateTime: ResolvedDateTime,
    val alarmTimeHour: Int,
    val alarmTimeMinute: Int,
    val matchedContacts: List<FamilyContact>,
    val matchedPlaces: List<FamiliarPlace>,
    val matchedMedications: List<MedicationSchedule>,
    val groundingItems: List<GroundingItem>,
    val promptContextBlock: String
)

class FamilyMemoryRepository(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("senior_voice_family_prefs", Context.MODE_PRIVATE)
    private var memoryData: FamilyMemoryData

    init {
        memoryData = loadFamilyMemory()
    }

    fun isFirstSetupCompleted(): Boolean {
        return prefs.getBoolean("first_setup_completed_v1", false)
    }

    fun setFirstSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean("first_setup_completed_v1", completed).apply()
    }

    private fun loadDefaultAssetMemory(): FamilyMemoryData {
        return runCatching {
            val jsonStr = context.assets.open("family_memory.json").bufferedReader().use { it.readText() }
            gson.fromJson(jsonStr, FamilyMemoryData::class.java)
        }.getOrElse {
            FamilyMemoryData()
        }
    }

    private fun loadFamilyMemory(): FamilyMemoryData {
        val customJson = prefs.getString("custom_family_memory_json", null)
        if (!customJson.isNullOrBlank()) {
            runCatching {
                return gson.fromJson(customJson, FamilyMemoryData::class.java)
            }
        }
        return loadDefaultAssetMemory()
    }

    private fun saveMemory() {
        prefs.edit().putString("custom_family_memory_json", gson.toJson(memoryData)).apply()
    }

    fun getMemoryData(): FamilyMemoryData = memoryData

    /**
     * Lưu dữ liệu từ màn hình Thiết lập lần đầu (Setup 1 lần khi mới cài app)
     */
    fun saveInitialSetup(
        preferredTitle: String,
        fullName: String,
        homeAddress: String,
        contactNickname: String,
        contactPhone: String,
        favoritePlaceName: String,
        favoritePlaceAddress: String,
        medicationName: String,
        medicationHour: Int,
        medicationMinute: Int
    ) {
        val defaultAssets = loadDefaultAssetMemory()
        val contactsList = mutableListOf<FamilyContact>()
        if (contactNickname.isNotBlank() && contactPhone.isNotBlank()) {
            val nicks = contactNickname.split(",").map { it.trim() }.filter { it.isNotBlank() }
            contactsList.add(
                FamilyContact(
                    id = "setup_contact_1",
                    nicknames = nicks,
                    full_name = nicks.firstOrNull() ?: contactNickname.trim(),
                    relationship = "Người thân",
                    phone = contactPhone.trim(),
                    zalo = contactPhone.trim(),
                    preferred_channel = "ZALO"
                )
            )
        }

        val placesList = mutableListOf<FamiliarPlace>()
        if (favoritePlaceName.isNotBlank()) {
            val nicks = favoritePlaceName.split(",").map { it.trim() }.filter { it.isNotBlank() }
            placesList.add(
                FamiliarPlace(
                    id = "setup_place_1",
                    nicknames = nicks,
                    full_name = nicks.firstOrNull() ?: favoritePlaceName.trim(),
                    address = favoritePlaceAddress.trim().ifBlank { favoritePlaceName.trim() }
                )
            )
        }

        val medsList = mutableListOf<MedicationSchedule>()
        if (medicationName.isNotBlank()) {
            medsList.add(
                MedicationSchedule(
                    id = "setup_med_1",
                    keywords = listOf(medicationName.trim(), "uống thuốc"),
                    name = medicationName.trim(),
                    default_hour = medicationHour.coerceIn(0, 23),
                    default_minute = medicationMinute.coerceIn(0, 59),
                    dosage = "Theo đơn hàng ngày"
                )
            )
        }

        memoryData = FamilyMemoryData(
            senior_profile = SeniorProfile(
                full_name = fullName.trim(),
                preferred_title = preferredTitle.trim().ifBlank { "Bác" },
                age = 70,
                home_address = homeAddress.trim().ifBlank { "TP. Hồ Chí Minh" }
            ),
            contacts = contactsList,
            places = placesList,
            medications = medsList,
            dialect_dictionary = defaultAssets.dialect_dictionary
        )
        saveMemory()
        setFirstSetupCompleted(true)
    }

    fun useSampleKhktDataAndCompleteSetup() {
        prefs.edit().remove("custom_family_memory_json").apply()
        memoryData = loadDefaultAssetMemory()
        setFirstSetupCompleted(true)
    }

    fun updateSeniorProfile(fullName: String, preferredTitle: String, age: Int, homeAddress: String, medicalNotes: String) {
        memoryData.senior_profile = memoryData.senior_profile.copy(
            full_name = fullName.trim(),
            preferred_title = preferredTitle.trim().ifBlank { "Bác" },
            age = age,
            home_address = homeAddress.trim(),
            medical_notes = medicalNotes.trim()
        )
        saveMemory()
    }

    fun addOrUpdateContact(
        nicknameInput: String,
        fullName: String,
        relationship: String,
        phone: String,
        channel: String = "ZALO",
        address: String = ""
    ) {
        val nicks = nicknameInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(fullName.trim()) }
        val existingIdx = memoryData.contacts.indexOfFirst { c ->
            c.nicknames.any { existing -> nicks.any { it.equals(existing, ignoreCase = true) } } ||
                    c.full_name.equals(fullName.trim(), ignoreCase = true)
        }
        val newContact = FamilyContact(
            id = if (existingIdx >= 0) memoryData.contacts[existingIdx].id else "contact_${System.currentTimeMillis()}",
            nicknames = nicks,
            full_name = fullName.trim().ifBlank { nicks.first() },
            relationship = relationship.trim().ifBlank { "Người thân" },
            phone = phone.trim(),
            zalo = phone.trim(),
            preferred_channel = channel.uppercase(),
            address = address.trim(),
            notes = "Người dùng tự nhập"
        )
        if (existingIdx >= 0) {
            memoryData.contacts[existingIdx] = newContact
        } else {
            memoryData.contacts.add(0, newContact)
        }
        saveMemory()
    }

    fun deleteContact(contactId: String) {
        memoryData.contacts.removeAll { it.id == contactId }
        saveMemory()
    }

    fun addOrUpdatePlace(
        nicknameInput: String,
        fullName: String,
        address: String,
        category: String = "PLACE",
        reminderNote: String = ""
    ) {
        val nicks = nicknameInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(fullName.trim()) }
        val existingIdx = memoryData.places.indexOfFirst { p ->
            p.nicknames.any { existing -> nicks.any { it.equals(existing, ignoreCase = true) } } ||
                    p.full_name.equals(fullName.trim(), ignoreCase = true)
        }
        val newPlace = FamiliarPlace(
            id = if (existingIdx >= 0) memoryData.places[existingIdx].id else "place_${System.currentTimeMillis()}",
            nicknames = nicks,
            full_name = nicks.first().takeIf { fullName.isBlank() } ?: fullName.trim(),
            address = address.trim(),
            category = category.uppercase(),
            reminder_note = reminderNote.trim()
        )
        if (existingIdx >= 0) {
            memoryData.places[existingIdx] = newPlace
        } else {
            memoryData.places.add(0, newPlace)
        }
        saveMemory()
    }

    fun deletePlace(placeId: String) {
        memoryData.places.removeAll { it.id == placeId }
        saveMemory()
    }

    fun addOrUpdateMedication(
        keywordsInput: String,
        medName: String,
        hour: Int,
        minute: Int,
        dosage: String
    ) {
        val kws = keywordsInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf(medName.trim()) }
        val existingIdx = memoryData.medications.indexOfFirst { m ->
            m.name.equals(medName.trim(), ignoreCase = true)
        }
        val newMed = MedicationSchedule(
            id = if (existingIdx >= 0) memoryData.medications[existingIdx].id else "med_${System.currentTimeMillis()}",
            keywords = kws,
            name = medName.trim(),
            default_hour = hour.coerceIn(0, 23),
            default_minute = minute.coerceIn(0, 59),
            dosage = dosage.trim().ifBlank { "Uống đúng giờ" }
        )
        if (existingIdx >= 0) {
            memoryData.medications[existingIdx] = newMed
        } else {
            memoryData.medications.add(0, newMed)
        }
        saveMemory()
    }

    fun deleteMedication(medId: String) {
        memoryData.medications.removeAll { it.id == medId }
        saveMemory()
    }

    fun clearAllDataForBlankStart() {
        val defaultAssets = loadDefaultAssetMemory()
        memoryData = FamilyMemoryData(
            senior_profile = SeniorProfile(
                full_name = "",
                preferred_title = "Bác",
                age = 68,
                home_address = ""
            ),
            contacts = mutableListOf(),
            places = mutableListOf(),
            medications = mutableListOf(),
            dialect_dictionary = defaultAssets.dialect_dictionary
        )
        saveMemory()
    }

    fun resetToDefaultMemory() {
        prefs.edit().remove("custom_family_memory_json").apply()
        memoryData = loadDefaultAssetMemory()
    }

    fun searchDevicePhonebookContacts(utteranceLower: String): List<FamilyContact> {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return emptyList()

        val found = mutableListOf<FamilyContact>()
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val rawNum = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    val cleanNum = rawNum.replace(Regex("[^0-9+]"), "")
                    if (name.length >= 2 && cleanNum.isNotBlank() && utteranceLower.contains(name.lowercase())) {
                        found.add(
                            FamilyContact(
                                id = "device_${cleanNum}",
                                nicknames = listOf(name),
                                full_name = "$name (Danh bạ máy)",
                                relationship = "Danh bạ điện thoại",
                                phone = cleanNum,
                                zalo = cleanNum,
                                preferred_channel = "CALL"
                            )
                        )
                    }
                }
            }
        }
        return found.distinctBy { it.phone }
    }

    fun loadBenchmarkQueries(): List<BenchmarkQueryItem> {
        val customBenchJson = prefs.getString("custom_benchmark_queries_json", null)
        val customList: List<BenchmarkQueryItem> = if (!customBenchJson.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<List<BenchmarkQueryItem>>() {}.type
                gson.fromJson<List<BenchmarkQueryItem>>(customBenchJson, type)
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val defaultList: List<BenchmarkQueryItem> = runCatching {
            val json = context.assets.open("benchmark_40_queries.json").bufferedReader().use { it.readText() }
            val listType = object : TypeToken<List<BenchmarkQueryItem>>() {}.type
            gson.fromJson<List<BenchmarkQueryItem>>(json, listType)
        }.getOrElse { emptyList() }

        return customList + defaultList
    }

    fun addCustomBenchmarkQuery(
        region: String,
        utterance: String,
        expectedTools: List<String>,
        hasSensitive: Boolean
    ): List<BenchmarkQueryItem> {
        val customBenchJson = prefs.getString("custom_benchmark_queries_json", null)
        val currentCustom: MutableList<BenchmarkQueryItem> = if (!customBenchJson.isNullOrBlank()) {
            runCatching {
                val type = object : TypeToken<MutableList<BenchmarkQueryItem>>() {}.type
                gson.fromJson<MutableList<BenchmarkQueryItem>>(customBenchJson, type)
            }.getOrElse { mutableListOf() }
        } else {
            mutableListOf()
        }

        val nextId = 100 + currentCustom.size + 1
        currentCustom.add(
            0,
            BenchmarkQueryItem(
                id = nextId,
                region = region.ifBlank { "Tự nhập" },
                utterance = utterance.trim(),
                expected_tools = expectedTools.ifEmpty { listOf("check_weather") },
                expected_contact = "",
                expected_place = "",
                expected_lunar_tag = "",
                expected_alarm = "",
                has_sensitive_action = hasSensitive,
                baseline_failure_reason = "Câu lệnh kiểm thử do người dùng tự nhập."
            )
        )
        prefs.edit().putString("custom_benchmark_queries_json", gson.toJson(currentCustom)).apply()
        return loadBenchmarkQueries()
    }

    fun analyzeAndGround(
        utterance: String,
        enableGrounding: Boolean = true,
        referenceDate: LocalDate = LocalDate.now()
    ): GroundingAnalysisResult {
        val groundingItems = mutableListOf<GroundingItem>()
        var normalized = utterance

        if (enableGrounding) {
            memoryData.dialect_dictionary.entries
                .sortedByDescending { it.key.length }
                .forEach { (dialectWord, standardMeaning) ->
                    val regex = Regex("\\b${Regex.escape(dialectWord)}\\b", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(utterance)) {
                        groundingItems.add(
                            GroundingItem(
                                category = "PHƯƠNG NGỮ",
                                rawTerm = "\"$dialectWord\"",
                                resolvedValue = standardMeaning
                            )
                        )
                        normalized = regex.replace(normalized, standardMeaning)
                    }
                }
        }

        val resolvedDateTime = if (enableGrounding) {
            LunarCalendarConverter.resolveVietnameseDateTime(utterance, referenceDate)
        } else {
            val todayLunar = LunarCalendarConverter.solarToLunar(referenceDate)
            ResolvedDateTime(
                solarDate = referenceDate,
                lunarDate = todayLunar,
                time = LunarCalendarConverter.extractTimeFromUtterance(utterance.lowercase(), false),
                matchedExpression = "hôm nay",
                explanationVi = "Chưa bật quy đổi Lịch Âm"
            )
        }

        val lowerUtterance = utterance.lowercase()
        if (enableGrounding && resolvedDateTime.matchedExpression != "hôm nay") {
            groundingItems.add(
                GroundingItem(
                    category = "THỜI GIAN / LỊCH ÂM",
                    rawTerm = "\"${resolvedDateTime.matchedExpression}\"",
                    resolvedValue = "${LunarCalendarConverter.formatDisplayDate(resolvedDateTime.solarDate)} (${resolvedDateTime.lunarDate.formatVi()})"
                )
            )
        }

        val matchedContacts = if (enableGrounding) {
            val fromMemory = memoryData.contacts.filter { contact ->
                contact.nicknames.any { nick -> lowerUtterance.contains(nick.lowercase()) } ||
                        lowerUtterance.contains(contact.full_name.lowercase())
            }
            val fromPhonebook = if (fromMemory.isEmpty()) {
                searchDevicePhonebookContacts(lowerUtterance)
            } else {
                emptyList()
            }
            fromMemory + fromPhonebook
        } else {
            emptyList()
        }

        matchedContacts.forEach { contact ->
            val matchedNick = contact.nicknames.firstOrNull { lowerUtterance.contains(it.lowercase()) } ?: contact.full_name
            groundingItems.add(
                GroundingItem(
                    category = "NGƯỜI THÂN",
                    rawTerm = "\"$matchedNick\"",
                    resolvedValue = "${contact.full_name} (${contact.phone})"
                )
            )
        }

        val matchedPlaces = if (enableGrounding) {
            memoryData.places.filter { place ->
                place.nicknames.any { nick -> lowerUtterance.contains(nick.lowercase()) } ||
                        lowerUtterance.contains(place.full_name.lowercase())
            }
        } else {
            emptyList()
        }

        matchedPlaces.forEach { place ->
            val matchedNick = place.nicknames.firstOrNull { lowerUtterance.contains(it.lowercase()) } ?: place.full_name
            groundingItems.add(
                GroundingItem(
                    category = "ĐỊA ĐIỂM",
                    rawTerm = "\"$matchedNick\"",
                    resolvedValue = "${place.full_name} (${place.address})"
                )
            )
        }

        val matchedMeds = if (enableGrounding) {
            memoryData.medications.filter { med ->
                med.keywords.any { kw -> lowerUtterance.contains(kw.lowercase()) } ||
                        lowerUtterance.contains(med.name.lowercase())
            }
        } else {
            emptyList()
        }

        matchedMeds.forEach { med ->
            val matchedKw = med.keywords.firstOrNull { lowerUtterance.contains(it.lowercase()) } ?: med.name
            groundingItems.add(
                GroundingItem(
                    category = "THUỐC",
                    rawTerm = "\"$matchedKw\"",
                    resolvedValue = "${med.name} (${String.format("%02d:%02d", med.default_hour, med.default_minute)})"
                )
            )
        }

        val alarmTime = LunarCalendarConverter.extractTimeFromUtterance(lowerUtterance, preferAlarmTime = true)
        val alarmHour = if (matchedMeds.isNotEmpty() && !lowerUtterance.contains("giờ") && !lowerUtterance.contains("rưỡi") && !lowerUtterance.contains(":")) {
            matchedMeds.first().default_hour
        } else {
            alarmTime.hour
        }
        val alarmMin = if (matchedMeds.isNotEmpty() && !lowerUtterance.contains("giờ") && !lowerUtterance.contains("rưỡi") && !lowerUtterance.contains(":")) {
            matchedMeds.first().default_minute
        } else {
            alarmTime.minute
        }

        val promptBlock = buildString {
            appendLine("=== NGỮ CẢNH GIA ĐÌNH & LỊCH ÂM (DATA MINIMIZATION: CHỈ GỬI THỰC THỂ LIÊN QUAN) ===")
            appendLine("- Người dùng: ${memoryData.senior_profile.preferred_title} ${memoryData.senior_profile.full_name}")
            appendLine("- Ngày mục tiêu: ${LunarCalendarConverter.formatIsoDate(resolvedDateTime.solarDate)} (${resolvedDateTime.lunarDate.formatVi()})")
            appendLine("- Giờ sự kiện: ${resolvedDateTime.time} | Giờ báo thức: ${String.format("%02d:%02d", alarmHour, alarmMin)}")
            if (matchedContacts.isNotEmpty()) {
                appendLine("- Danh bạ khớp: " + matchedContacts.joinToString("; ") { "${it.full_name} (tên gọi: ${it.nicknames.firstOrNull()}, quan hệ: ${it.relationship}, SĐT: ${it.phone}, kênh: ${it.preferred_channel}, địa chỉ: ${it.address})" })
            }
            if (matchedPlaces.isNotEmpty()) {
                appendLine("- Địa điểm khớp: " + matchedPlaces.joinToString("; ") { "${it.full_name} (địa chỉ: ${it.address}, lat=${it.latitude}, lon=${it.longitude})" })
            }
            if (matchedMeds.isNotEmpty()) {
                appendLine("- Thuốc khớp: " + matchedMeds.joinToString("; ") { "${it.name} (${String.format("%02d:%02d", it.default_hour, it.default_minute)} - ${it.dosage})" })
            }
        }

        return GroundingAnalysisResult(
            rawUtterance = utterance,
            normalizedUtterance = normalized,
            resolvedDateTime = resolvedDateTime,
            alarmTimeHour = alarmHour,
            alarmTimeMinute = alarmMin,
            matchedContacts = matchedContacts,
            matchedPlaces = matchedPlaces,
            matchedMedications = matchedMeds,
            groundingItems = groundingItems,
            promptContextBlock = promptBlock
        )
    }
}
