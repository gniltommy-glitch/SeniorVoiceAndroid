package com.khkt.seniorvoice.context

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.sin

data class LunarDate(
    val day: Int,
    val month: Int,
    val year: Int,
    val isLeapMonth: Boolean = false
) {
    fun formatVi(): String {
        val prefix = when (day) {
            1 -> "Mùng 1"
            in 2..10 -> "Mùng $day"
            15 -> "Rằm (15)"
            else -> "Ngày $day"
        }
        val leapStr = if (isLeapMonth) " nhuận" else ""
        return "$prefix tháng $month$leapStr Âm lịch ($day/$month/$year ÂL)"
    }
}

data class ResolvedDateTime(
    val solarDate: LocalDate,
    val lunarDate: LunarDate,
    val time: LocalTime,
    val matchedExpression: String,
    val explanationVi: String
)

/**
 * Thuật toán thiên văn Âm - Dương lịch Việt Nam (Múi giờ UTC+7)
 * Tích hợp:
 *  - Xử lý Tự sửa lời nói (Self-correction: "6 giờ... à thôi 7 giờ rưỡi")
 *  - Toán thời gian tương đối ("sớm trước 1 tiếng giờ khám 7 rưỡi" -> 06:30)
 *  - Chuẩn hóa chữ số tiếng Việt từ ASR ("hai giờ chiều" -> 14:00) không va chạm danh xưng ("chị Hai", "chú Tư", "bà Bảy")
 *  - Quy đổi Rằm / Mùng theo tháng Âm lịch cụ thể ("rằm tháng bảy", "mùng 1 tháng giêng", "23 tháng chạp")
 */
object LunarCalendarConverter {

    private const val TIMEZONE_OFFSET = 7.0
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val ISO_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val CAN = arrayOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")
    private val CHI = arrayOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Thân", "Dậu", "Tuất", "Hợi")

    private val SELF_CORRECTION_KEYWORDS = listOf(
        "à thôi", "à quên", "ý lộn", "nhầm rồi", "nhầm", "đổi lại",
        "thôi đổi", "sửa lại", "lấy lúc", "chuyển sang"
    )

    fun getCanChiYear(lunarYear: Int): String {
        val can = CAN[(lunarYear + 6) % 10]
        val chiAll = arrayOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")
        val chi = chiAll[(lunarYear + 8) % 12]
        return "$can $chi"
    }

    /**
     * Cắt bỏ mệnh đề giờ bị hủy phía trước từ khóa đính chính ("6 giờ... à thôi 7 giờ rưỡi" -> giữ phần sau "à thôi")
     */
    fun extractEffectiveClauseAfterSelfCorrection(text: String): String {
        val lower = text.lowercase()
        var lastIdx = -1
        var matchedKw = ""
        for (kw in SELF_CORRECTION_KEYWORDS) {
            val idx = lower.lastIndexOf(kw)
            if (idx > lastIdx) {
                lastIdx = idx
                matchedKw = kw
            }
        }
        if (lastIdx == -1) return text
        val prefixIntent = text.substring(0, lastIdx)
            .replace(Regex("\\d{1,2}\\s*(?:giờ|g|h|:)\\s*(?:\\d{1,2}|rưỡi)?(?:\\s*kém\\s*\\d{1,2})?\\s*(?:sáng|trưa|chiều|tối)?", RegexOption.IGNORE_CASE), " ")
        val correctedPart = text.substring(lastIdx + matchedKw.length)
        return "$prefixIntent $correctedPart".trim()
    }

    /**
     * Chuyển chữ số tiếng Việt ("hai giờ", "bảy giờ rưỡi", "mười một giờ") sang số Ả Rập
     * nhưng bảo vệ tuyệt đối các danh xưng gia đình ("chị Hai", "chú Tư", "bà Bảy", "ông Năm")
     */
    fun normalizeSpokenVietnameseNumbers(input: String): String {
        var s = input
        val numberWords = listOf(
            "mười hai" to "12",
            "mười một" to "11",
            "mười lăm" to "15",
            "hai mươi" to "20",
            "ba mươi" to "30",
            "bốn mươi lăm" to "45",
            "mười" to "10",
            "một" to "1",
            "hai" to "2",
            "ba" to "3",
            "bốn" to "4",
            "tư" to "4",
            "năm" to "5",
            "lăm" to "5",
            "sáu" to "6",
            "bảy" to "7",
            "tám" to "8",
            "chín" to "9"
        )
        for ((word, digit) in numberWords) {
            // Chỉ đổi khi theo sau là đơn vị thời gian (giờ, rưỡi, phút, tiếng) và phía trước KHÔNG phải là từ chỉ quan hệ gia đình
            val regex = Regex("(?<!chị |anh |bác |chú |ông |bà |cô |dì |cậu |mợ |thằng |con )\\b$word\\b(?=\\s*(?:giờ|rưỡi|phút|tiếng))", RegexOption.IGNORE_CASE)
            s = regex.replace(s, digit)
        }
        return s
    }

