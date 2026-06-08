package com.speaker

import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import android.provider.AlarmClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 工具执行器 — 执行 DeepSeek 请求的各类工具
 *
 * 支持：
 *   - get_weather(city)    查天气（Open-Meteo 免费 API，无 key）
 *   - get_current_time()   当前时间
 *   - set_alarm(h,m,label) 设闹钟（Android 系统闹钟）
 *   - set_timer(min,label) 设倒计时
 *   - calculate(expr)      数学计算
 *   - get_news(cat,keyword)查新闻（NewsAPI，需要用户在设置中配置 key）
 */
class ToolExecutor {

    companion object {
        private const val WEATHER_GEO_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val WEATHER_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val NEWS_API_URL = "https://newsapi.org/v2/top-headlines"
    }

    private var context: Context? = null
    private var newsApiKey: String = ""
    private val executor = Executors.newSingleThreadExecutor()

    fun setContext(ctx: Context) {
        context = ctx
    }

    fun setNewsApiKey(key: String) {
        newsApiKey = key
    }

    fun setApiKey(key: String) {
        // 复用 DeepSeek key 作为新闻 API key（如果用户没单独配）
        if (newsApiKey.isEmpty()) {
            newsApiKey = key
        }
    }

    /**
     * 执行工具调用
     * @param funcName 函数名
     * @param argsJson 参数 JSON 字符串
     * @return 工具执行结果（文字描述）
     */
    fun execute(funcName: String, argsJson: String): String {
        return try {
            val args = JSONObject(argsJson)
            when (funcName) {
                "get_weather" -> getWeather(args.optString("city", ""))
                "get_current_time" -> getCurrentTime()
                "set_alarm" -> setAlarm(
                    args.optInt("hour", 8),
                    args.optInt("minute", 0),
                    args.optString("label", "闹钟")
                )
                "set_timer" -> setTimer(
                    args.optInt("minutes", 5),
                    args.optString("label", "倒计时")
                )
                "calculate" -> calculate(args.optString("expression", ""))
                "get_news" -> getNews(
                    args.optString("category", ""),
                    args.optString("keyword", "")
                )
                else -> "未知工具: $funcName"
            }
        } catch (e: Exception) {
            "工具执行出错: ${e.message}"
        }
    }

    // ==================== 工具实现 ====================

    /**
     * 查天气（免费 Open-Meteo API）
     */
    private fun getWeather(city: String): String {
        if (city.isBlank()) return "请告诉我城市名"

        return try {
            // 1. 城市 → 经纬度
            val geoUrl = "$WEATHER_GEO_URL?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=zh&format=json"
            val geoJson = httpGet(geoUrl) ?: return "查不到城市 [$city] 的信息"
            val geo = JSONObject(geoJson)
            val results = geo.optJSONArray("results")
            if (results == null || results.length() == 0) {
                return "没找到城市 [$city]"
            }

            val loc = results.getJSONObject(0)
            val lat = loc.getDouble("latitude")
            val lon = loc.getDouble("longitude")
            val cityName = loc.optString("name", city)
            val country = loc.optString("country", "")
            val admin = loc.optString("admin1", "")

            // 2. 查天气
            val weatherUrl = "$WEATHER_FORECAST_URL?latitude=$lat&longitude=$lon" +
                    "&current_weather=true" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability" +
                    "&timezone=auto" +
                    "&forecast_days=1"
            val weatherJson = httpGet(weatherUrl) ?: return "获取天气数据失败"

            val weather = JSONObject(weatherJson)
            val current = weather.getJSONObject("current_weather")
            val temp = current.getDouble("temperature")
            val windSpeed = current.getDouble("windspeed")
            val weatherCode = current.optInt("weathercode", 0)
            val weatherDesc = weatherCodeToDesc(weatherCode)

            var result = "$cityName"
            if (admin.isNotEmpty()) result += "·$admin"
            result += " 当前天气：$weatherDesc，${temp.toInt()}°C"
            result += "，风速 ${windSpeed}km/h"

            // 当日预报
            val daily = weather.optJSONObject("daily")
            if (daily != null) {
                val maxT = daily.optJSONArray("temperature_2m_max")?.optDouble(0)
                val minT = daily.optJSONArray("temperature_2m_min")?.optDouble(0)
                val rainProb = daily.optJSONArray("precipitation_probability")?.optInt(0, -1)

                if (maxT != null && minT != null) {
                    result += "。今天 ${minT.toInt()}~${maxT.toInt()}°C"
                }
                if (rainProb >= 0) {
                    result += "，降雨概率 $rainProb%"
                }
            }

            result
        } catch (e: Exception) {
            "查天气时出错: ${e.message}"
        }
    }

