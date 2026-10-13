package com.spake.brony.doubaoassistant.information

import android.content.Context
import android.icu.util.TimeZone
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.conversation.AiOperationFailure
import com.spake.brony.doubaoassistant.conversation.ApiOperations
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** 天气取真实 API 数据；时间读取设备时钟，国外城市仅联网解析地点与时区。 */
internal class InformationController(context: Context) : ApiOperations, AutoCloseable {
    private val app = context.applicationContext
    private val location = WeatherLocation(app)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    // 与权限弹窗一样，查询期间切换前后台不能丢掉本轮结果。
    var hasActiveQuery = false
        private set

    override suspend fun getWeather(slots: JSONObject): String = query { weather(slots) }
    override suspend fun getTime(slots: JSONObject): String = query { time(slots) }

    private suspend fun query(request: suspend () -> String): String {
        hasActiveQuery = true
        try {
            return request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiOperationFailure) {
            throw e
        } catch (_: IOException) {
            throw AiOperationFailure(R.string.info_network_failed)
        } catch (_: JSONException) {
            throw AiOperationFailure(R.string.info_invalid_response)
        } finally {
            hasActiveQuery = false
        }
    }

    private data class Place(val label: String, val latitude: Double, val longitude: Double, val zone: ZoneId)
    private val chinaCodes = setOf("CN", "HK", "MO", "TW")

