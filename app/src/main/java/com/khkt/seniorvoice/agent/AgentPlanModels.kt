package com.khkt.seniorvoice.agent

enum class RiskLevel {
    SAFE,
    SENSITIVE
}

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    WAITING_CONFIRMATION,
    CONFIRMED,
    CANCELLED,
    FAILED
}

enum class ExecutionEngineMode {
    API_DIRECT, // HTTPS requests using encrypted provider keys on the installation
    API_PROXY   // Optional authenticated HTTPS API gateway

}

data class PlanStep(
    val stepId: Int,
    val tool: String,
    val titleVi: String,
    val descriptionVi: String,
    val risk: RiskLevel,
    val params: Map<String, String>,
    val status: StepStatus = StepStatus.PENDING,
    val executionResult: String? = null
)

data class GroundingItem(
    val category: String,       // "LỊCH ÂM -> DƯƠNG", "NGƯỜI THÂN", "ĐỊA ĐIỂM", "THUỐC ĐỊNH KỲ", "PHƯƠNG NGỮ"
    val rawTerm: String,        // "sáng mai rằm", "thằng Út", "viện Chợ Rẫy"
    val resolvedValue: String   // "24/11/2026 (15/10 Âm lịch)", "Người thân Út - ", ...
)

data class AgentPlan(
    val rawUtterance: String,
    val normalizedUtterance: String,
    val thought: String,
    val requiresConfirmation: Boolean,
    val spokenResponse: String,
    val confirmationPrompt: String?,
    val groundingItems: List<GroundingItem>,
    val steps: List<PlanStep>,
    val engineUsed: String,
    val latencyMs: Long
)

data class BenchmarkQueryItem(
    val id: Int,
    val region: String,
    val utterance: String,
    val expected_tools: List<String>,
    val expected_contact: String,
    val expected_place: String,
    val expected_lunar_tag: String,
    val expected_alarm: String,
    val has_sensitive_action: Boolean,
    val baseline_failure_reason: String
)

data class SingleBenchmarkEvaluation(
    val query: BenchmarkQueryItem,
    // Baseline (Không có Family-Context Grounding & Safety Guard)
    val baselineSlotCorrect: Boolean,
    val baselineMultiAppSuccess: Boolean,
    val baselineSafetyProtected: Boolean,
    val baselineTouchCount: Int,
    // SeniorVoice-Android (Đề xuất: Có Family-Context Grounding + Safety Guard)
    val proposedSlotCorrect: Boolean,
    val proposedMultiAppSuccess: Boolean,
    val proposedSafetyProtected: Boolean,
    val proposedTouchCount: Int,
    val generatedPlan: AgentPlan
)

data class BenchmarkSummaryReport(
    val totalQueries: Int,
    val evaluations: List<SingleBenchmarkEvaluation>,
    val baselineSlotAccuracy: Double,
    val proposedSlotAccuracy: Double,
    val baselineMultiAppRate: Double,
    val proposedMultiAppRate: Double,
    val baselineSafetyRate: Double,
    val proposedSafetyRate: Double,
    val baselineAvgTouches: Double,
    val proposedAvgTouches: Double,
    val averageLatencyMs: Long
)
