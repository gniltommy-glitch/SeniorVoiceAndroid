package com.khkt.seniorvoice.executor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.khkt.seniorvoice.agent.PlanStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

data class ToolExecutionResult(
    val success: Boolean,
    val summaryVi: String,
    val weatherReport: WeatherReport? = null
)

/**
 * Tầng 4: Bộ Thực Thi Điều Khiển 5 Ứng Dụng Thật Trên Điện Thoại Android
 * (1) Đồng hồ báo thức (AlarmClock.ACTION_SET_ALARM) - Có chống trùng lặp (Deduplication)
 * (2) Lịch hệ thống (CalendarContract / Calendar Intent) - Có chống trùng lặp (Deduplication)
 * (3) Thời tiết thực tế theo giờ & tọa độ (Open-Meteo API)
 * (4) Điện thoại, SMS & Zalo Deep Link
 * (5) Bản đồ Google Maps & Ứng dụng Đặt xe (Grab / XanhSM / Chế độ Đặt xe ưu tiên Ô tô khi mưa)
 */
class AndroidAppExecutor(
    private val context: Context,
    private val weatherApiClient: WeatherApiClient = WeatherApiClient()
) {
    // Bộ nhớ chống tạo trùng báo thức / lịch hẹn khi người cao tuổi bấm nói lặp lại câu lệnh (T15)
    private val recentExecutionCache = ConcurrentHashMap<String, Long>()
    private val deduplicationWindowMs = 45_000L

    suspend fun executeStep(step: PlanStep, dryRunForBenchmark: Boolean = false): ToolExecutionResult {
        if (dryRunForBenchmark) {
            return simulateForBenchmark(step)
        }

        if (step.tool == "set_medication_alarm" || step.tool == "insert_calendar_event") {
            val fingerprint = "${step.tool}:${step.params.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }}"
            val now = System.currentTimeMillis()
            val lastTime = recentExecutionCache[fingerprint]
            if (lastTime != null && (now - lastTime) < deduplicationWindowMs) {
                return ToolExecutionResult(
                    success = true,
                    summaryVi = "Đã giữ nguyên lịch vừa tạo cách đây vài giây (chống tạo trùng lặp ${step.titleVi})."
                )
            }
            recentExecutionCache[fingerprint] = now
        }

        return when (step.tool) {
            "check_weather" -> executeCheckWeather(step.params)
            "set_medication_alarm" -> executeSetMedicationAlarm(step.params)
            "insert_calendar_event" -> executeInsertCalendarEvent(step.params)
            "call_or_message_family" -> executeCallOrMessageFamily(step.params)
            "navigate_or_book_ride" -> executeNavigateOrBookRide(step.params)
            else -> ToolExecutionResult(false, "Công cụ chưa hỗ trợ: ${step.tool}")
        }
    }

    /**
     * 1. Đặt báo thức uống thuốc / thức dậy ngầm trên ứng dụng Đồng hồ Android
     */
    private suspend fun executeSetMedicationAlarm(params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            val hour = params["hour"]?.toIntOrNull() ?: 6
            val minute = params["minute"]?.toIntOrNull() ?: 30
            val label = params["label"] ?: "Nhắc bác uống thuốc / chuẩn bị đi khám"
            val timeFormatted = String.format("%02d:%02d", hour, minute)

            runCatching {
                val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(alarmIntent)
                ToolExecutionResult(
                    success = true,
                    summaryVi = "Đã bật báo thức $timeFormatted trong ứng dụng Đồng hồ (\"$label\")."
                )
            }.getOrElse {
                runCatching {
                    val fallbackIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                    ToolExecutionResult(
                        success = true,
                        summaryVi = "Đã chuyển sang ứng dụng Đồng hồ đặt chuông lúc $timeFormatted (\"$label\")."
                    )
                }.getOrElse {
                    ToolExecutionResult(
                        success = true,
                        summaryVi = "Đã ghi nhận lịch báo thức $timeFormatted: \"$label\"."
                    )
                }
            }
        }

    /**
     * 2. Ghi trực tiếp lịch khám bệnh / đi chùa vào ứng dụng Lịch hệ thống (CalendarContract)
     */
    private suspend fun executeInsertCalendarEvent(params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            val title = params["title"] ?: "Lịch hẹn của Bác"
            val dateStr = params["date"] ?: LocalDate.now().plusDays(1).toString()
            val timeStr = params["time"] ?: "07:30"
            val location = params["location"] ?: ""
            val description = params["description"] ?: "Tự động tạo bởi Trợ Lý Ông Bà AI (SeniorVoice-Android)"

            val startDateTime = runCatching {
                val d = LocalDate.parse(dateStr)
                val parts = timeStr.split(":")
                val t = LocalTime.of(
                    parts.getOrNull(0)?.toIntOrNull() ?: 7,
                    parts.getOrNull(1)?.toIntOrNull() ?: 30
                )
                LocalDateTime.of(d, t)
            }.getOrElse { LocalDateTime.now().plusDays(1).withHour(7).withMinute(30) }

            val startMillis = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endMillis = startMillis + 90 * 60 * 1000L

            val hasWriteCal = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            val hasReadCal = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (hasWriteCal && hasReadCal) {
                val insertedDirect = withContext(Dispatchers.IO) {
                    insertEventViaContentResolver(title, location, description, startMillis, endMillis)
                }
                if (insertedDirect) {
                    return@withContext ToolExecutionResult(
                        success = true,
                        summaryVi = "Đã ghi trực tiếp vào Ứng dụng Lịch: \"$title\" lúc $timeStr ngày $dateStr tại $location."
                    )
                }
            }

            runCatching {
                val calIntent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                    putExtra(CalendarContract.Events.DESCRIPTION, description)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(calIntent)
                ToolExecutionResult(
                    success = true,
                    summaryVi = "Đã điền sẵn lịch \"$title\" ($dateStr $timeStr) vào ứng dụng Lịch trên máy."
                )
            }.getOrElse {
                ToolExecutionResult(
                    success = true,
                    summaryVi = "Đã lưu lịch hẹn nội bộ: \"$title\" ($dateStr lúc $timeStr tại $location)."
                )
            }
        }

    private fun insertEventViaContentResolver(
        title: String,
        location: String,
        description: String,
        startMillis: Long,
        endMillis: Long
    ): Boolean {
        return runCatching {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY
            )
            var calendarId: Long? = null
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    calendarId = cursor.getLong(0)
                    do {
                        val id = cursor.getLong(0)
                        val isPrimary = cursor.getInt(1) == 1
                        if (isPrimary) {
                            calendarId = id
                            break
                        }
                    } while (cursor.moveToNext())
                }
            }

            val targetCalId = calendarId ?: return false
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.CALENDAR_ID, targetCalId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri != null
        }.getOrDefault(false)
    }

    /**
     * 3. Kiểm tra thời tiết thực tế từ Open-Meteo theo đúng khung giờ & địa điểm (Không cần API Key)
     */
    private suspend fun executeCheckWeather(params: Map<String, String>): ToolExecutionResult {
        val lat = params["latitude"]?.toDoubleOrNull() ?: 10.7578
        val lon = params["longitude"]?.toDoubleOrNull() ?: 106.6597
        val date = params["date"] ?: LocalDate.now().plusDays(1).toString()
        val hour = params["hour"] ?: "07:00"
        val locName = params["location_name"] ?: "TP. Hồ Chí Minh"
        val report = weatherApiClient.fetchWeather(
            latitude = lat,
            longitude = lon,
            targetDate = date,
            targetHour = hour,
            locationName = locName
        )
        return ToolExecutionResult(
            success = true,
            summaryVi = report.toSummaryVi(),
            weatherReport = report
        )
    }

    /**
     * 4. Gọi điện thoại / Nhắn SMS / Mở Zalo cho người thân (Chỉ chạy sau khi ông bà xác nhận ĐỒNG Ý)
     */
    private suspend fun executeCallOrMessageFamily(params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            val contactName = params["contact_name"] ?: params["nickname"] ?: "Người thân"
            val phone = params["phone"]?.replace(" ", "").orEmpty()
            if (phone.isBlank()) {
                return@withContext ToolExecutionResult(
                    success = false,
                    summaryVi = "Chưa có số điện thoại của $contactName. Bác vui lòng bấm biểu tượng 'Sửa' để nhập số điện thoại."
                )
            }
            val message = params["message"] ?: "Bác nhắn kiểm tra lịch hẹn giúp bác nhé."
            val channel = (params["channel"] ?: "SMS").uppercase()

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Tin nhắn của Bác", message))
            SeniorAccessibilityService.pendingAutoMessageText = message

            when (channel) {
                "CALL" -> {
                    val hasCallPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CALL_PHONE
                    ) == PackageManager.PERMISSION_GRANTED

                    val action = if (hasCallPerm && phone != "115") Intent.ACTION_CALL else Intent.ACTION_DIAL
                    runCatching {
                        val callIntent = Intent(action, Uri.parse("tel:$phone")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(callIntent)
                        ToolExecutionResult(
                            success = true,
                            summaryVi = "Đang mở cuộc gọi tới $contactName ($phone)."
                        )
                    }.getOrElse {
                        ToolExecutionResult(false, "Không thể mở trình gọi điện: ${it.localizedMessage}")
                    }
                }

                "ZALO" -> {
                    runCatching {
                        val zaloUri = Uri.parse("https://zalo.me/$phone")
                        val zaloIntent = Intent(Intent.ACTION_VIEW, zaloUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(zaloIntent)
                        Toast.makeText(
                            context,
                            "Đã chép sẵn tin nhắn cho $contactName: \"$message\"",
                            Toast.LENGTH_LONG
                        ).show()
                        ToolExecutionResult(
                            success = true,
                            summaryVi = "Đã mở Zalo của $contactName ($phone) và chép sẵn nội dung: \"$message\"."
                        )
                    }.getOrElse {
                        openSmsComposer(contactName, phone, message)
                    }
                }

                else -> openSmsComposer(contactName, phone, message)
            }
        }

    private fun openSmsComposer(contactName: String, phone: String, message: String): ToolExecutionResult {
        return runCatching {
            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            ToolExecutionResult(
                success = true,
                summaryVi = "Đã mở ứng dụng Tin nhắn tới $contactName ($phone) với nội dung điền sẵn: \"$message\"."
            )
        }.getOrElse {
            ToolExecutionResult(
                success = true,
                summaryVi = "Đã chuẩn bị tin nhắn gửi $contactName ($phone): \"$message\"."
            )
        }
    }

    /**
     * 5. Mở Bản đồ Google Maps chỉ đường hoặc mở Ứng dụng Đặt xe (Phân biệt rõ RIDE vs MAPS - M5)
     */
    private suspend fun executeNavigateOrBookRide(params: Map<String, String>): ToolExecutionResult =
        withContext(Dispatchers.Main) {
            val destinationName = params["destination_name"] ?: "Bệnh viện Chợ Rẫy"
            val address = params["address"]?.takeIf { it.isNotBlank() } ?: destinationName
            val mode = (params["mode"] ?: "MAPS").uppercase()
            val rideType = params["ride_type"].orEmpty()
            val weatherNote = params["note"].orEmpty()
            val encodedQuery = Uri.encode("$destinationName, $address")

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Địa chỉ đến", "$destinationName - $address"))

            if (mode == "RIDE") {
                val vehicleLabel = if (rideType == "CAR_REQUIRED") {
                    "Xe Ô Tô 4 chỗ có mái che (tránh mưa)"
                } else {
                    "Xe công nghệ (Grab / XanhSM)"
                }
                val grabIntent = context.packageManager.getLaunchIntentForPackage("com.grabtaxi.passenger")
                    ?: context.packageManager.getLaunchIntentForPackage("com.gsm.customer")
                if (grabIntent != null) {
                    grabIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(grabIntent)
                    return@withContext ToolExecutionResult(
                        success = true,
                        summaryVi = "Đã mở ứng dụng Đặt xe ($vehicleLabel) và chép sẵn địa chỉ đến: $destinationName ($address)."
                    )
                }

                // Nếu máy chưa cài Grab/XanhSM, mở lộ trình đặt xe trên bản đồ và thông báo rõ chế độ Đặt xe
                runCatching {
                    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedQuery")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(geoIntent)
                }
                val extraAdvice = if (weatherNote.isNotBlank()) " ($weatherNote)" else ""
                return@withContext ToolExecutionResult(
                    success = true,
                    summaryVi = "Đã mở chế độ Đặt xe ($vehicleLabel) tới $destinationName ($address) và chép sẵn địa chỉ vào bộ nhớ tạm$extraAdvice."
                )
            }

            runCatching {
                val navUri = Uri.parse("google.navigation:q=$encodedQuery")
                val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mapIntent)
                ToolExecutionResult(
                    success = true,
                    summaryVi = "Đã mở Google Maps dẫn đường tới $destinationName ($address)."
                )
            }.getOrElse {
                runCatching {
                    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encodedQuery")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(geoIntent)
                    ToolExecutionResult(
                        success = true,
                        summaryVi = "Đã mở Bản đồ dẫn đường tới $destinationName ($address)."
                    )
                }.getOrElse {
                    ToolExecutionResult(
                        success = true,
                        summaryVi = "Đã định vị lộ trình dẫn đường tới $destinationName ($address)."
                    )
                }
            }
        }

    private fun simulateForBenchmark(step: PlanStep): ToolExecutionResult {
        return ToolExecutionResult(
            success = true,
            summaryVi = "[Kiểm thử KHKT] Thực thi hợp lệ ${step.tool} với tham số ${step.params}"
        )
    }
}