    /**
     * 获取当前时间
     */
    private fun getCurrentTime(): String {
        val now = Date()
        val dateFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINESE)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINESE)
        val weekFmt = SimpleDateFormat("EEEE", Locale.CHINESE)
        return "现在是 ${dateFmt.format(now)} 星期${weekdayToChinese(now)} ${timeFmt.format(now)}"
    }

    /**
     * 设置闹钟（调用 Android 系统闹钟）
     */
    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        val ctx = context ?: return "无法设置闹钟"
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            return "已为您打开闹钟设置，${hour}点${minute}分的闹钟「${label}」"
        } catch (e: Exception) {
            return "打开闹钟失败: ${e.message}"
        }
    }

    /**
     * 设置倒计时
     */
    private fun setTimer(minutes: Int, label: String): String {
        val ctx = context ?: return "无法设置倒计时"
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            return "已为您打开倒计时设置，${minutes}分钟"
        } catch (e: Exception) {
            return "打开倒计时失败: ${e.message}"
        }
    }

    /**
     * 数学计算（纯 Kotlin 实现，不需要 ScriptEngine）
     */
    private fun calculate(expression: String): String {
        if (expression.isBlank()) return "请输入表达式"

        return try {
            // 替换中文符号
            val sanitized = expression.replace("×", "*")
                .replace("÷", "/")
                .replace("x", "*")
                .replace("X", "*")
                .replace("（", "(")
                .replace("）", ")")
                .trim()

            if (!sanitized.matches(Regex("^[\\d+\\-*/().%\\s]+$"))) {
                return "表达式包含不支持的字符"
            }

            val result = evalMath(sanitized)

            if (result == null) {
                "计算失败"
            } else {
                if (result == result.toLong().toDouble()) {
                    "${sanitized} = ${result.toLong()}"
                } else {
                    "${sanitized} = ${"%.2f".format(result)}"
                }
            }
        } catch (e: Exception) {
            "计算错误: ${e.message}"
        }
    }

    /**
     * 简易数学表达式求值器（递归下降）
     */
    private fun evalMath(expr: String): Double? {
        return try {
            val tokens = expr.replace(" ", "").toCharArray()
            val parser = MathParser(tokens)
            parser.parseExpression()
        } catch (e: Exception) {
            null
        }
    }

    private class MathParser(private val tokens: CharArray) {
        private var pos = 0

        fun parseExpression(): Double {
            var result = parseTerm()
            while (pos < tokens.size) {
                when (tokens[pos]) {
                    '+' -> { pos++; result += parseTerm() }
                    '-' -> { pos++; result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parseFactor()
            while (pos < tokens.size) {
                when (tokens[pos]) {
                    '*' -> { pos++; result *= parseFactor() }
                    '/' -> { pos++; result /= parseFactor() }
                    '%' -> { pos++; result %= parseFactor() }
                    else -> break
                }
            }
            return result
        }

        private fun parseFactor(): Double {
            if (pos >= tokens.size) return 0.0

            if (tokens[pos] == '(') {
                pos++ // 跳过 '('
                val result = parseExpression()
                if (pos < tokens.size && tokens[pos] == ')') pos++ // 跳过 ')'
                return result
            }

            if (tokens[pos] == '-') {
                pos++
                return -parseFactor()
            }

            // 解析数字
            val start = pos
            while (pos < tokens.size && (tokens[pos].isDigit() || tokens[pos] == '.')) {
                pos++
            }
            return tokens.concatToString(start, pos).toDoubleOrNull() ?: 0.0
        }
    }

    /**
     * 查新闻（需要 NewsAPI key）
     */
    private fun getNews(category: String, keyword: String): String {
        if (newsApiKey.isEmpty()) {
            return "新闻功能需要配置 API Key"
        }

        return try {
            val sb = StringBuilder()
            sb.append("$NEWS_API_URL?country=cn&pageSize=5")
            if (category.isNotEmpty()) {
                val catMap = mapOf(
                    "科技" to "technology", "财经" to "business",
                    "体育" to "sports", "娱乐" to "entertainment",
                    "健康" to "health", "国内" to "general",
                    "国际" to "general"
                )
                val enCat = catMap[category] ?: category
                sb.append("&category=$enCat")
            }
            if (keyword.isNotEmpty()) {
                sb.append("&q=${URLEncoder.encode(keyword, "UTF-8")}")
            }
            sb.append("&apiKey=$newsApiKey")

            val json = httpGet(sb.toString()) ?: return "获取新闻失败"
            val data = JSONObject(json)
            val articles = data.optJSONArray("articles")
            if (articles == null || articles.length() == 0) {
                return "没有找到相关新闻"
            }

            val result = StringBuilder("最新新闻：")
            for (i in 0 until minOf(articles.length(), 5)) {
                val art = articles.getJSONObject(i)
                val title = art.optString("title", "")
                if (title.isNotEmpty()) {
                    if (i > 0) result.append("；")
                    result.append(title)
                }
            }
            result.toString()
        } catch (e: Exception) {
            "获取新闻出错: ${e.message}"
        }
    }

    // ==================== 工具函数 ====================

    /**
     * HTTP GET 请求
     */
    private fun httpGet(urlStr: String): String? {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"

            val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val response = reader.readText()
            reader.close()
            conn.disconnect()
            response
        } catch (e: Exception) {
            null
        }
    }

    /**
     * WMO 天气代码转中文描述
     */
    private fun weatherCodeToDesc(code: Int): String {
        return when (code) {
            0 -> "晴天"
            1, 2, 3 -> "多云"
            45, 48 -> "有雾"
            51, 53, 55 -> "毛毛雨"
            56, 57 -> "冻雨"
            61, 63, 65 -> "下雨"
            66, 67 -> "冻雨"
            71, 73, 75 -> "下雪"
            77 -> "冰粒"
            80, 81, 82 -> "阵雨"
            85, 86 -> "阵雪"
            95 -> "雷暴"
            96, 99 -> "雷暴伴有冰雹"
            else -> "未知"
        }
    }

    private fun weekdayToChinese(date: Date): String {
        val cal = java.util.Calendar.getInstance().apply { time = date }
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            1 -> "日"
            2 -> "一"
            3 -> "二"
            4 -> "三"
            5 -> "四"
            6 -> "五"
            7 -> "六"
            else -> ""
        }
    }
}
