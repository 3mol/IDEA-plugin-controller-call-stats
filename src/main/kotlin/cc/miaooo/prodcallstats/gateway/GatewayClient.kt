package cc.miaooo.prodcallstats.gateway

import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.settings.StatsSettingsState
import cc.miaooo.prodcallstats.stats.CallStats
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Talks to the prod call stats gateway. All methods are blocking — callers
 * must dispatch them on a background thread.
 *
 * When [StatsSettingsState.gatewayUrl] is blank or [StatsSettingsState.useMock]
 * is true, the client returns random-but-stable stats so the user can see the
 * Code Vision badges without configuring a backend. The mock generator seeds
 * off the handler sign so the same method always shows the same numbers within
 * one minute window.
 */
object GatewayClient {

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 5_000
    private val gson = Gson()
    private val log = Logger.getInstance("ProdCallStats")

    fun fetch(handler: HandlerMethod): CallStats {
        val settings = StatsSettingsState.getInstance()
        if (settings.useMock || settings.gatewayUrl.isBlank()) {
            log.warn("[PCS] fetch MOCK sign=${handler.sign}")
            return mockFor(handler, System.currentTimeMillis())
        }
        log.warn("[PCS] fetch HTTP sign=${handler.sign} env=${settings.environment}")
        val sign = URLEncoder.encode(handler.sign, "UTF-8")
        val url = buildString {
            append(settings.gatewayUrl.trimEnd('/'))
            append("/api/v1/call-stats")
            append("?sign=").append(sign)
            append("&env=").append(settings.environment)
        }
        return requestSingle(url, settings.apiToken)
    }

    fun fetchBatch(handlers: List<HandlerMethod>): Map<String, CallStats> {
        if (handlers.isEmpty()) return emptyMap()
        val settings = StatsSettingsState.getInstance()
        if (settings.useMock || settings.gatewayUrl.isBlank()) {
            val now = System.currentTimeMillis()
            log.warn("[PCS] fetchBatch MOCK count=${handlers.size}")
            return handlers.associate { it.sign to mockFor(it, now) }
        }
        log.warn("[PCS] fetchBatch HTTP count=${handlers.size} env=${settings.environment} api=${settings.apiVersion}")
        return requestBatch(
            settings.gatewayUrl.trimEnd('/'),
            settings.apiToken,
            settings.environment,
            handlers,
            settings.apiVersion,
        )
    }

    fun ping(): Boolean {
        val settings = StatsSettingsState.getInstance()
        if (settings.useMock || settings.gatewayUrl.isBlank()) {
            log.warn("[PCS] ping -> mock mode, treating as ok")
            return true
        }
        val url = "${settings.gatewayUrl.trimEnd('/')}/api/v1/health"
        log.warn("[PCS] ping $url")
        return try {
            HttpRequests.request(url)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .tuner { connection ->
                    if (connection is HttpURLConnection) {
                        connection.requestMethod = "GET"
                    }
                    connection.setRequestProperty("X-Api-Token", settings.apiToken)
                }
                .throwStatusCodeException(true)
                .connect { true }
        } catch (e: HttpRequests.HttpStatusException) {
            throw GatewayException.HttpStatus(e.statusCode, e.message ?: "")
        } catch (e: Exception) {
            throw GatewayException.Unreachable(e)
        }
    }

    private fun requestSingle(url: String, token: String): CallStats {
        try {
            return HttpRequests.request(url)
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .tuner { connection ->
                    connection.setRequestProperty("X-Api-Token", token)
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", userAgent())
                }
                .throwStatusCodeException(true)
                .connect { request -> parseSingle(request.readString()) }
        } catch (e: HttpRequests.HttpStatusException) {
            mapHttpStatus(e)
        } catch (e: IOException) {
            throw GatewayException.Unreachable(e)
        }
    }

