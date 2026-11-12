package com.khkt.seniorvoice.executor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WeatherReport(
    val date: String,
    val targetHour: String = "07:00",
    val locationName: String = "TP. Hồ Chí Minh",
    val hourlyTemperature: Double = 27.5,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val rainProbability: Int,
    val conditionVi: String,
    val seniorAdviceVi: String,
    val isLiveFromOpenMeteo: Boolean
) {
    fun toSummaryVi(): String {
        return "Tại $locationName ngày $date (khung giờ $targetHour): $conditionVi, nhiệt độ khoảng ${hourlyTemperature.toInt()}°C (trong ngày ${temperatureMin.toInt()}°C–${temperatureMax.toInt()}°C), khả năng mưa $rainProbability%. $seniorAdviceVi"
    }
}

/**
 * Tầng 4: Gọi trực tiếp Open-Meteo API qua HTTPS (Miễn phí 100%, Không cần API Key)
 * Hỗ trợ dự báo theo khung giờ cụ thể (Hourly + Daily) tại đúng tọa độ địa điểm của ông bà,
 * kèm cơ chế dự phòng Offline khi phòng thi không có Internet quốc tế.
 */
class WeatherApiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetchWeather(
        latitude: Double = 10.7578,
        longitude: Double = 106.6597,
        targetDate: String,
        targetHour: String = "07:00",
        locationName: String = "TP. Hồ Chí Minh"
    ): WeatherReport = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&hourly=temperature_2m,precipitation_probability,weathercode" +
                "&daily=weathercode,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=Asia%2FHo_Chi_Minh"

        runCatching {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val bodyStr = response.body?.string().orEmpty()
                val root = JSONObject(bodyStr)
                val daily = root.getJSONObject("daily")
                val times = daily.getJSONArray("time")
                val maxTemps = daily.getJSONArray("temperature_2m_max")
                val minTemps = daily.getJSONArray("temperature_2m_min")
                val rainProbs = daily.optJSONArray("precipitation_probability_max")
                val codes = daily.optJSONArray("weathercode")

                var idx = 0
                for (i in 0 until times.length()) {
                    if (times.getString(i) == targetDate) {
                        idx = i
                        break
                    }
                }

                val tMax = maxTemps.optDouble(idx, 32.0)
                val tMin = minTemps.optDouble(idx, 25.0)
                val dailyRainProb = rainProbs?.optInt(idx, 20) ?: 20
                val dailyCode = codes?.optInt(idx, 1) ?: 1

                // Phân tích chính xác khung giờ mục tiêu (ví dụ 06:00 sáng tập dưỡng sinh)
                val hourPrefix = targetHour.split(":").firstOrNull()?.padStart(2, '0') ?: "07"
                val targetIsoHour = "${targetDate}T$hourPrefix:00"
                var hourlyTemp = (tMax + tMin) / 2.0
                var hourlyRain = dailyRainProb
                var hourlyCode = dailyCode

                val hourlyObj = root.optJSONObject("hourly")
                if (hourlyObj != null) {
                    val hTimes = hourlyObj.optJSONArray("time")
                    val hTemps = hourlyObj.optJSONArray("temperature_2m")
                    val hRains = hourlyObj.optJSONArray("precipitation_probability")
                    val hCodes = hourlyObj.optJSONArray("weathercode")
                    if (hTimes != null) {
                        for (j in 0 until hTimes.length()) {
                            if (hTimes.optString(j) == targetIsoHour) {
                                hourlyTemp = hTemps?.optDouble(j, hourlyTemp) ?: hourlyTemp
                                hourlyRain = hRains?.optInt(j, dailyRainProb) ?: dailyRainProb
                                hourlyCode = hCodes?.optInt(j, dailyCode) ?: dailyCode
                                break
                            }
                        }
                    }
                }

                val effectiveRainProb = maxOf(hourlyRain, dailyRainProb)
                val condition = mapWmoCodeToVietnamese(hourlyCode, effectiveRainProb)
                val advice = buildSeniorHealthAdvice(tMax, tMin, hourlyTemp, effectiveRainProb)

                WeatherReport(
                    date = targetDate,
                    targetHour = "$hourPrefix:00",
                    locationName = locationName,
                    hourlyTemperature = hourlyTemp,
                    temperatureMax = tMax,
                    temperatureMin = tMin,
                    rainProbability = effectiveRainProb,
                    conditionVi = condition,
                    seniorAdviceVi = advice,
                    isLiveFromOpenMeteo = true
                )
            }
        }.getOrElse {
            // Dự phòng Offline khi bật Hotspot nội bộ không nối Internet
            WeatherReport(
                date = targetDate,
                targetHour = targetHour,
                locationName = locationName,
                hourlyTemperature = 27.5,
                temperatureMax = 31.5,
                temperatureMin = 25.0,
                rainProbability = 15,
                conditionVi = "Trời nắng ráo, gió nhẹ",
                seniorAdviceVi = "Thời tiết thuận lợi để bác đi khám bệnh/tập dưỡng sinh, nhớ đội nón và mang theo bình nước ấm.",
                isLiveFromOpenMeteo = false
            )
        }
    }

    private fun mapWmoCodeToVietnamese(wmoCode: Int, rainProb: Int): String {
        return when {
            wmoCode == 0 -> "Trời quang mây, nắng đẹp"
            wmoCode in 1..3 -> "Trời nắng dịu, có mây rải rác"
            wmoCode in 45..48 -> "Sáng sớm có sương mù nhẹ"
            wmoCode in 51..67 || rainProb >= 60 -> "Có khả năng mưa rào rải rác"
            wmoCode in 80..99 -> "Có mưa dông"
            else -> "Trời nắng ráo, mát mẻ"
        }
    }

    private fun buildSeniorHealthAdvice(
        tMax: Double,
        tMin: Double,
        hourlyTemp: Double,
        rainProb: Int
    ): String {
        return when {
            rainProb >= 60 -> "Khả năng mưa cao ($rainProb%), bác nên đi xe ô tô kín gió hoặc mang dù/áo mưa, đi giày chống trượt cẩn thận."
            hourlyTemp <= 21.0 || tMin <= 20.0 -> "Trời se lạnh (${hourlyTemp.toInt()}°C), bác nhớ mặc thêm áo khoác ấm để giữ ổn định huyết áp."
            tMax >= 34.0 -> "Buổi trưa nắng gắt, bác nhớ đội nón rộng vành và uống đủ nước ấm."
            else -> "Thời tiết rất tốt, bác nhớ mang theo sổ khám bệnh và thuốc định kỳ."
        }
    }
}