    private suspend fun weather(slots: JSONObject): String {
        validate(slots, setOf("location", "region", "country", "day_offset"))
        // current 是本机定位标记，不作为城市名称发送给地点检索接口。
        val name = text(slots, "location")?.takeUnless { it == "current" }
        val region = text(slots, "region")
        val country = text(slots, "country")
        if (country != null && countryCode(country) !in chinaCodes) throw AiOperationFailure(R.string.weather_only_china)
        val day = if (slots.has("day_offset")) {
            val value = (slots.opt("day_offset") as? Number)?.toDouble() ?: invalid()
            if (value !in 0.0..6.0 || value % 1.0 != 0.0) invalid()
            value.toInt()
        } else null
        val place = if (name != null) resolve(name, region, country, chinaOnly = true) else {
            if (country != null || region != null) throw AiOperationFailure(R.string.weather_city_required)
            val fix = location.current()
            val address = location.address(fix) ?: throw AiOperationFailure(R.string.weather_country_unconfirmed)
            if (address.countryCode?.uppercase(Locale.ROOT) !in chinaCodes) throw AiOperationFailure(R.string.weather_only_china)
            val label = listOfNotNull(address.adminArea, address.locality, address.subLocality).distinct().joinToString("")
                .ifBlank { app.getString(R.string.weather_here) }
            Place(label, fix.latitude, fix.longitude, ZoneId.of("Asia/Shanghai"))
        }
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.latitude.toString()).addQueryParameter("longitude", place.longitude.toString())
            .addQueryParameter("timezone", place.zone.id).addQueryParameter("temperature_unit", "celsius")
        if (day == null) url.addQueryParameter("current", "temperature_2m,weather_code").addQueryParameter("forecast_days", "1")
        else url.addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("forecast_days", (day + 1).toString())
        val data = json(url.build())
        val answer = if (day == null) {
            val current = data.optJSONObject("current") ?: invalidResponse()
            try { LocalDateTime.parse(current.getString("time")) } catch (_: Exception) { invalidResponse() }
            val code = integer(current, "weather_code")
            app.getString(R.string.weather_current_result, description(code), number(current, "temperature_2m"))
        } else {
            val daily = data.optJSONObject("daily") ?: invalidResponse()
            val dates = daily.optJSONArray("time") ?: invalidResponse()
            val date = try { LocalDate.parse(dates.getString(day)) } catch (_: Exception) { invalidResponse() }
            // 不把过期缓存的“明天”当成现在的明天。
            if (date != LocalDate.now(place.zone).plusDays(day.toLong())) invalidResponse()
            fun at(key: String): Double {
                val value = (daily.optJSONArray(key)?.opt(day) as? Number)?.toDouble() ?: invalidResponse()
                if (!value.isFinite()) invalidResponse()
                return value
            }
            val code = at("weather_code")
            if (code % 1.0 != 0.0 || code !in 0.0..99.0) invalidResponse()
            val probability = at("precipitation_probability_max")
            if (probability !in 0.0..100.0) invalidResponse()
            val dayLabel = when (day) {
                0 -> app.getString(R.string.weather_today)
                1 -> app.getString(R.string.weather_tomorrow)
                2 -> app.getString(R.string.weather_day_after_tomorrow)
                else -> app.getString(R.string.weather_after_days, day)
            }
            app.getString(R.string.weather_daily_result, dayLabel,
                description(code.toInt()), decimal(at("temperature_2m_min")), decimal(at("temperature_2m_max")), probability.toInt())
        }
        // 默认查询仍按定位取数据，但不在回复中暴露完整地址。
        return if (name == null) answer else app.getString(R.string.info_location_prefix, place.label, answer)
    }

    private suspend fun time(slots: JSONObject): String {
        validate(slots, setOf("location", "region", "country", "format"))
        val name = text(slots, "location")
        val region = text(slots, "region")
        val country = text(slots, "country")
        val format = text(slots, "format") ?: "time"
        if (format !in listOf("time", "date", "weekday", "datetime")) invalid()
        val place = when {
            name != null -> resolve(name, region, country, chinaOnly = false).let { it.label to it.zone }
            country != null -> {
                if (region != null) return resolve(region, null, country, chinaOnly = false).let { formatTime(it.label, it.zone, format) }
                val code = countryCode(country) ?: throw AiOperationFailure(R.string.info_location_not_found)
                country to countryZone(code)
            }
            region != null -> resolve(region, null, null, chinaOnly = false).let { it.label to it.zone }
            else -> "" to ZoneId.systemDefault()
        }
        return formatTime(place.first, place.second, format)
    }

    private fun formatTime(label: String, zone: ZoneId, format: String): String {
        val pattern = when (format) {
            "time" -> "H时m分"
            "date" -> "yyyy年M月d日"
            "weekday" -> "EEEE"
            else -> "yyyy年M月d日H时m分"
        }
        val value = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern(pattern, Locale.SIMPLIFIED_CHINESE))
        val reply = app.getString(if (format == "date" || format == "weekday") R.string.date_result else R.string.time_result, value)
        return if (label.isBlank()) reply else app.getString(R.string.info_location_prefix, label, reply)
    }

    private fun countryZone(code: String): ZoneId {
        if (code == "CN") return ZoneId.of("Asia/Shanghai")
        val zones = TimeZone.getAvailableIDs(code).mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull() }.distinct()
        if (zones.isEmpty()) throw AiOperationFailure(R.string.info_location_not_found)
        val now = Instant.now()
        if (zones.map { it.rules.getOffset(now) }.distinct().size > 1) throw AiOperationFailure(R.string.time_city_required)
        return zones.first()
    }

    // 国家、省份限定交给 API 筛选；不接受模型提供的经纬度、时区或 URL。
    private suspend fun resolve(name: String, region: String?, country: String?, chinaOnly: Boolean): Place {
        val code = country?.let { countryCode(it) ?: throw AiOperationFailure(R.string.info_location_not_found) }
        val search = if (region == null) name else "$name, $region"
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", search).addQueryParameter("language", "zh").addQueryParameter("count", "10")
        if (code != null && !(chinaOnly && code == "CN")) url.addQueryParameter("countryCode", code)
        val results = json(url.build()).optJSONArray("results") ?: throw AiOperationFailure(R.string.info_location_not_found)
        if (chinaOnly && code == null && results.length() > 0 &&
            results.optJSONObject(0)?.optString("country_code") !in chinaCodes) {
            throw AiOperationFailure(R.string.weather_only_china)
        }
        val places = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { !chinaOnly || it.optString("country_code") in chinaCodes }
        if (places.isEmpty()) throw AiOperationFailure(R.string.info_location_not_found)
        val first = places.first()
        val feature = first.optString("feature_code")
        val resolvedCode = first.optString("country_code")
        if (!chinaOnly && feature == "CONT") throw AiOperationFailure(R.string.time_city_required)
        val municipality = first.optString("name") in setOf("北京", "北京市", "上海", "上海市", "天津", "天津市", "重庆", "重庆市")
        if (chinaOnly && ((feature == "ADM1" && !municipality) ||
                (feature.startsWith("PCL") && resolvedCode !in setOf("HK", "MO")))) {
            throw AiOperationFailure(R.string.weather_city_required)
        }
        // 中国城市统一使用官方北京时间，不使用地名库中部分地区的民间时区。
        val zone = if (feature.startsWith("PCL")) countryZone(resolvedCode)
            else try { ZoneId.of(if (resolvedCode == "CN") "Asia/Shanghai" else first.getString("timezone")) }
            catch (_: Exception) { invalidResponse() }
        val lat = (first.opt("latitude") as? Number)?.toDouble() ?: invalidResponse()
        val lon = (first.opt("longitude") as? Number)?.toDouble() ?: invalidResponse()
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) invalidResponse()
        val label = listOf(first.optString("country"), first.optString("admin1"), first.optString("name"))
            .filter { it.isNotBlank() }.distinct().joinToString(" ")
        return Place(label, lat, lon, zone)
    }

    private fun countryCode(name: String): String? {
        val normalized = name.trim()
        val alias = mapOf("中国大陆" to "CN", "中华人民共和国" to "CN", "大陆" to "CN",
            "香港" to "HK", "澳门" to "MO", "台湾" to "TW", "USA" to "US", "UK" to "GB")
        alias[normalized.uppercase(Locale.ROOT)]?.let { return it }
        return Locale.getISOCountries().firstOrNull { code ->
            normalized.equals(code, true) || listOf(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH).any { locale ->
                normalized.equals(Locale("", code).getDisplayCountry(locale), true)
            }
        }
    }

    private fun validate(slots: JSONObject, allowed: Set<String>) {
        if (slots.keys().asSequence().any { it !in allowed }) invalid()
    }
    private fun text(slots: JSONObject, key: String): String? {
        if (!slots.has(key)) return null
        return (slots.opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() && it.length <= 128 } ?: invalid()
    }
    private fun number(data: JSONObject, key: String): String {
        val value = (data.opt(key) as? Number)?.toDouble() ?: invalidResponse()
        if (!value.isFinite()) invalidResponse()
        return decimal(value)
    }
    private fun integer(data: JSONObject, key: String): Int {
        val value = (data.opt(key) as? Number)?.toDouble() ?: invalidResponse()
        if (!value.isFinite() || value % 1.0 != 0.0 || value !in 0.0..99.0) invalidResponse()
        return value.toInt()
    }
    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    private fun invalid(): Nothing = throw AiOperationFailure(R.string.info_invalid_parameters)
    private fun invalidResponse(): Nothing = throw AiOperationFailure(R.string.info_invalid_response)

    // WMO 编码来自 Open-Meteo 文档，未知编码不能伪造为晴天。
    private fun description(code: Int): String = when (code) {
        0 -> "晴"; 1 -> "大部晴朗"; 2 -> "多云"; 3 -> "阴天"; 45, 48 -> "有雾"
        51, 53, 55 -> "毛毛雨"; 56, 57, 66, 67 -> "冻雨"
        61, 80 -> "小雨"; 63, 81 -> "中雨"; 65, 82 -> "大雨"
        71, 85 -> "小雪"; 73 -> "中雪"; 75, 86 -> "大雪"; 77 -> "米雪"
        95, 97 -> "雷雨"; 96, 99 -> "雷雨伴冰雹"; else -> invalidResponse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun json(url: HttpUrl): JSONObject {
        val call = client.newCall(Request.Builder().url(url).get().build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            })
        }
        try { return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw AiOperationFailure(if (it.code == 429) R.string.info_rate_limited else R.string.info_network_failed)
                val source = it.body?.source() ?: invalidResponse()
                source.request(131_073)
                if (source.buffer.size > 131_072) invalidResponse()
                JSONObject(source.readUtf8()).also { data -> if (data.optBoolean("error")) invalidResponse() }
            }
        } } finally { response.close() }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        val executor = client.dispatcher.executorService
        executor.execute { try { client.connectionPool.evictAll() } finally { executor.shutdown() } }
    }
}