    private fun requestBatch(
        baseUrl: String,
        token: String,
        env: String,
        handlers: List<HandlerMethod>,
        apiVersion: String,
    ): Map<String, CallStats> {
        // v1: POST /api/v1/call-stats/batch，results 为 map
        // v2: POST /api/v2/call-stats，results 为 array，每项含 className/methodName/sign
        val url = if (apiVersion == "v2") "$baseUrl/api/v2/call-stats" else "$baseUrl/api/v1/call-stats/batch"
        val payload = JsonObject().apply {
            addProperty("env", env)
            add("signs", gson.toJsonTree(handlers.map { it.sign }))
        }.toString()
        try {
            return HttpRequests.post(url, "application/json; charset=utf-8")
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .readTimeout(READ_TIMEOUT_MS)
                .tuner { connection ->
                    connection.setRequestProperty("X-Api-Token", token)
                    connection.setRequestProperty("User-Agent", userAgent())
                }
                .throwStatusCodeException(true)
                .connect { request ->
                    request.write(payload)
                    parseBatch(request.readString())
                }
        } catch (e: HttpRequests.HttpStatusException) {
            mapHttpStatus(e)
        } catch (e: IOException) {
            throw GatewayException.Unreachable(e)
        }
    }

    private fun mapHttpStatus(e: HttpRequests.HttpStatusException): Nothing {
        when (e.statusCode) {
            401 -> throw GatewayException.TokenExpired
            404 -> throw GatewayException.NotFound
            else -> throw GatewayException.HttpStatus(e.statusCode, e.message ?: "")
        }
    }

    private fun parseSingle(json: String): CallStats {
        val obj = JsonParser.parseString(json).asJsonObject
        return parseStats(obj)
    }

    private fun parseBatch(json: String): Map<String, CallStats> {
        val obj = JsonParser.parseString(json).asJsonObject
        val results = obj.get("results") ?: return emptyMap()
        // v1: results 是对象 {sign: stats}
        // v2: results 是数组 [{className, methodName, sign, ...stats}]
        return when {
            results.isJsonObject -> {
                results.asJsonObject.entrySet().associate { (sign, value) ->
                    sign to parseStats(value.asJsonObject)
                }
            }
            results.isJsonArray -> {
                results.asJsonArray.mapNotNull { el ->
                    val item = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    // 优先用 sign 字段，缺失则用 className + "#" + methodName 组合
                    val sign = item.get("sign")?.takeIf { !it.isJsonNull }?.asString
                        ?: buildString {
                            val cls = item.get("className")?.takeIf { !it.isJsonNull }?.asString
                            val mtd = item.get("methodName")?.takeIf { !it.isJsonNull }?.asString
                            if (cls != null && mtd != null) append("$cls#$mtd")
                        }
                    if (sign.isNullOrEmpty()) null else sign to parseStats(item)
                }.toMap()
            }
            else -> emptyMap()
        }
    }

    private fun parseStats(o: JsonObject) = CallStats(
        today = o.get("today")?.asLong ?: 0L,
        week = o.get("week")?.asLong ?: 0L,
        p99Millis = o.get("p99Millis")?.asLong ?: 0L,
        maxExecuteTimeRequired = o.get("maxExecuteTimeRequired")?.asLong ?: 0L,
        minExecuteTimeRequired = o.get("minExecuteTimeRequired")?.asLong ?: 0L,
        avgExecuteTimeRequired = o.get("avgExecuteTimeRequired")?.asLong ?: 0L,
        errorRate = o.get("errorRate")?.asDouble ?: 0.0,
        fetchedAt = o.get("fetchedAt")?.asLong ?: System.currentTimeMillis(),
    )

    private fun userAgent(): String {
        val info = ApplicationInfo.getInstance()
        return "ProdCallStats/IDEA-${info.build}"
    }

    // ---- mock helpers ----

    private fun mockFor(handler: HandlerMethod, now: Long): CallStats {
        val rng = Random(hashCodeSeed(handler.sign, now / 60_000)) // changes once per minute
        val today = rng.nextLong(50L, 50_000L)
        val week = today * rng.nextLong(6L, 12L)
        val p99 = rng.nextLong(40L, 1_500L)
        val max = p99 + rng.nextLong(10L, 500L)
        val min = rng.nextLong(2L, 60L)
        val avg = rng.nextLong(min, p99.coerceAtLeast(min + 1))
        val errorRate = rng.nextDouble(0.0, 0.03)
        return CallStats(
            today = today,
            week = week,
            p99Millis = p99,
            maxExecuteTimeRequired = max,
            minExecuteTimeRequired = min,
            avgExecuteTimeRequired = avg,
            errorRate = errorRate,
            fetchedAt = now,
        )
    }

    private fun hashCodeSeed(key: String, salt: Long): Int {
        var h = key.hashCode()
        h = 31 * h + salt.hashCode()
        return h
    }
}
