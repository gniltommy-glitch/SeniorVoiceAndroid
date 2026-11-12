package com.khkt.seniorvoice.agent

import android.content.Context
import com.khkt.seniorvoice.context.FamilyMemoryRepository
import com.khkt.seniorvoice.context.GroundingAnalysisResult
import com.khkt.seniorvoice.context.LunarCalendarConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** API inference with local grounding and code-enforced safety checks. */
class ApiLlmPlanner(
    private val familyRepository: FamilyMemoryRepository,
    context: Context
) {
    private val keys = ApiKeyStore(context)
    private val directClient = ApiPlannerClient(context, keys)
    private val prefs = context.getSharedPreferences("senior_voice_prefs", Context.MODE_PRIVATE)

    fun providerApiKey(provider: ApiProvider): String = keys.get(provider.name)
    fun saveProviderApiKey(provider: ApiProvider, value: String) = keys.put(provider.name, value)

    var allowMeteredProviders: Boolean
        get() = prefs.getBoolean("api_allow_metered", false)
        set(value) { prefs.edit().putBoolean("api_allow_metered", value).apply() }

    var executionMode: ExecutionEngineMode
        get() {
            val saved = prefs?.getString("exec_mode", ExecutionEngineMode.API_DIRECT.name)
            return runCatching { ExecutionEngineMode.valueOf(saved ?: ExecutionEngineMode.API_DIRECT.name) }
                .getOrDefault(ExecutionEngineMode.API_DIRECT)
        }
        set(value) {
            prefs?.edit()?.putString("exec_mode", value.name)?.apply()
        }

    var apiProxyUrl: String
        get() = prefs?.getString("api_proxy_url", "") ?: ""
        set(value) { prefs?.edit()?.putString("api_proxy_url", value.trim())?.apply() }

    var apiDeviceToken: String
        get() = prefs?.getString("api_device_token", "") ?: ""
        set(value) { prefs?.edit()?.putString("api_device_token", value.trim())?.apply() }

    var cloudConsent: Boolean
        get() = prefs?.getBoolean("cloud_consent_all_routes_v2", false) ?: false
        set(value) { prefs?.edit()?.putBoolean("cloud_consent_all_routes_v2", value)?.apply() }

    private var proxyFailures = 0
    private var proxyBlockedUntil = 0L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(28000, TimeUnit.MILLISECONDS)
        .callTimeout(30000, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val cityCoordinates = mapOf(
        "hà nội" to Triple("Hà Nội", 21.0285, 105.8542),
        "đà nẵng" to Triple("Đà Nẵng", 16.0544, 108.2022),
        "huế" to Triple("TP. Huế", 16.4637, 107.5909),
        "cần thơ" to Triple("Cần Thơ", 10.0452, 105.7469),
        "hải phòng" to Triple("Hải Phòng", 20.8449, 106.6881),
        "đà lạt" to Triple("Đà Lạt", 11.9404, 108.4583),
        "nha trang" to Triple("Nha Trang", 12.2388, 109.1967),
        "vũng tàu" to Triple("Vũng Tàu", 10.3460, 107.0843),
        "quy nhơn" to Triple("Quy Nhơn", 13.7830, 109.2197),
        "vinh" to Triple("TP. Vinh", 18.6796, 105.6813),
        "sài gòn" to Triple("TP. Hồ Chí Minh", 10.7626, 106.6602),
        "hồ chí minh" to Triple("TP. Hồ Chí Minh", 10.7626, 106.6602)
    )

    suspend fun createPlan(
        utterance: String,
        enableGrounding: Boolean = true,
        enableSafetyGuard: Boolean = true,
        referenceDate: LocalDate = LocalDate.now()
    ): AgentPlan {
        val plan = createPlanInternal(utterance, enableGrounding, enableSafetyGuard, referenceDate)
        if (!enableGrounding) return plan // Offline ablation benchmark, not used for live execution.
        val grounding = familyRepository.analyzeAndGround(utterance, enableGrounding, referenceDate)
        val emergency = plan.thought.startsWith("PHÁT HIỆN TRIỆU CHỨNG ĐÈN ĐỎ")
        if (!emergency && plan.steps.any { !PlanValidator.validate(it.tool, it.params, grounding) }) {
            return askAgain(plan, "Con chưa xác định chắc người nhận hoặc ngày giờ. Bác nói rõ lại giúp con nhé.")
        }
        val steps = plan.steps.map {
            if (it.tool in setOf("call_or_message_family", "navigate_or_book_ride")) it.copy(risk = RiskLevel.SENSITIVE) else it
        }
        val sensitive = steps.any { it.risk == RiskLevel.SENSITIVE }
        return plan.copy(steps = steps, requiresConfirmation = sensitive,
            confirmationPrompt = if (sensitive) buildConfirmationPrompt(steps) else null)
    }

    private suspend fun createPlanInternal(
        utterance: String,
        enableGrounding: Boolean = true,
        enableSafetyGuard: Boolean = true,
        referenceDate: LocalDate = LocalDate.now()
    ): AgentPlan = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val grounding = familyRepository.analyzeAndGround(
            utterance = utterance,
            enableGrounding = enableGrounding,
            referenceDate = referenceDate
        )

        val rules = buildDeterministicSafetyPlan(grounding, enableGrounding, enableSafetyGuard, startMs)
        val emergency = rules.thought.startsWith("PHÁT HIỆN TRIỆU CHỨNG ĐÈN ĐỎ")
        // Only safety rules and the developer baseline run without inference.
        if (emergency || !enableGrounding) return@withContext rules
        if (!cloudConsent) return@withContext askAgain(rules,
            "Dạ bác nhờ người thân bật quyền dùng API trong Cài đặt AI trước nhé.")
        val remote = if (executionMode == ExecutionEngineMode.API_PROXY) {
            if (apiProxyUrl.isBlank() || apiDeviceToken.isBlank()) return@withContext askAgain(rules,
                "Dạ bác nhờ người thân nhập địa chỉ gateway và mã thiết bị trong Cài đặt AI nhé.")
            tryCallApiProxy(grounding, enableSafetyGuard, startMs)
        } else {
            if (!directClient.hasKey(allowMeteredProviders)) return@withContext askAgain(rules,
                "Dạ bác nhờ người thân nhập ít nhất một API key trong Cài đặt AI nhé.")
            val prompt = PrivatePlanPrompt.create(grounding, familyRepository.getMemoryData())
            directClient.plan(prompt.text, allowMeteredProviders) { json, source ->
                parseValidatedApiPlan(prompt.restore(json.toString()), grounding, enableSafetyGuard, startMs, source)
            }
        }
        remote ?: askAgain(rules, "Dạ dịch vụ AI đang bận hoặc điện thoại mất mạng. Bác thử lại sau giúp con nhé.")
    }

    private fun askAgain(plan: AgentPlan, message: String) = plan.copy(
        steps = emptyList(), requiresConfirmation = false, confirmationPrompt = null,
        spokenResponse = message, engineUsed = "Clarification (Offline rules)")

    private fun tryCallApiProxy(grounding: GroundingAnalysisResult, enableSafetyGuard: Boolean, startMs: Long): AgentPlan? {
        if (!cloudConsent || !apiProxyUrl.startsWith("https://") || apiDeviceToken.isBlank() ||
            System.currentTimeMillis() < proxyBlockedUntil) return null
        return runCatching {
            val privatePrompt = PrivatePlanPrompt.create(grounding, familyRepository.getMemoryData())
            val request = Request.Builder().url("${apiProxyUrl.trimEnd('/')}/v1/plan")
                .header("Authorization", "Bearer $apiDeviceToken")
                .post(JSONObject().put("prompt", privatePrompt.text).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful)
                val root = JSONObject(response.body?.string() ?: error("Empty response"))
                val source = "${root.getString("provider")} (${root.getString("model")})"
                parseValidatedApiPlan(privatePrompt.restore(root.getJSONObject("plan").toString()),
                    grounding, enableSafetyGuard, startMs, source) ?: error("Invalid plan")
            }
        }.onSuccess { proxyFailures = 0 }.onFailure {
            proxyFailures++
            if (proxyFailures >= 3) proxyBlockedUntil = System.currentTimeMillis() + 120_000
        }.getOrNull()
    }

    private fun parseValidatedApiPlan(
        jsonContent: String,
        grounding: GroundingAnalysisResult,
        enableSafetyGuard: Boolean,
        startMs: Long,
        source: String = "API"
    ): AgentPlan? {
        return runCatching {
            val obj = JSONObject(jsonContent)
            val thought = obj.optString("thought", "Đã phân tích kế hoạch qua $source.")
            val spoken = obj.optString("spoken_response", "Dạ con đã lên kế hoạch thực hiện yêu cầu của bác.")
            val stepsArr = obj.optJSONArray("steps") ?: return null
            if (stepsArr.length() > 8) return null
            if (stepsArr.length() == 0) return AgentPlan(
                grounding.rawUtterance, grounding.normalizedUtterance, "Cần làm rõ yêu cầu",
                false, spoken, null, grounding.groundingItems, emptyList(), source,
                System.currentTimeMillis() - startMs)

            val parsedSteps = mutableListOf<PlanStep>()
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(i)
                val tool = s.optString("tool")
                val paramsObj = s.optJSONObject("params") ?: JSONObject()
                val paramMap = mutableMapOf<String, String>()
                paramsObj.keys().forEach { k -> paramMap[k] = paramsObj.optString(k) }

                if (tool == "call_or_message_family") {
                    val contacts = grounding.matchedContacts.filter { it.full_name == paramMap["contact_name"] ||
                        it.nicknames.any { nick -> nick == paramMap["nickname"] } }
                    if (contacts.size != 1) return null
                    paramMap["phone"] = contacts.single().phone
                }
                if (tool == "check_weather") {
                    val place = grounding.matchedPlaces.firstOrNull()
                    val profile = familyRepository.getMemoryData().senior_profile
                    paramMap["latitude"] = (place?.latitude ?: profile.latitude).toString()
                    paramMap["longitude"] = (place?.longitude ?: profile.longitude).toString()
                }
                if (!PlanValidator.validate(tool, paramMap, grounding)) return null
                val isSensitiveTool = tool == "call_or_message_family" || tool == "navigate_or_book_ride"
                val risk = if (isSensitiveTool) RiskLevel.SENSITIVE else RiskLevel.SAFE
                parsedSteps.add(
                    PlanStep(
                        stepId = i + 1,
                        tool = tool,
                        titleVi = mapToolToVietnameseTitle(tool, paramMap),
                        descriptionVi = mapToolToVietnameseDescription(tool, paramMap),
                        risk = risk,
                        params = paramMap
                    )
                )
            }

            val hasSensitive = parsedSteps.any { it.risk == RiskLevel.SENSITIVE }
            val confirmPrompt = if (hasSensitive) buildConfirmationPrompt(parsedSteps) else null

            AgentPlan(
                rawUtterance = grounding.rawUtterance,
                normalizedUtterance = grounding.normalizedUtterance,
                thought = thought,
                requiresConfirmation = hasSensitive,
                spokenResponse = spoken,
                confirmationPrompt = confirmPrompt,
                groundingItems = grounding.groundingItems,
                steps = parsedSteps,
                engineUsed = source,
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(12L)
            )
        }.getOrNull()
    }

    private fun buildDeterministicSafetyPlan(
        grounding: GroundingAnalysisResult,
        enableGrounding: Boolean,
        enableSafetyGuard: Boolean,
        startMs: Long
    ): AgentPlan {
        val rawText = grounding.rawUtterance.trim()
        val textForCheck = if (enableGrounding) {
            "${grounding.rawUtterance} ${grounding.normalizedUtterance}".lowercase()
        } else {
            grounding.rawUtterance.lowercase()
        }

        val steps = mutableListOf<PlanStep>()
        var stepCounter = 1

        val primaryPlace = grounding.matchedPlaces.firstOrNull()
        val primaryMed = grounding.matchedMedications.firstOrNull()
        val targetDateIso = LunarCalendarConverter.formatIsoDate(grounding.resolvedDateTime.solarDate)
        val targetDateDisplay = LunarCalendarConverter.formatDisplayDate(grounding.resolvedDateTime.solarDate)
        val eventTimeStr = String.format(
            "%02d:%02d",
            grounding.resolvedDateTime.time.hour,
            grounding.resolvedDateTime.time.minute
        )

        // =========================================================================
        // 0A. PHÂN LUỒNG KHẨN CẤP Y TẾ ĐÈN ĐỎ (RED-FLAG EMERGENCY TRIAGE - T8, #45)
        // =========================================================================
        val hasRedFlagEmergency = enableGrounding && (
                textForCheck.contains("đau tức ngực") ||
                        textForCheck.contains("ngực bác đau") ||
                        textForCheck.contains("tức ngực") ||
                        textForCheck.contains("khó thở") ||
                        textForCheck.contains("mắt mờ") ||
                        textForCheck.contains("mờ mắt") ||
                        textForCheck.contains("yếu nửa người") ||
                        textForCheck.contains("đột quỵ") ||
                        textForCheck.contains("gọi cấp cứu") ||
                        textForCheck.contains("115")
                )

        if (hasRedFlagEmergency) {
            val emergencyContact = selectTargetContactForCommunication(textForCheck, grounding)
                ?: familyRepository.getMemoryData().contacts.firstOrNull()
            val contactLabel = if (emergencyContact != null) {
                "Cấp cứu 115 & ${emergencyContact.full_name}"
            } else {
                "Cấp cứu Y tế 115"
            }
            val risk = if (enableSafetyGuard) RiskLevel.SENSITIVE else RiskLevel.SAFE
            val emergencyParams = mapOf(
                "nickname" to (emergencyContact?.nicknames?.firstOrNull() ?: "Cấp cứu 115"),
                "contact_name" to contactLabel,
                "phone" to "115",
                "backup_family_phone" to (emergencyContact?.phone ?: ""),
                "channel" to "CALL",
                "message" to "KHẨN CẤP: Bác đang có triệu chứng đau tức ngực / khó thở / mờ mắt, cần hỗ trợ y tế ngay lập tức!"
            )
            steps.add(
                PlanStep(
                    stepId = stepCounter++,
                    tool = "call_or_message_family",
                    titleVi = "GỌI KHẨN CẤP 115 & $contactLabel",
                    descriptionVi = "Tổng đài Cấp cứu: 115 • Người thân: ${emergencyContact?.phone ?: "Khẩn cấp"}",
                    risk = risk,
                    params = emergencyParams
                )
            )

            val emergencySpoken = "CẢNH BÁO Y TẾ KHẨN CẤP: Bác đang có dấu hiệu nguy hiểm (đau tức ngực, khó thở hoặc mờ mắt). Lưu ý: Trợ lý AI không thay thế bác sĩ! Bác hãy ngồi hoặc nằm nghỉ ngay tại chỗ, giữ bình tĩnh, nói 'Đồng ý' hoặc bấm nút Xác nhận màu xanh để mở cuộc gọi Cấp cứu 115 và báo cho ${emergencyContact?.full_name ?: "người thân"} ngay lập tức ạ!"
            return AgentPlan(
                rawUtterance = grounding.rawUtterance,
                normalizedUtterance = grounding.normalizedUtterance,
                thought = "PHÁT HIỆN TRIỆU CHỨNG ĐÈN ĐỎ NGUY HIỂM: Kích hoạt quy trình Cấp cứu 115 & Liên hệ khẩn cấp người thân.",
                requiresConfirmation = enableSafetyGuard,
                spokenResponse = emergencySpoken,
                confirmationPrompt = "KHẨN CẤP: Xác nhận mở cuộc gọi Cấp cứu 115 & báo người thân ngay?",
                groundingItems = grounding.groundingItems,
                steps = steps,
                engineUsed = "Deterministic safety rules (no model)",
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(12L)
            )
        }

        // =========================================================================
        // 0B. SUY LUẬN TRIỆU CHỨNG Y TẾ NGẦM ĐỊNH (IMPLICIT SYMPTOM REASONING - #44)
        // =========================================================================
        val hasImplicitSymptom = enableGrounding && (
                textForCheck.contains("chóng mặt") ||
                        textForCheck.contains("quay quay") ||
                        textForCheck.contains("choáng váng") ||
                        textForCheck.contains("đau đầu") ||
                        textForCheck.contains("nhức đầu") ||
                        textForCheck.contains("huyết áp lại lên") ||
                        textForCheck.contains("mệt trong người")
                )

        // 1. Kiểm tra Thời tiết (check_weather - SAFE)
        val wantsWeather = textForCheck.contains("thời tiết") ||
                textForCheck.contains("mưa") ||
                textForCheck.contains("nắng") ||
                textForCheck.contains("lạnh") ||
                textForCheck.contains("rét") ||
                textForCheck.contains("nhiệt độ") ||
                textForCheck.contains("coi thử trời")

        if (wantsWeather) {
            val matchedCity = cityCoordinates.entries.firstOrNull { textForCheck.contains(it.key) }?.value
            val lat = matchedCity?.second ?: primaryPlace?.latitude ?: familyRepository.getMemoryData().senior_profile.latitude
            val lon = matchedCity?.third ?: primaryPlace?.longitude ?: familyRepository.getMemoryData().senior_profile.longitude
            val locName = matchedCity?.first ?: primaryPlace?.full_name ?: familyRepository.getMemoryData().senior_profile.home_address.ifBlank { "Vị trí hiện tại" }

            val params = mapOf(
                "date" to targetDateIso,
                "hour" to eventTimeStr,
                "latitude" to lat.toString(),
                "longitude" to lon.toString(),
                "location_name" to locName
            )
            steps.add(
                PlanStep(
                    stepId = stepCounter++,
                    tool = "check_weather",
                    titleVi = "Kiểm tra thời tiết ngày $targetDateDisplay",
                    descriptionVi = "Khu vực: $locName (khung giờ $eventTimeStr)",
                    risk = RiskLevel.SAFE,
                    params = params
                )
            )
        }

        // 2. Đặt báo thức / Nhắc uống thuốc (set_medication_alarm - SAFE)
        // Hỗ trợ tách nhiều chuông báo thức trong cùng 1 câu lệnh (Bug 3 - Câu #22)
        val wantsAlarm = textForCheck.contains("báo thức") ||
                textForCheck.contains("đặt chuông") ||
                textForCheck.contains("hẹn giờ") ||
                textForCheck.contains("nhắc ") ||
                hasImplicitSymptom ||
                (textForCheck.contains("uống thuốc") && !textForCheck.contains("hỏi đơn thuốc"))

        if (wantsAlarm) {
            val multiAlarmSteps = extractMultipleMedicationAlarmsIfPresent(rawText, grounding)
            if (multiAlarmSteps.size >= 2) {
                for ((h, m, lbl) in multiAlarmSteps) {
                    val tf = String.format("%02d:%02d", h, m)
                    steps.add(
                        PlanStep(
                            stepId = stepCounter++,
                            tool = "set_medication_alarm",
                            titleVi = "Đặt báo thức lúc $tf",
                            descriptionVi = "Nhãn báo thức: \"$lbl\"",
                            risk = RiskLevel.SAFE,
                            params = mapOf(
                                "hour" to h.toString(),
                                "minute" to m.toString(),
                                "label" to lbl
                            )
                        )
                    )
                }
            } else {
                val alarmH = grounding.alarmTimeHour
                val alarmM = grounding.alarmTimeMinute
                val timeFormatted = String.format("%02d:%02d", alarmH, alarmM)
                val dynamicLabel = extractCustomAlarmLabel(rawText)
                val label = when {
                    hasImplicitSymptom && primaryMed != null ->
                        "Nghỉ ngơi, đo huyết áp & uống ${primaryMed.name} (${primaryMed.dosage})"
                    hasImplicitSymptom ->
                        "Nghỉ ngơi, đo lại huyết áp & kiểm tra thuốc huyết áp"
                    primaryMed != null ->
                        "Uống ${primaryMed.name} (${primaryMed.dosage})"
                    textForCheck.contains("tập dưỡng sinh") || textForCheck.contains("tập thể dục") ->
                        "Dậy tập dưỡng sinh / thể dục sáng${if (primaryPlace != null) " tại ${primaryPlace.full_name}" else ""}"
                    textForCheck.contains("uống nước ấm") ->
                        "Uống nước ấm buổi sáng"
                    primaryPlace != null ->
                        "Dậy chuẩn bị đi ${primaryPlace.full_name}"
                    dynamicLabel.isNotBlank() ->
                        dynamicLabel
                    else ->
                        "Báo thức lúc $timeFormatted"
                }
                val params = mapOf(
                    "hour" to alarmH.toString(),
                    "minute" to alarmM.toString(),
                    "label" to label
                )
                steps.add(
                    PlanStep(
                        stepId = stepCounter++,
                        tool = "set_medication_alarm",
                        titleVi = "Đặt báo thức lúc $timeFormatted",
                        descriptionVi = "Nhãn báo thức: \"$label\"",
                        risk = RiskLevel.SAFE,
                        params = params
                    )
                )
            }
        }

        // 3. Ghi sự kiện vào Lịch hệ thống (insert_calendar_event - SAFE)
        val wantsCalendar = textForCheck.contains("ghi lịch") ||
                textForCheck.contains("ghi vào lịch") ||
                textForCheck.contains("thêm vào lịch") ||
                textForCheck.contains("thêm lịch") ||
                textForCheck.contains("tạo lịch") ||
                textForCheck.contains("đặt lịch") ||
                textForCheck.contains("thêm vô lịch") ||
                textForCheck.contains("ghi vô lịch") ||
                textForCheck.contains("khám định kỳ") ||
                textForCheck.contains("tái khám") ||
                (enableGrounding && (textForCheck.contains("đi khám") ||
                        textForCheck.contains("đi lễ") ||
                        textForCheck.contains("đi chùa") ||
                        textForCheck.contains("ăn cơm chay") ||
                        textForCheck.contains("lấy kết quả") ||
                        textForCheck.contains("hẹn đánh cờ") ||
                        (primaryPlace != null && (textForCheck.contains("rằm") || textForCheck.contains("mùng") || textForCheck.contains("lúc")))))

        if (wantsCalendar) {
            val extractedEventTitle = extractCustomCalendarTitle(rawText)
            val extractedLocation = extractCustomDestinationOrLocation(rawText)
            val placeName = primaryPlace?.full_name ?: extractedLocation.ifBlank { extractedEventTitle.ifBlank { "Lịch hẹn cá nhân" } }
            val placeAddress = primaryPlace?.address ?: extractedLocation.ifBlank { placeName }
            val eventTitle = when {
                primaryPlace?.category == "HOSPITAL" -> "Khám bệnh tại $placeName"
                primaryPlace?.category == "TEMPLE" -> "Đi lễ Phật tại $placeName"
                primaryPlace != null -> "Lịch hẹn tại $placeName"
                extractedEventTitle.isNotBlank() -> extractedEventTitle
                else -> "Lịch hẹn: $placeName"
            }
            val note = primaryPlace?.reminder_note ?: "Ghi chú từ câu lệnh: $rawText"
            val params = mapOf(
                "title" to eventTitle,
                "date" to targetDateIso,
                "time" to eventTimeStr,
                "location" to placeAddress,
                "description" to "$note (${grounding.resolvedDateTime.lunarDate.formatVi()})"
            )
            steps.add(
                PlanStep(
                    stepId = stepCounter++,
                    tool = "insert_calendar_event",
                    titleVi = "Ghi Lịch: $eventTitle ($eventTimeStr)",
                    descriptionVi = "Ngày $targetDateDisplay (${grounding.resolvedDateTime.lunarDate.formatVi()}) • Tại: $placeAddress",
                    risk = RiskLevel.SAFE,
                    params = params
                )
            )
        }

        // 4. Mở Bản đồ / Đặt xe (navigate_or_book_ride - SENSITIVE)
        // Hỗ trợ dẫn đường / đặt xe tới nhà người thân khi nói "qua nhà con Thảo", "ghé nhà thằng Út" (Bug 4 - Câu #34)
        val hasVisitFamilyHomePhrase = textForCheck.contains("qua nhà") ||
                textForCheck.contains("ghé nhà") ||
                textForCheck.contains("đến nhà") ||
                textForCheck.contains("tới nhà") ||
                textForCheck.contains("sang nhà")

        val wantsNavigationOrRide = textForCheck.contains("chỉ đường") ||
                textForCheck.contains("dẫn đường") ||
                textForCheck.contains("bản đồ") ||
                textForCheck.contains("google maps") ||
                textForCheck.contains("đặt xe") ||
                textForCheck.contains("bắt xe") ||
                textForCheck.contains("gọi xe") ||
                textForCheck.contains("đi bộ ra") ||
                (hasVisitFamilyHomePhrase && grounding.matchedContacts.any { it.address.isNotBlank() })

        if (wantsNavigationOrRide) {
            val contactWithAddress = grounding.matchedContacts.firstOrNull { it.address.isNotBlank() }
            val dynamicDest = extractCustomDestinationOrLocation(rawText)
            val destName = when {
                primaryPlace != null -> primaryPlace.full_name
                contactWithAddress != null && hasVisitFamilyHomePhrase -> "Nhà ${contactWithAddress.full_name}"
                dynamicDest.isNotBlank() -> dynamicDest
                contactWithAddress != null -> "Nhà ${contactWithAddress.full_name}"
                else -> rawText
            }
            val destAddr = when {
                primaryPlace != null -> primaryPlace.address
                contactWithAddress != null && contactWithAddress.address.isNotBlank() -> contactWithAddress.address
                dynamicDest.isNotBlank() -> dynamicDest
                else -> destName
            }
            val mode = if (
                textForCheck.contains("đặt xe") ||
                textForCheck.contains("bắt xe") ||
                textForCheck.contains("gọi xe")
            ) "RIDE" else "MAPS"
            val modeVi = if (mode == "RIDE") "Mở ứng dụng Đặt xe" else "Mở Bản đồ dẫn đường"
            val risk = if (enableSafetyGuard) RiskLevel.SENSITIVE else RiskLevel.SAFE

            val params = mapOf(
                "destination_name" to destName,
                "address" to destAddr,
                "mode" to mode
            )
            steps.add(
                PlanStep(
                    stepId = stepCounter++,
                    tool = "navigate_or_book_ride",
                    titleVi = "$modeVi tới $destName",
                    descriptionVi = "Địa chỉ / Điểm đến: $destAddr",
                    risk = risk,
                    params = params
                )
            )
        }

        // 5. Gọi điện / Nhắn Zalo / SMS (call_or_message_family - SENSITIVE)
        var unknownContactWarning: String? = null
        val wantsContact = textForCheck.contains("nhắn") ||
                textForCheck.contains("gọi") ||
                textForCheck.contains("alo") ||
                textForCheck.contains("báo cho") ||
                textForCheck.contains("biểu") ||
                textForCheck.contains("bẩu") ||
                textForCheck.contains("kêu") ||
                textForCheck.contains("dây thép") ||
                hasImplicitSymptom

        if (wantsContact) {
            val targetContact = selectTargetContactForCommunication(textForCheck, grounding)
                ?: if (hasImplicitSymptom) {
                    // Nếu có triệu chứng chóng mặt/mệt mỏi mà chưa nêu tên, ưu tiên Bác sĩ gia đình hoặc Con gái/Con trai
                    familyRepository.getMemoryData().contacts.firstOrNull {
                        it.relationship.contains("Bác sĩ", ignoreCase = true) ||
                                it.relationship.contains("Con", ignoreCase = true)
                    }
                } else null

            val explicitPhone = extractPhoneNumberFromText(rawText)
            val dynamicPersonName = extractDynamicRecipientName(rawText)

            val contactName = targetContact?.full_name
                ?: dynamicPersonName.ifBlank { explicitPhone.ifBlank { "Người thân" } }
            val phone = targetContact?.phone?.ifBlank { explicitPhone } ?: explicitPhone

            if (targetContact == null && explicitPhone.isBlank()) {
                unknownContactWarning = "Lưu ý: Con chưa tìm thấy số điện thoại của \"$contactName\" trong Sổ tay Gia đình hoặc Danh bạ máy. Bác có thể bấm biểu tượng 'Sửa' ở bước bên dưới để nhập số điện thoại trước khi xác nhận nhé ạ!"
            }

            val isCall = (textForCheck.contains("gọi") && !textForCheck.contains("gọi xe")) ||
                    textForCheck.contains("dây thép") || textForCheck.contains("alo")
            val channel = when {
                isCall -> "CALL"
                textForCheck.contains("zalo") -> "ZALO"
                textForCheck.contains("sms") || textForCheck.contains("tin nhắn") -> "SMS"
                else -> targetContact?.preferred_channel ?: "ZALO"
            }

            val customMsg = extractCustomMessageContent(rawText)
            val autoMessage = if (hasImplicitSymptom && customMsg.isBlank()) {
                "Bác đang thấy chóng mặt / mệt trong người, nhờ kiểm tra huyết áp và tư vấn giúp bác nhé."
            } else if (customMsg.isNotBlank() && targetContact == null) {
                customMsg
            } else {
                composeNaturalFamilyMessage(
                    contact = targetContact,
                    place = primaryPlace,
                    dateDisplay = targetDateDisplay,
                    lunarDisplay = grounding.resolvedDateTime.lunarDate.formatVi(),
                    timeStr = eventTimeStr,
                    rawUtterance = rawText,
                    customExtractedMsg = customMsg
                )
            }

            val risk = if (enableSafetyGuard) RiskLevel.SENSITIVE else RiskLevel.SAFE
            val actionLabel = when (channel) {
                "CALL" -> "Gọi điện thoại cho $contactName"
                "ZALO" -> "Nhắn tin Zalo cho $contactName"
                else -> "Gửi tin nhắn SMS cho $contactName"
            }

            val params = mapOf(
                "nickname" to (targetContact?.nicknames?.firstOrNull() ?: contactName),
                "contact_name" to contactName,
                "phone" to phone,
                "channel" to channel,
                "message" to autoMessage
            )
            steps.add(
                PlanStep(
                    stepId = stepCounter++,
                    tool = "call_or_message_family",
                    titleVi = actionLabel,
                    descriptionVi = if (channel == "CALL") {
                        "Số điện thoại: ${phone.ifBlank { "Chưa có SĐT – Bấm 'Sửa' để nhập SĐT" }}"
                    } else {
                        "SĐT: ${phone.ifBlank { "Chưa có SĐT (Bấm 'Sửa' để nhập)" }} • Nội dung: \"$autoMessage\""
                    },
                    risk = risk,
                    params = params
                )
            )
        }

        val finalSteps = if (!enableGrounding && steps.size > 1) {
            listOf(steps.first())
        } else {
            steps
        }

        val hasSensitive = finalSteps.any { it.risk == RiskLevel.SENSITIVE }
        val confirmPrompt = if (hasSensitive) buildConfirmationPrompt(finalSteps) else null

        val thoughtText = buildString {
            append("Phân tích câu lệnh: \"$rawText\". ")
            if (grounding.groundingItems.isNotEmpty()) {
                append(grounding.groundingItems.joinToString("; ") { "${it.rawTerm} → ${it.resolvedValue}" })
                append(". ")
            }
            if (grounding.matchedContacts.size > 1) {
                append("Phân giải đa người thân trong câu: chọn đúng người nhận theo mệnh đề liên lạc. ")
            }
            append("Lập kế hoạch ${finalSteps.size} bước: ")
            append(finalSteps.joinToString(" → ") { "[${it.stepId}] ${it.tool} (${it.risk})" })
        }

        val baseSpoken = if (finalSteps.isEmpty()) "Bác nói ngắn lại từng việc giúp con nhé." else buildNaturalSpokenResponse(finalSteps, targetDateDisplay, grounding)
        val spokenText = buildString {
            if (hasImplicitSymptom) {
                append("Dạ bác đang thấy chóng mặt hoặc mệt trong người, bác nhớ ngồi nghỉ ngơi ngay nhé (lưu ý: gợi ý của AI không thay thế chỉ định của bác sĩ). ")
            }
            append(baseSpoken)
            if (!unknownContactWarning.isNullOrBlank()) {
                append(" ")
                append(unknownContactWarning)
            }
        }

        return AgentPlan(
            rawUtterance = grounding.rawUtterance,
            normalizedUtterance = grounding.normalizedUtterance,
            thought = thoughtText,
            requiresConfirmation = hasSensitive,
            spokenResponse = spokenText,
            confirmationPrompt = confirmPrompt,
            groundingItems = grounding.groundingItems,
            steps = finalSteps,
            engineUsed = "Deterministic safety rules (no model)",
            latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(15L)
        )
    }

    /**
     * Tách nhiều chuông báo thức nếu câu nói chứa từ 2 loại thuốc hoặc 2 mốc giờ báo thức trở lên (Bug 3 - Câu #22)
     */
    private fun extractMultipleMedicationAlarmsIfPresent(
        rawUtterance: String,
        grounding: GroundingAnalysisResult
    ): List<Triple<Int, Int, String>> {
        val clauses = rawUtterance.split(Regex("\\s+(?:và|rồi|sau đó)\\s+|,|;", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (clauses.size < 2 && grounding.matchedMedications.size < 2) return emptyList()

        val results = mutableListOf<Triple<Int, Int, String>>()
        val allMeds = familyRepository.getMemoryData().medications

        for (clause in clauses) {
            val lowerClause = clause.lowercase()
            val matchedMed = allMeds.firstOrNull { med ->
                med.keywords.any { kw -> lowerClause.contains(kw.lowercase()) } ||
                        lowerClause.contains(med.name.lowercase())
            }
            val hasTimeWord = Regex("\\d{1,2}\\s*(?:giờ|h|:)").containsMatchIn(
                LunarCalendarConverter.normalizeSpokenVietnameseNumbers(lowerClause)
            )
            if (matchedMed != null || (hasTimeWord && (lowerClause.contains("uống") || lowerClause.contains("nhắc") || lowerClause.contains("báo thức")))) {
                val parsedTime = LunarCalendarConverter.extractTimeFromUtterance(lowerClause, preferAlarmTime = true)
                val h = if (hasTimeWord) parsedTime.hour else (matchedMed?.default_hour ?: parsedTime.hour)
                val m = if (hasTimeWord) parsedTime.minute else (matchedMed?.default_minute ?: parsedTime.minute)
                val label = if (matchedMed != null) {
                    "Uống ${matchedMed.name} (${matchedMed.dosage})"
                } else {
                    extractCustomAlarmLabel(clause).ifBlank { "Nhắc uống thuốc lúc ${String.format("%02d:%02d", h, m)}" }
                }
                results.add(Triple(h, m, label))
            }
        }

        if (results.size >= 2) return results.distinctBy { "${it.first}:${it.second}:${it.third}" }

        // Nếu 2 loại thuốc được nhắc chung mà chưa tách theo mệnh đề, tạo từng chuông theo giờ mặc định của từng thuốc
        if (grounding.matchedMedications.size >= 2) {
            return grounding.matchedMedications.map { med ->
                Triple(med.default_hour, med.default_minute, "Uống ${med.name} (${med.dosage})")
            }
        }
        return emptyList()
    }

    private fun extractPhoneNumberFromText(text: String): String {
        val phoneRegex = Regex("(?:\\+84|0)(?:\\d[\\s\\.-]?){8,10}")
        val match = phoneRegex.find(text)?.value ?: return ""
        return match.replace(Regex("[^0-9+]"), "")
    }

    private fun extractDynamicRecipientName(text: String): String {
        val regex = Regex("(?:gọi điện thoại cho|gọi điện cho|gọi cho|nhắn tin zalo cho|nhắn zalo cho|nhắn sms cho|nhắn tin cho|nhắn cho|báo cho|biểu|bẩu|kêu)\\s+([^,\\.;]+?)(?:\\s+(?:bảo|nhắn|rằng|là|hỏi|biết|ra|qua|về|lúc|số|đem|mang)|$|,)", RegexOption.IGNORE_CASE)
        val candidate = regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return candidate.takeIf { it.length in 2..35 } ?: ""
    }

    private fun extractCustomMessageContent(text: String): String {
        val regex = Regex("(?:bảo là|nhắn là|nội dung là|bảo rằng|nhắn rằng|bảo|hỏi thăm|hỏi xem)\\s+([^\\.]+)$", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun extractCustomDestinationOrLocation(text: String): String {
        val navRegex = Regex("(?:chỉ đường đi|chỉ đường tới|chỉ đường qua|chỉ đường đến|chỉ đường ra|dẫn đường đi|dẫn đường tới|dẫn đường đến|mở bản đồ đi|mở bản đồ tới|mở bản đồ qua|đặt xe đi|đặt xe tới|đặt xe qua|đặt xe ra|bắt xe qua|bắt xe đi|bắt xe ra|đi bộ ra|qua nhà|ghé nhà|đến nhà|tới địa chỉ|ở tại|tại)\\s+([^,\\.;]+)", RegexOption.IGNORE_CASE)
        val rawLoc = navRegex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return rawLoc
            .replace(Regex("\\s+(?:giùm|nhé|nha|nghe|với|rồi|chơi).*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractCustomAlarmLabel(text: String): String {
        val regex = Regex("(?:nhắc bác|nhắc tôi|nhắc mình|nhắc|để)\\s+([^,\\.;]+)", RegexOption.IGNORE_CASE)
        val raw = regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return raw
            .replace(Regex("^\\d{1,2}\\s*(?:giờ|h|rưỡi|phút|\\s)+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:nhé|nha|nghe con|giùm bác).*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun extractCustomCalendarTitle(text: String): String {
        val regex = Regex("(?:ghi lịch|thêm vào lịch|ghi vào lịch|thêm vô lịch|ghi vô lịch|đặt lịch|tạo lịch)\\s+([^,\\.;]+)", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun selectTargetContactForCommunication(
        lowerText: String,
        grounding: GroundingAnalysisResult
    ): com.khkt.seniorvoice.context.FamilyContact? {
        if (grounding.matchedContacts.size <= 1) return grounding.matchedContacts.firstOrNull()
        val commRegex = Regex("(?:nhắn|gọi|báo|biểu|bẩu|kêu)[^,\\.]*")
        val commClause = commRegex.find(lowerText)?.value ?: lowerText
        return grounding.matchedContacts.firstOrNull { c ->
            c.nicknames.any { nick -> commClause.contains(nick.lowercase()) }
        } ?: grounding.matchedContacts.last()
    }

    /**
     * Soạn tin nhắn tự nhiên đúng vai vế xưng hô gia đình Việt Nam (M10):
     * - Với Con trai / Con gái: xưng "Ba/Bác", gọi "con".
     * - Với Em ruột (chú Tư), Bạn già (bà Bảy), Bác sĩ (BS. Hùng): xưng tên "Bác Ba", gọi đúng danh xưng ("chú Tư", "bà Bảy", "bác sĩ Hùng").
     */
    private fun composeNaturalFamilyMessage(
        contact: com.khkt.seniorvoice.context.FamilyContact?,
        place: com.khkt.seniorvoice.context.FamiliarPlace?,
        dateDisplay: String,
        lunarDisplay: String,
        timeStr: String,
        rawUtterance: String,
        customExtractedMsg: String
    ): String {
        val nick = contact?.nicknames?.firstOrNull() ?: "con"
        val rel = contact?.relationship?.lowercase().orEmpty()
        val isChild = rel.contains("con") || nick.lowercase().startsWith("con ") || nick.lowercase().startsWith("thằng ") || nick.lowercase().startsWith("chị ")
        val senderSelf = if (isChild) "Ba" else "Bác Ba"
        val receiverCall = if (isChild) "con" else nick

        return when {
            place != null -> "Ngày $dateDisplay ($lunarDisplay) lúc $timeStr $senderSelf có lịch qua ${place.full_name} (${place.address}), nhắn $receiverCall biết nhé."
            customExtractedMsg.isNotBlank() -> {
                val cleaned = customExtractedMsg.replaceFirstChar { it.uppercase() }
                if (!isChild) "$senderSelf nhắn $receiverCall: $cleaned" else cleaned
            }
            rawUtterance.lowercase().contains("khỏe") -> "$senderSelf vẫn khỏe và uống thuốc đều đặn, $receiverCall yên tâm nhé."
            rawUtterance.lowercase().contains("mua") -> "Nhớ mua thêm lọ thuốc định kỳ giúp $senderSelf nhé $receiverCall."
            else -> rawUtterance
        }
    }

    private fun buildNaturalSpokenResponse(
        steps: List<PlanStep>,
        dateDisplay: String,
        grounding: GroundingAnalysisResult
    ): String {
        val titlePrefix = familyRepository.getMemoryData().senior_profile.preferred_title.ifBlank { "bác" }
        val safeSteps = steps.filter { it.risk == RiskLevel.SAFE }
        val sensitiveSteps = steps.filter { it.risk == RiskLevel.SENSITIVE }

        val safeSummary = if (safeSteps.isNotEmpty()) {
            "Dạ thưa $titlePrefix, con đã tự động thực hiện ${safeSteps.size} việc an toàn: " +
                    safeSteps.joinToString(", ") { it.titleVi.lowercase() } + ". "
        } else {
            "Dạ thưa $titlePrefix, "
        }

        val sensitiveSummary = if (sensitiveSteps.isNotEmpty()) {
            val actionNames = sensitiveSteps.joinToString(" và ") { it.titleVi }
            "Riêng thao tác $actionNames là thao tác quan trọng, $titlePrefix nói 'Đồng ý' hoặc kiểm tra/chỉnh sửa thông tin bên dưới rồi bấm nút Xác nhận màu xanh nhé ạ!"
        } else {
            "Tất cả yêu cầu ngày $dateDisplay (${grounding.resolvedDateTime.lunarDate.formatVi()}) đã hoàn tất ạ!"
        }

        return safeSummary + sensitiveSummary
    }

    private fun buildConfirmationPrompt(steps: List<PlanStep>): String {
        val sens = steps.filter { it.risk == RiskLevel.SENSITIVE }
        return "Phanh An Toàn: Xác nhận thực hiện ${sens.joinToString(" & ") { it.titleVi }}?"
    }

    fun mapToolToVietnameseTitle(tool: String, params: Map<String, String>): String {
        return when (tool) {
            "check_weather" -> "Kiểm tra thời tiết (${params["location_name"] ?: params["date"] ?: "hôm nay"})"
            "set_medication_alarm" -> "Đặt báo thức ${params["hour"]?.padStart(2, '0') ?: "06"}:${params["minute"]?.padStart(2, '0') ?: "30"}"
            "insert_calendar_event" -> "Ghi Lịch: ${params["title"] ?: "Lịch hẹn"}"
            "call_or_message_family" -> {
                val ch = params["channel"] ?: "SMS"
                val name = params["contact_name"] ?: params["nickname"] ?: "Người nhận"
                when (ch.uppercase()) {
                    "CALL" -> "Gọi điện thoại cho $name"
                    "ZALO" -> "Nhắn tin Zalo cho $name"
                    else -> "Gửi tin nhắn SMS cho $name"
                }
            }
            "navigate_or_book_ride" -> {
                val mode = if (params["mode"] == "RIDE") "Mở ứng dụng Đặt xe" else "Mở Bản đồ dẫn đường"
                "$mode tới ${params["destination_name"] ?: params["address"] ?: "điểm đến"}"
            }
            else -> tool
        }
    }

    fun mapToolToVietnameseDescription(tool: String, params: Map<String, String>): String {
        return when (tool) {
            "set_medication_alarm" -> "Giờ: ${params["hour"]?.padStart(2, '0')}:${params["minute"]?.padStart(2, '0')} • Nhãn: \"${params["label"] ?: ""}\""
            "insert_calendar_event" -> "Ngày ${params["date"]} lúc ${params["time"]} • Địa điểm: ${params["location"] ?: ""}"
            "call_or_message_family" -> "SĐT: ${params["phone"]?.ifBlank { "Chưa nhập" }} • Kênh: ${params["channel"]} • Tin nhắn: \"${params["message"] ?: ""}\""
            "navigate_or_book_ride" -> {
                val base = "Địa chỉ: ${params["address"] ?: params["destination_name"] ?: ""}"
                val note = params["note"].orEmpty()
                if (note.isNotBlank()) "$base • $note" else base
            }
            else -> params.entries.joinToString(" • ") { "${it.key}: ${it.value}" }
        }
    }
}
