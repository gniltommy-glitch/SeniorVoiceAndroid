package com.khkt.seniorvoice.agent

import com.khkt.seniorvoice.executor.AndroidAppExecutor
import com.khkt.seniorvoice.executor.WeatherReport
import com.khkt.seniorvoice.speech.VietnameseSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tầng 3: Bộ Quản Lý Phanh An Toàn Hai Bước (Two-Stage Acoustic Safety Verification) & Vòng lặp ReAct Thời tiết Động
 * - Hỗ trợ rẽ nhánh kế hoạch động dựa trên kết quả thực tế từ check_weather (Dynamic ReAct Weather Branching - Feature 5, T9).
 * - Xử lý chặt chẽ câu trả lời phủ định ("không đồng ý" -> Hủy - T5) và câu do dự ("đồng ý... à thôi" -> Hỏi lại - T6).
 */
class SafetyGuardManager(
    private val scope: CoroutineScope,
    private val appExecutor: AndroidAppExecutor,
    private val speechManager: VietnameseSpeechManager,
    private val llmPlanner: ApiLlmPlanner
) {
    private val _currentPlan = MutableStateFlow<AgentPlan?>(null)
    val currentPlan: StateFlow<AgentPlan?> = _currentPlan.asStateFlow()

    private val _isAwaitingConfirmation = MutableStateFlow(false)
    val isAwaitingConfirmation: StateFlow<Boolean> = _isAwaitingConfirmation.asStateFlow()

    private val _executionLog = MutableStateFlow<List<String>>(emptyList())
    val executionLog: StateFlow<List<String>> = _executionLog.asStateFlow()

    fun executePlanWithSafetyGuard(plan: AgentPlan) {
        scope.launch {
            var workingPlan = plan
            _currentPlan.value = workingPlan
            _isAwaitingConfirmation.value = false
            val logs = mutableListOf<String>()
            var weatherSummaryToSpeak: String? = null
            var latestWeatherReport: WeatherReport? = null

            val updatedSteps = workingPlan.steps.toMutableList()

            for (i in updatedSteps.indices) {
                val step = updatedSteps[i]
                if (step.risk == RiskLevel.SAFE) {
                    updatedSteps[i] = step.copy(status = StepStatus.RUNNING)
                    _currentPlan.value = workingPlan.copy(steps = updatedSteps.toList())

                    val result = appExecutor.executeStep(step, dryRunForBenchmark = false)
                    if (step.tool == "check_weather") {
                        weatherSummaryToSpeak = result.summaryVi
                        latestWeatherReport = result.weatherReport
                    }

                    updatedSteps[i] = step.copy(
                        status = if (result.success) StepStatus.COMPLETED else StepStatus.FAILED,
                        executionResult = result.summaryVi
                    )
                    logs.add("✓ [SAFE] ${step.titleVi}: ${result.summaryVi}")
                    _executionLog.value = logs.toList()
                    workingPlan = workingPlan.copy(steps = updatedSteps.toList())
                    _currentPlan.value = workingPlan
                } else {
                    updatedSteps[i] = step.copy(
                        status = StepStatus.WAITING_CONFIRMATION,
                        executionResult = "Đang tạm dừng bởi Phanh An Toàn – Chờ xác nhận hoặc chỉnh sửa tham số."
                    )
                }
            }

            workingPlan = workingPlan.copy(steps = updatedSteps.toList())

            // Vòng lặp ReAct Động: Tự động điều chỉnh bước Đặt xe / Tập dưỡng sinh theo kết quả Thời tiết thực tế (Feature 5, T9)
            if (latestWeatherReport != null) {
                workingPlan = processWeatherConditionResult(workingPlan, latestWeatherReport)
            }

            val finalSteps = workingPlan.steps
            val hasWaitingSensitive = finalSteps.any { it.status == StepStatus.WAITING_CONFIRMATION }
            _isAwaitingConfirmation.value = hasWaitingSensitive

            val finalSpokenText = buildString {
                if (!weatherSummaryToSpeak.isNullOrBlank()) {
                    append("Dạ dự báo thời tiết: $weatherSummaryToSpeak ")
                }
                append(workingPlan.spokenResponse)
            }

            _currentPlan.value = workingPlan.copy(
                spokenResponse = finalSpokenText,
                steps = finalSteps
            )

            speechManager.speakVietnamese(
                text = finalSpokenText,
                autoListenForConfirmation = hasWaitingSensitive
            )
        }
    }

    /**
     * Điều chỉnh kế hoạch động (Dynamic ReAct Branching) sau khi nhận kết quả Thời tiết từ Open-Meteo
     */
    fun processWeatherConditionResult(plan: AgentPlan, weatherReport: WeatherReport): AgentPlan {
        val rainProb = weatherReport.rainProbability
        val lowerUtterance = plan.rawUtterance.lowercase()
        val hasConditionalExercise = lowerUtterance.contains("không mưa") ||
                lowerUtterance.contains("nếu mưa") ||
                lowerUtterance.contains("tập dưỡng sinh") ||
                lowerUtterance.contains("tập thể dục")

        if (rainProb >= 60) {
            val updatedSteps = plan.steps.map { step ->
                if (step.tool == "navigate_or_book_ride") {
                    val newParams = step.params.toMutableMap().apply {
                        put("mode", "RIDE")
                        put("ride_type", "CAR_REQUIRED")
                        put("note", "Khả năng mưa cao ($rainProb%), ưu tiên đặt Xe Ô Tô 4 chỗ có mái che an toàn cho bác")
                    }
                    step.copy(
                        titleVi = llmPlanner.mapToolToVietnameseTitle(step.tool, newParams),
                        descriptionVi = llmPlanner.mapToolToVietnameseDescription(step.tool, newParams),
                        params = newParams
                    )
                } else {
                    step
                }
            }
            val extraRainWarning = "Lưu ý quan trọng: Do dự báo có khả năng mưa cao ($rainProb%), con đã tự động chuyển phương án di chuyển sang ưu tiên đặt Xe Ô Tô 4 chỗ kín gió và nhắc bác mang theo dù nhé ạ!"
            return plan.copy(
                steps = updatedSteps,
                spokenResponse = "${plan.spokenResponse} $extraRainWarning"
            )
        } else if (hasConditionalExercise) {
            val clearSkyNote = "Vì dự báo tại ${weatherReport.locationName} khung giờ ${weatherReport.targetHour} trời tạnh ráo (khả năng mưa chỉ $rainProb%, nhiệt độ ${weatherReport.hourlyTemperature.toInt()}°C), con đã giữ lịch báo thức để bác yên tâm ra công viên tập dưỡng sinh nhé ạ!"
            return plan.copy(
                spokenResponse = "${plan.spokenResponse} $clearSkyNote"
            )
        }
        return plan
    }

    /**
     * Cho phép người dùng tự sửa trực tiếp tham số của bất kỳ bước nào (giờ báo thức, lịch, SĐT, nội dung tin nhắn, địa chỉ)
     * và thực thi ngay nếu muốn!
     */
    fun updateStepParameters(stepId: Int, newParams: Map<String, String>, executeImmediately: Boolean) {
        val plan = _currentPlan.value ?: return
        scope.launch {
            val updatedSteps = plan.steps.toMutableList()
            val index = updatedSteps.indexOfFirst { it.stepId == stepId }
            if (index < 0) return@launch

            val oldStep = updatedSteps[index]
            val newTitle = llmPlanner.mapToolToVietnameseTitle(oldStep.tool, newParams)
            val newDesc = llmPlanner.mapToolToVietnameseDescription(oldStep.tool, newParams)

            var modifiedStep = oldStep.copy(
                titleVi = newTitle,
                descriptionVi = newDesc,
                params = newParams
            )

            if (executeImmediately) {
                modifiedStep = modifiedStep.copy(status = StepStatus.RUNNING)
                updatedSteps[index] = modifiedStep
                _currentPlan.value = plan.copy(steps = updatedSteps.toList())

                val result = appExecutor.executeStep(modifiedStep, dryRunForBenchmark = false)
                modifiedStep = modifiedStep.copy(
                    status = if (oldStep.risk == RiskLevel.SENSITIVE) StepStatus.CONFIRMED else StepStatus.COMPLETED,
                    executionResult = result.summaryVi
                )
            }

            updatedSteps[index] = modifiedStep
            _isAwaitingConfirmation.value = updatedSteps.any { it.status == StepStatus.WAITING_CONFIRMATION }
            _currentPlan.value = plan.copy(steps = updatedSteps.toList())
        }
    }

    /**
     * Xử lý xác nhận bằng giọng nói hai bước (M3, T5, T6):
     * 1. Bắt mẫu câu do dự / đổi ý giữa chừng ("đồng ý... à thôi") -> Hỏi lại, tuyệt đối không tự chạy (T6).
     * 2. Bắt mẫu câu phủ định ("không đồng ý", "chưa đồng ý", "hủy", "thôi", "không") TRƯỚC khi kiểm tra "đồng ý" (T5).
     * 3. Chỉ khi khẳng định rõ ràng mới thực thi.
     */
    fun handleVoiceConfirmationResponse(spokenReply: String): Boolean {
        if (!_isAwaitingConfirmation.value) return false
        val lower = spokenReply.lowercase().trim()

        val hasAffirmativeWord = listOf("đồng ý", "ok", "được", "gửi", "gọi").any { lower.contains(it) }
        val hasHesitationOrReversal = listOf("à thôi", "mà thôi", "nhưng thôi", "khoan đã", "từ từ", "đợi chút").any { lower.contains(it) }

        // Trường hợp T6: Ông bà nói "Đồng ý... à thôi" hoặc ngập ngừng đổi ý -> Tạm dừng và hỏi lại rõ ràng
        if (hasAffirmativeWord && hasHesitationOrReversal) {
            speechManager.speakVietnamese(
                text = "Dạ bác vừa nói đổi ý 'à thôi', nên con vẫn đang tạm dừng chưa gửi đi ạ. Nếu bác muốn hủy hẳn hãy nói 'Hủy bỏ', hoặc nói 'Đồng ý' nếu muốn thực hiện nhé ạ!",
                autoListenForConfirmation = true
            )
            return true
        }

        // Trường hợp T5 & M3: Kiểm tra từ khóa phủ định TRƯỚC từ khóa đồng ý (tránh lỗi "không đồng ý" khớp nhầm "đồng ý")
        val cancelKeywords = listOf(
            "không đồng ý", "chưa đồng ý", "không gửi", "không gọi",
            "hủy", "thôi", "không", "dừng", "đừng", "bỏ qua", "sai rồi", "khoan"
        )
        if (cancelKeywords.any { lower.contains(it) }) {
            cancelPendingSensitiveSteps()
            return true
        }

        val agreedKeywords = listOf(
            "đồng ý", "ok", "ừ", "được", "gửi đi", "gửi luôn", "gọi luôn",
            "mở luôn", "chắc chắn", "đúng rồi", "làm đi", "nhất trí", "xác nhận"
        )
        if (lower.trimEnd('.', '!', '?') in agreedKeywords) {
            confirmAndExecuteSensitiveSteps()
            return true
        }

        speechManager.speakVietnamese(
            text = "Dạ vui lòng nói rõ 'Đồng ý' để thực hiện, hoặc nói 'Hủy bỏ' để dừng lại ạ!",
            autoListenForConfirmation = true
        )
        return true
    }

    fun confirmAndExecuteSensitiveSteps() {
        val plan = _currentPlan.value ?: return
        scope.launch {
            _isAwaitingConfirmation.value = false
            speechManager.stopListening()

            val updatedSteps = plan.steps.toMutableList()
            val logs = _executionLog.value.toMutableList()
            val executedNames = mutableListOf<String>()

            for (i in updatedSteps.indices) {
                val step = updatedSteps[i]
                if (step.status == StepStatus.WAITING_CONFIRMATION) {
                    updatedSteps[i] = step.copy(status = StepStatus.RUNNING)
                    _currentPlan.value = plan.copy(steps = updatedSteps.toList())

                    val result = appExecutor.executeStep(step, dryRunForBenchmark = false)
                    updatedSteps[i] = step.copy(
                        status = if (result.success) StepStatus.CONFIRMED else StepStatus.FAILED,
                        executionResult = result.summaryVi
                    )
                    executedNames.add(step.titleVi)
                    logs.add("✓ [ĐÃ XÁC NHẬN] ${step.titleVi}: ${result.summaryVi}")
                    _executionLog.value = logs.toList()
                    _currentPlan.value = plan.copy(steps = updatedSteps.toList())
                }
            }

            if (executedNames.isNotEmpty()) {
                speechManager.speakVietnamese(
                    text = "Dạ đã xác nhận đồng ý, con đã thực hiện ${executedNames.joinToString(" và ")} xong rồi ạ!",
                    autoListenForConfirmation = false
                )
            }
        }
    }

    fun cancelPendingSensitiveSteps() {
        val plan = _currentPlan.value ?: return
        scope.launch {
            _isAwaitingConfirmation.value = false
            speechManager.stopListening()

            val updatedSteps = plan.steps.map { step ->
                if (step.status == StepStatus.WAITING_CONFIRMATION) {
                    step.copy(
                        status = StepStatus.CANCELLED,
                        executionResult = "Đã hủy an toàn theo yêu cầu của người dùng."
                    )
                } else {
                    step
                }
            }
            val logs = _executionLog.value.toMutableList().apply {
                add("✗ [PHANH AN TOÀN] Đã hủy các thao tác nhạy cảm.")
            }
            _executionLog.value = logs
            _currentPlan.value = plan.copy(steps = updatedSteps)

            speechManager.speakVietnamese(
                text = "Dạ con đã hủy thao tác đó rồi ạ!",
                autoListenForConfirmation = false
            )
        }
    }
}
