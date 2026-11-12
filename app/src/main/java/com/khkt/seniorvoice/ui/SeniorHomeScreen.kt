package com.khkt.seniorvoice.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khkt.seniorvoice.agent.AgentPlan
import com.khkt.seniorvoice.agent.PlanStep
import com.khkt.seniorvoice.agent.RiskLevel
import com.khkt.seniorvoice.agent.StepStatus
import com.khkt.seniorvoice.context.LunarCalendarConverter
import com.khkt.seniorvoice.context.SeniorProfile
import java.time.LocalDate

private val PrimaryTeal = Color(0xFF0B3C49)
private val LightBg = Color(0xFFFDFBF7)
private val SafeGreen = Color(0xFF15803D)
private val WarnAmber = Color(0xFFB45309)
private val WarnAmberBg = Color(0xFFFEF3C7)
private val DangerRed = Color(0xFFB91C1C)

/**
 * Màn hình chính Tối Giản (Minimalist Senior UI):
 * 1. Thanh chào + Lịch Âm Dương gọn gàng
 * 2. Nút Micro lớn ở giữa
 * 3. Ô nhập lệnh tự do
 * 4. Hộp xác nhận ĐỒNG Ý / HỦY BỎ & Danh sách bước ngắn gọn
 */
@Composable
fun SeniorHomeScreen(
    seniorProfile: SeniorProfile,
    isListening: Boolean,
    isSpeaking: Boolean,
    isTtsAvailable: Boolean = true,
    partialSpeechText: String,
    statusMessage: String,
    currentPlan: AgentPlan?,
    isAwaitingConfirmation: Boolean,
    onToggleMic: () -> Unit,
    onRunUtterance: (String) -> Unit,
    onUpdateStepParams: (stepId: Int, newParams: Map<String, String>, executeNow: Boolean) -> Unit,
    onConfirmSensitive: () -> Unit,
    onCancelSensitive: () -> Unit,
    onSpeakAgain: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    var userInputText by remember { mutableStateOf("") }

    LaunchedEffect(partialSpeechText) {
        if (partialSpeechText.isNotBlank()) {
            userInputText = partialSpeechText
        }
    }

    val todaySolar = remember { LocalDate.now() }
    val todayLunar = remember(todaySolar) { LunarCalendarConverter.solarToLunar(todaySolar) }

    val greeting = remember(seniorProfile) {
        val t = seniorProfile.preferred_title.trim().ifBlank { "Bác" }
        val n = seniorProfile.full_name.trim()
        if (n.isNotBlank()) "Chào $t $n" else "Chào $t"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Thanh tiêu đề đơn giản: Tên + Ngày Dương & Âm lịch
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PrimaryTeal,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = greeting,
                    color = Color(0xFFFFD166),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Dương lịch: ${LunarCalendarConverter.formatDisplayDate(todaySolar)} • ${todayLunar.formatVi()}",
                    color = Color.White,
                    fontSize = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isTtsAvailable) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                color = WarnAmberBg,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, WarnAmber)
            ) {
                Text(
                    text = "Thiết bị chưa cài gói giọng đọc Tiếng Việt (TTS). Cháu hiển thị chữ lớn trên màn hình để bác theo dõi nhé!",
                    color = Color(0xFF78350F),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // 2. Nút Micro lớn ở trung tâm
        SimpleMicButton(
            isListening = isListening,
            isSpeaking = isSpeaking,
            isAwaitingConfirmation = isAwaitingConfirmation,
            statusMessage = statusMessage,
            onClick = onToggleMic
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Ô nhập lệnh đơn giản (tự gõ hoặc tự điền khi nói)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = userInputText,
                onValueChange = { userInputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Bấm Micro nói hoặc nhập yêu cầu tại đây...",
                        fontSize = 16.sp,
                        color = Color(0xFF94A3B8)
                    )
                },
                trailingIcon = {
                    if (userInputText.isNotBlank()) {
                        IconButton(onClick = { userInputText = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Xóa")
                        }
                    }
                },
                textStyle = TextStyle(
                    fontSize = 18.sp,
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Medium
                ),
                shape = RoundedCornerShape(14.dp),
                maxLines = 3
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = userInputText.trim()
                    if (text.isNotBlank()) {
                        onRunUtterance(text)
                    }
                },
                enabled = userInputText.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi")
            }
        }

        // 4. Hộp xác nhận ĐỒNG Ý / HỦY BỎ (Chỉ hiện khi có thao tác nhạy cảm)
        if (isAwaitingConfirmation && currentPlan != null) {
            Spacer(modifier = Modifier.height(16.dp))
            SimpleSafetyConfirmBox(
                promptText = currentPlan.confirmationPrompt
                    ?: "Xác nhận thực hiện thao tác này?",
                onConfirm = onConfirmSensitive,
                onCancel = onCancelSensitive
            )
        }

        // 5. Kết quả thực hiện (Ngắn gọn, rõ ràng)
        if (currentPlan != null) {
            Spacer(modifier = Modifier.height(16.dp))

            // Câu trả lời của AI
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                border = BorderStroke(1.5.dp, SafeGreen)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Trợ lý AI:",
                            color = SafeGreen,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        OutlinedButton(
                            onClick = { onSpeakAgain(currentPlan.spokenResponse) },
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = SafeGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Đọc lại", color = SafeGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentPlan.spokenResponse,
                        color = Color(0xFF064E3B),
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Các bước thực hiện
            currentPlan.steps.forEach { step ->
                SimplePlanStepItem(
                    step = step,
                    onSaveAndExecute = { updatedParams, runNow ->
                        onUpdateStepParams(step.stepId, updatedParams, runNow)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SimpleMicButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    isAwaitingConfirmation: Boolean,
    statusMessage: String,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.1f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isListening -> DangerRed
            isAwaitingConfirmation -> WarnAmber
            isSpeaking -> Color(0xFF0284C7)
            else -> PrimaryTeal
        },
        label = "color"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(144.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(buttonColor.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
                .clickable { onClick() }
        ) {
            Surface(
                modifier = Modifier.size(118.dp),
                shape = CircleShape,
                color = buttonColor,
                shadowElevation = 8.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "Micro",
                        tint = Color.White,
                        modifier = Modifier.size(46.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isListening) "ĐANG NGHE" else "CHẠM ĐỂ NÓI",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = statusMessage,
            color = if (isListening) DangerRed else PrimaryTeal,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SimpleSafetyConfirmBox(
    promptText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WarnAmberBg),
        border = BorderStroke(2.5.dp, WarnAmber)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = promptText,
                color = Color(0xFF78350F),
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ĐỒNG Ý", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                }

                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("HỦY BỎ", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun SimplePlanStepItem(
    step: PlanStep,
    onSaveAndExecute: (Map<String, String>, Boolean) -> Unit
) {
    var isEditing by remember(step.stepId, step.params) {
        mutableStateOf(step.tool == "call_or_message_family" && step.params["phone"].isNullOrBlank())
    }

    val statusColor = when (step.status) {
        StepStatus.COMPLETED, StepStatus.CONFIRMED -> SafeGreen
        StepStatus.WAITING_CONFIRMATION -> WarnAmber
        StepStatus.CANCELLED, StepStatus.FAILED -> DangerRed
        else -> Color(0xFF64748B)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, statusColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = toolIcon(step.tool),
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${step.stepId}. ${step.titleVi}",
                        color = Color(0xFF0F172A),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = { isEditing = !isEditing },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Sửa",
                        tint = PrimaryTeal,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = step.descriptionVi,
                color = Color(0xFF475569),
                fontSize = 14.sp
            )

            AnimatedVisibility(visible = isEditing) {
                CompactStepEditor(
                    step = step,
                    onApply = { updated, runNow ->
                        isEditing = false
                        onSaveAndExecute(updated, runNow)
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusIcon = when (step.status) {
                    StepStatus.COMPLETED, StepStatus.CONFIRMED -> Icons.Default.CheckCircle
                    StepStatus.CANCELLED, StepStatus.FAILED -> Icons.Default.Cancel
                    else -> Icons.Default.HourglassTop
                }
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = step.executionResult ?: if (step.risk == RiskLevel.SAFE) "Đã xong" else "Chờ xác nhận",
                    color = statusColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun CompactStepEditor(
    step: PlanStep,
    onApply: (Map<String, String>, Boolean) -> Unit
) {
    val currentParams = step.params
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        when (step.tool) {
            "set_medication_alarm" -> {
                var hour by remember(step) { mutableStateOf(currentParams["hour"] ?: "6") }
                var minute by remember(step) { mutableStateOf(currentParams["minute"] ?: "30") }
                var label by remember(step) { mutableStateOf(currentParams["label"] ?: "") }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = hour, onValueChange = { hour = it }, label = { Text("Giờ") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = minute, onValueChange = { minute = it }, label = { Text("Phút") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Nhãn báo thức") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onApply(mapOf("hour" to hour, "minute" to minute, "label" to label), true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) { Text("Đặt báo thức ngay") }
            }

            "insert_calendar_event" -> {
                var title by remember(step) { mutableStateOf(currentParams["title"] ?: "") }
                var date by remember(step) { mutableStateOf(currentParams["date"] ?: LocalDate.now().toString()) }
                var time by remember(step) { mutableStateOf(currentParams["time"] ?: "07:30") }
                var location by remember(step) { mutableStateOf(currentParams["location"] ?: "") }

                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Tiêu đề lịch") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Ngày (YYYY-MM-DD)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Giờ (HH:mm)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Địa điểm") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        onApply(mapOf("title" to title, "date" to date, "time" to time, "location" to location, "description" to title), true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) { Text("Lưu vào Lịch ngay") }
            }

            "call_or_message_family" -> {
                var name by remember(step) { mutableStateOf(currentParams["contact_name"] ?: "") }
                var phone by remember(step) { mutableStateOf(currentParams["phone"] ?: "") }
                var channel by remember(step) { mutableStateOf(currentParams["channel"] ?: "SMS") }
                var message by remember(step) { mutableStateOf(currentParams["message"] ?: "") }

                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Người nhận") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Số điện thoại") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CALL" to "Gọi", "SMS" to "SMS", "ZALO" to "Zalo").forEach { (ch, lb) ->
                        FilterChip(selected = channel.equals(ch, true), onClick = { channel = ch }, label = { Text(lb) })
                    }
                }
                if (!channel.equals("CALL", true)) {
                    OutlinedTextField(value = message, onValueChange = { message = it }, label = { Text("Tin nhắn") }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        onApply(mapOf("nickname" to name, "contact_name" to name, "phone" to phone, "channel" to channel, "message" to message), true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) { Text("Lưu & Thực hiện ngay") }
            }

            "navigate_or_book_ride" -> {
                var address by remember(step) { mutableStateOf(currentParams["address"] ?: "") }
                var mode by remember(step) { mutableStateOf(currentParams["mode"] ?: "MAPS") }

                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Điểm đến / Địa chỉ") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "MAPS", onClick = { mode = "MAPS" }, label = { Text("Google Maps") })
                    FilterChip(selected = mode == "RIDE", onClick = { mode = "RIDE" }, label = { Text("Đặt xe") })
                }
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { onApply(mapOf("destination_name" to address, "address" to address, "mode" to mode), true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) { Text("Mở ngay") }
            }

            else -> {
                var locName by remember(step) { mutableStateOf(currentParams["location_name"] ?: "TP. Hồ Chí Minh") }
                OutlinedTextField(value = locName, onValueChange = { locName = it }, label = { Text("Tỉnh / Thành phố") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        val updated = currentParams.toMutableMap().apply { put("location_name", locName) }
                        onApply(updated, true)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) { Text("Xem thời tiết") }
            }
        }
    }
}

private fun toolIcon(tool: String): ImageVector {
    return when (tool) {
        "check_weather" -> Icons.Default.CloudQueue
        "set_medication_alarm" -> Icons.Default.Alarm
        "insert_calendar_event" -> Icons.Default.CalendarMonth
        "call_or_message_family" -> Icons.Default.ContactPhone
        "navigate_or_book_ride" -> Icons.Default.DirectionsCar
        else -> Icons.Default.PlayArrow
    }
}