    fun jdFromDate(dd: Int, mm: Int, yy: Int): Int {
        val a = (14 - mm) / 12
        val y = yy + 4800 - a
        val m = mm + 12 * a - 3
        var jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
        if (jd < 2299161) {
            jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083
        }
        return jd
    }

    fun jdToDate(jd: Int): Triple<Int, Int, Int> {
        val a: Int
        val b: Int
        val c: Int
        if (jd > 2299160) {
            a = jd + 32044
            b = (4 * a + 3) / 146097
            c = a - (b * 146097) / 4
        } else {
            b = 0
            c = jd + 32082
        }
        val d = (4 * c + 3) / 1461
        val e = c - (1461 * d) / 4
        val m = (5 * e + 2) / 153
        val day = e - (153 * m + 2) / 5 + 1
        val month = m + 3 - 12 * (m / 10)
        val year = b * 100 + d - 4800 + m / 10
        return Triple(day, month, year)
    }

    private fun getNewMoonDay(k: Int, timeZone: Double): Int {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val dr = Math.PI / 180.0
        var jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd1 += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c1 = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c1 = c1 - 0.4068 * sin(mpr * dr) + 0.0161 * sin(dr * 2 * mpr)
        c1 -= 0.0004 * sin(dr * 3 * mpr)
        c1 = c1 + 0.0104 * sin(dr * 2 * f) - 0.0051 * sin(dr * (m + mpr))
        c1 = c1 - 0.0074 * sin(dr * (m - mpr)) + 0.0004 * sin(dr * (2 * f + m))
        c1 = c1 - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mpr))
        c1 += 0.0010 * sin(dr * (2 * f - mpr)) + 0.0005 * sin(dr * (2 * mpr + m))
        val deltat = if (t < -11) {
            0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
        } else {
            -0.000278 + 0.000265 * t + 0.000262 * t2
        }
        val jdNew = jd1 + c1 - deltat
        return floor(jdNew + 0.5 + timeZone / 24.0).toInt()
    }

    private fun getSunLongitude(jdn: Int, timeZone: Double): Int {
        val t = (jdn - 2451545.5 - timeZone / 24.0) / 36525.0
        val t2 = t * t
        val dr = Math.PI / 180.0
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        var dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m)
        dl += (0.019993 - 0.000101 * t) * sin(dr * 2 * m) + 0.000290 * sin(dr * 3 * m)
        var l = l0 + dl
        l *= dr
        l -= Math.PI * 2 * floor(l / (Math.PI * 2))
        return floor(l / Math.PI * 6).toInt()
    }

    private fun getLunarMonth11(yy: Int, timeZone: Double): Int {
        val off = jdFromDate(31, 12, yy) - 2415021
        val k = floor(off / 29.530588853).toInt()
        var nm = getNewMoonDay(k, timeZone)
        val sunLong = getSunLongitude(nm, timeZone)
        if (sunLong >= 9) {
            nm = getNewMoonDay(k - 1, timeZone)
        }
        return nm
    }

    private fun getLeapMonthOffset(a11: Int, timeZone: Double): Int {
        val k = floor((a11 - 2415021.076998695) / 29.530588853 + 0.5).toInt()
        var last: Int
        var i = 1
        var arc = getSunLongitude(getNewMoonDay(k + i, timeZone), timeZone)
        do {
            last = arc
            i++
            arc = getSunLongitude(getNewMoonDay(k + i, timeZone), timeZone)
        } while (arc != last && i < 14)
        return i - 1
    }

    fun solarToLunar(solarDate: LocalDate): LunarDate {
        val dd = solarDate.dayOfMonth
        val mm = solarDate.monthValue
        val yy = solarDate.year
        val dayNumber = jdFromDate(dd, mm, yy)
        val k = floor((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = getNewMoonDay(k + 1, TIMEZONE_OFFSET)
        if (monthStart > dayNumber) {
            monthStart = getNewMoonDay(k, TIMEZONE_OFFSET)
        }
        var a11 = getLunarMonth11(yy, TIMEZONE_OFFSET)
        var b11 = a11
        var lunarYear: Int
        if (a11 >= monthStart) {
            lunarYear = yy
            a11 = getLunarMonth11(yy - 1, TIMEZONE_OFFSET)
        } else {
            lunarYear = yy + 1
            b11 = getLunarMonth11(yy + 1, TIMEZONE_OFFSET)
        }
        val lunarDay = dayNumber - monthStart + 1
        val diff = floor((monthStart - a11) / 29.0).toInt()
        var lunarLeap = false
        var lunarMonth = diff + 11
        if (b11 - a11 > 365) {
            val leapMonthDiff = getLeapMonthOffset(a11, TIMEZONE_OFFSET)
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10
                if (diff == leapMonthDiff) {
                    lunarLeap = true
                }
            }
        }
        if (lunarMonth > 12) {
            lunarMonth -= 12
        }
        if (lunarMonth >= 11 && diff < 4) {
            lunarYear -= 1
        }
        return LunarDate(lunarDay, lunarMonth, lunarYear, lunarLeap)
    }

    fun lunarToSolar(lunarDay: Int, lunarMonth: Int, lunarYear: Int, lunarLeap: Boolean = false): LocalDate {
        val a11: Int
        val b11: Int
        if (lunarMonth < 11) {
            a11 = getLunarMonth11(lunarYear - 1, TIMEZONE_OFFSET)
            b11 = getLunarMonth11(lunarYear, TIMEZONE_OFFSET)
        } else {
            a11 = getLunarMonth11(lunarYear, TIMEZONE_OFFSET)
            b11 = getLunarMonth11(lunarYear + 1, TIMEZONE_OFFSET)
        }
        val k = floor(0.5 + (a11 - 2415021.076998695) / 29.530588853).toInt()
        var off = lunarMonth - 11
        if (off < 0) {
            off += 12
        }
        if (b11 - a11 > 365) {
            val leapOff = getLeapMonthOffset(a11, TIMEZONE_OFFSET)
            var leapMonth = leapOff - 2
            if (leapMonth < 0) {
                leapMonth += 12
            }
            if (lunarLeap && lunarMonth != leapMonth) {
                return LocalDate.now()
            } else if (lunarLeap || off >= leapOff) {
                off += 1
            }
        }
        val monthStart = getNewMoonDay(k + off, TIMEZONE_OFFSET)
        val (d, m, y) = jdToDate(monthStart + lunarDay - 1)
        return LocalDate.of(y, m, d)
    }

    fun findUpcomingLunarDay(targetLunarDay: Int, referenceSolar: LocalDate = LocalDate.now(), forceNextMonth: Boolean = false): Pair<LocalDate, LunarDate> {
        val currentLunar = solarToLunar(referenceSolar)
        return if (!forceNextMonth && currentLunar.day <= targetLunarDay) {
            val solar = lunarToSolar(targetLunarDay, currentLunar.month, currentLunar.year, currentLunar.isLeapMonth)
            Pair(solar, LunarDate(targetLunarDay, currentLunar.month, currentLunar.year, currentLunar.isLeapMonth))
        } else {
            val nextMonth = if (currentLunar.month == 12) 1 else currentLunar.month + 1
            val nextYear = if (currentLunar.month == 12) currentLunar.year + 1 else currentLunar.year
            val solar = lunarToSolar(targetLunarDay, nextMonth, nextYear, false)
            Pair(solar, LunarDate(targetLunarDay, nextMonth, nextYear, false))
        }
    }

    private fun parseVietnameseLunarMonthName(text: String): Int? {
        val monthMap = listOf(
            "tháng giêng" to 1, "tháng một" to 1, "tháng 1" to 1,
            "tháng hai" to 2, "tháng 2" to 2,
            "tháng ba" to 3, "tháng 3" to 3,
            "tháng tư" to 4, "tháng bốn" to 4, "tháng 4" to 4,
            "tháng năm" to 5, "tháng 5" to 5,
            "tháng sáu" to 6, "tháng 6" to 6,
            "tháng bảy" to 7, "tháng 7" to 7,
            "tháng tám" to 8, "tháng 8" to 8,
            "tháng chín" to 9, "tháng 9" to 9,
            "tháng mười một" to 11, "tháng 11" to 11,
            "tháng chạp" to 12, "tháng mười hai" to 12, "tháng 12" to 12,
            "tháng mười" to 10, "tháng 10" to 10
        )
        return monthMap.firstOrNull { text.contains(it.first) }?.second
    }

    fun resolveVietnameseDateTime(
        utterance: String,
        referenceDate: LocalDate = LocalDate.now()
    ): ResolvedDateTime {
        val cleanedUtterance = normalizeSpokenVietnameseNumbers(extractEffectiveClauseAfterSelfCorrection(utterance))
        val lower = cleanedUtterance.lowercase()
        var resolvedSolar = referenceDate
        var matchedExpr = "hôm nay"

        val explicitSlashRegex = Regex("(?:ngày\\s*)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?")
        val slashMatch = explicitSlashRegex.find(lower)

        val hasRam = lower.contains("rằm") || lower.contains("15 âm")
        val mungRegex = Regex("mùng\\s*(\\d{1,2})")
        val mungMatch = mungRegex.find(lower)
        val explicitLunarMonth = parseVietnameseLunarMonthName(lower)

        if (slashMatch != null && !lower.contains("âm")) {
            val d = slashMatch.groupValues[1].toIntOrNull() ?: referenceDate.dayOfMonth
            val m = slashMatch.groupValues[2].toIntOrNull() ?: referenceDate.monthValue
            val y = slashMatch.groupValues[3].toIntOrNull() ?: referenceDate.year
            runCatching {
                resolvedSolar = LocalDate.of(y, m.coerceIn(1, 12), d.coerceIn(1, 31))
                matchedExpr = slashMatch.value.trim()
            }
        } else if (hasRam && explicitLunarMonth != null) {
            val currentLunar = solarToLunar(referenceDate)
            val targetYear = if (explicitLunarMonth < currentLunar.month) currentLunar.year + 1 else currentLunar.year
            resolvedSolar = lunarToSolar(15, explicitLunarMonth, targetYear, false)
            matchedExpr = "Rằm tháng $explicitLunarMonth Âm lịch"
        } else if (mungMatch != null && explicitLunarMonth != null) {
            val dayNum = mungMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 30) ?: 1
            val currentLunar = solarToLunar(referenceDate)
            val targetYear = if (explicitLunarMonth < currentLunar.month) currentLunar.year + 1 else currentLunar.year
            resolvedSolar = lunarToSolar(dayNum, explicitLunarMonth, targetYear, false)
            matchedExpr = "Mùng $dayNum tháng $explicitLunarMonth Âm lịch"
        } else if (hasRam && (lower.contains("sáng mai rằm") || lower.contains("bữa mơi rằm") || lower.contains("sáng mơi rằm"))) {
            val (solar, lunar) = findUpcomingLunarDay(15, referenceDate)
            resolvedSolar = solar
            matchedExpr = "sáng mai rằm (15/${lunar.month} ÂL)"
        } else if (hasRam) {
            val nextMonth = lower.contains("tháng tới") || lower.contains("tháng sau")
            val (solar, lunar) = findUpcomingLunarDay(15, referenceDate, nextMonth)
            resolvedSolar = solar
            matchedExpr = "ngày Rằm (15/${lunar.month} ÂL)"
        } else if (mungMatch != null) {
            val dayNum = mungMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 30) ?: 1
            val nextMonth = lower.contains("tháng tới") || lower.contains("tháng sau") || lower.contains("sáng mai mùng 1") || lower.contains("sáng mơi mùng 1") || lower.contains("bữa mơi mùng 1")
            val (solar, lunar) = findUpcomingLunarDay(dayNum, referenceDate, nextMonth && currentLunarDayGreaterThan(referenceDate, dayNum))
            resolvedSolar = solar
            matchedExpr = "mùng $dayNum (${lunar.day}/${lunar.month} ÂL)"
        } else if (lower.contains("ngày kia") || lower.contains("ngày mốt") || lower.contains("bữa mốt")) {
            resolvedSolar = referenceDate.plusDays(2)
            matchedExpr = "ngày mốt"
        } else if (lower.contains("ngày mai") || lower.contains("sáng mai") || lower.contains("trưa mai") || lower.contains("chiều mai") || lower.contains("tối mai") ||
            lower.contains("bữa mơi") || lower.contains("sáng mơi") || lower.contains("chiều mơi") || lower.contains(" mơi ")
        ) {
            resolvedSolar = referenceDate.plusDays(1)
            matchedExpr = "ngày mai"
        } else if (lower.contains("cuối tuần")) {
            val daysUntilSaturday = (6 - referenceDate.dayOfWeek.value + 7) % 7
            resolvedSolar = referenceDate.plusDays(if (daysUntilSaturday == 0) 7L else daysUntilSaturday.toLong())
            matchedExpr = "cuối tuần"
        }

        val resolvedLunar = solarToLunar(resolvedSolar)
        val resolvedTime = extractTimeFromUtterance(utterance, preferAlarmTime = false)

        val explanation = "$matchedExpr → Dương lịch: ${resolvedSolar.format(DATE_FMT)} (${resolvedLunar.formatVi()}), Giờ: $resolvedTime"
        return ResolvedDateTime(
            solarDate = resolvedSolar,
            lunarDate = resolvedLunar,
            time = resolvedTime,
            matchedExpression = matchedExpr,
            explanationVi = explanation
        )
    }

    private fun currentLunarDayGreaterThan(referenceDate: LocalDate, targetDay: Int): Boolean {
        return solarToLunar(referenceDate).day > targetDay
    }

    /**
     * Trích xuất giờ chính xác từ câu nói (Hỗ trợ Tự sửa lời nói & Toán thời gian tương đối "sớm trước X tiếng/phút")
     */
    fun extractTimeFromUtterance(rawUtterance: String, preferAlarmTime: Boolean = false): LocalTime {
        // BƯỚC 1: Áp dụng Tự sửa lời nói (Self-correction) và chuẩn hóa số đếm
        val effectiveText = normalizeSpokenVietnameseNumbers(extractEffectiveClauseAfterSelfCorrection(rawUtterance))
        val lowerUtterance = effectiveText.lowercase()

        // BƯỚC 2: Xử lý Toán thời gian tương đối cho Báo thức ("sớm trước 1 tiếng", "trước 30 phút", "sớm hơn nửa tiếng")
        if (preferAlarmTime) {
            val relativeBeforeRegex = Regex("(?:sớm trước|sớm hơn|trước)\\s*(\\d+|nửa|một|hai)\\s*(tiếng|giờ|phút)")
            val relativeMatch = relativeBeforeRegex.find(lowerUtterance)
            if (relativeMatch != null) {
                val baseEventTime = extractTimeFromUtterance(rawUtterance, preferAlarmTime = false)
                val amountStr = relativeMatch.groupValues[1]
                val unitStr = relativeMatch.groupValues[2]
                val amount = when (amountStr) {
                    "nửa" -> 30L
                    "một" -> 1L
                    "hai" -> 2L
                    else -> amountStr.toLongOrNull() ?: 1L
                }
                return if (unitStr == "phút" || amountStr == "nửa") {
                    baseEventTime.minusMinutes(if (amountStr == "nửa") 30L else amount)
                } else {
                    baseEventTime.minusHours(amount)
                }
            }
        }

        // BƯỚC 3: Kiểm tra định dạng HH:mm
        val colonTimeRegex = Regex("(\\d{1,2}):(\\d{2})")
        val allColonMatches = colonTimeRegex.findAll(lowerUtterance).toList()
        if (allColonMatches.isNotEmpty()) {
            val chosen = if (preferAlarmTime) allColonMatches.last() else allColonMatches.first()
            val h = chosen.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: 7
            val m = chosen.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
            return LocalTime.of(h, m)
        }

        // BƯỚC 4: Nếu tìm giờ báo thức cụ thể ("đặt báo thức...", "đặt chuông...", "gọi bác dậy...", "nhắc bác...")
        if (preferAlarmTime) {
            val alarmKemRegex = Regex("(?:báo thức|đặt chuông|gọi bác dậy|nhắc)[^,\\.]*?(\\d{1,2})\\s*giờ\\s*kém\\s*(\\d{1,2})")
            alarmKemRegex.findAll(lowerUtterance).lastOrNull()?.let { m ->
                val h = (m.groupValues[1].toIntOrNull() ?: 6) - 1
                val min = 60 - (m.groupValues[2].toIntOrNull() ?: 15)
                return LocalTime.of(h.coerceIn(0, 23), min.coerceIn(0, 59))
            }

            val alarmRuoiRegex = Regex("(?:báo thức|đặt chuông|gọi bác dậy|nhắc)[^,\\.]*?(\\d{1,2})\\s*(?:giờ\\s*|h\\s*)?rưỡi\\s*(sáng|trưa|chiều|tối)?")
            alarmRuoiRegex.findAll(lowerUtterance).lastOrNull()?.let { m ->
                val rawH = m.groupValues[1].toIntOrNull() ?: 6
                val period = m.groupValues[2]
                return LocalTime.of(adjustHourByPeriod(rawH, period, lowerUtterance), 30)
            }

            val alarmHourMinRegex = Regex("(?:báo thức|đặt chuông|gọi bác dậy|nhắc)[^,\\.]*?(\\d{1,2})\\s*(?:giờ|h)(?:\\s*(\\d{1,2}))?\\s*(sáng|trưa|chiều|tối)?")
            alarmHourMinRegex.findAll(lowerUtterance).firstOrNull()?.let { m ->
                val rawH = m.groupValues[1].toIntOrNull() ?: 7
                val min = m.groupValues[2].toIntOrNull() ?: 0
                val period = m.groupValues[3]
                return LocalTime.of(adjustHourByPeriod(rawH, period, lowerUtterance), min.coerceIn(0, 59))
            }
        }

        // BƯỚC 5: Kiểm tra giờ ước lệ dân gian
        if (lowerUtterance.contains("tờ mờ sáng")) return LocalTime.of(5, 30)
        if (lowerUtterance.contains("chập tối")) return LocalTime.of(18, 30)
        if (lowerUtterance.contains("xế chiều") && !lowerUtterance.contains("giờ")) return LocalTime.of(15, 0)

        val kemRegex = Regex("(\\d{1,2})\\s*giờ\\s*kém\\s*(\\d{1,2})")
        kemRegex.findAll(lowerUtterance).lastOrNull()?.let { m ->
            val h = (m.groupValues[1].toIntOrNull() ?: 6) - 1
            val min = 60 - (m.groupValues[2].toIntOrNull() ?: 15)
            return LocalTime.of(h.coerceIn(0, 23), min.coerceIn(0, 59))
        }

        val eventRuoiRegex = Regex("(?:lúc|tầm)?\\s*(\\d{1,2})\\s*(?:giờ\\s*|h\\s*)?rưỡi\\s*(sáng|trưa|chiều|tối)?")
        val allRuoi = eventRuoiRegex.findAll(lowerUtterance).toList()
        if (allRuoi.isNotEmpty()) {
            val chosen = if (!preferAlarmTime && allRuoi.size > 1) allRuoi.first() else allRuoi.last()
            val rawH = chosen.groupValues[1].toIntOrNull() ?: 7
            val period = chosen.groupValues[2]
            return LocalTime.of(adjustHourByPeriod(rawH, period, lowerUtterance), 30)
        }

        val hourMinRegex = Regex("(?:lúc|tầm)?\\s*(\\d{1,2})\\s*(?:giờ|h)(?:\\s*(\\d{1,2}))?\\s*(sáng|trưa|chiều|tối)?")
        val allHours = hourMinRegex.findAll(lowerUtterance).toList()
        if (allHours.isNotEmpty()) {
            val chosen = if (preferAlarmTime) allHours.last() else allHours.first()
            val rawH = chosen.groupValues[1].toIntOrNull() ?: 7
            val min = chosen.groupValues[2].toIntOrNull() ?: 0
            val period = chosen.groupValues[3]
            return LocalTime.of(adjustHourByPeriod(rawH, period, lowerUtterance), min.coerceIn(0, 59))
        }

        return LocalTime.of(7, 30)
    }

    private fun adjustHourByPeriod(rawHour: Int, period: String, fullText: String): Int {
        val h = rawHour.coerceIn(0, 23)
        val p = period.ifBlank {
            when {
                fullText.contains("tối") || fullText.contains("đêm") -> "tối"
                fullText.contains("chiều") -> "chiều"
                fullText.contains("trưa") -> "trưa"
                else -> "sáng"
            }
        }
        return when (p) {
            "chiều", "tối" -> if (h in 1..11) h + 12 else h
            "trưa" -> if (h in 1..4) h + 12 else h
            else -> h
        }
    }

    fun formatIsoDate(date: LocalDate): String = date.format(ISO_DATE_FMT)
    fun formatDisplayDate(date: LocalDate): String = date.format(DATE_FMT)
}
