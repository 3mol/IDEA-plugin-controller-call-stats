package cc.miaooo.prodcallstats.stats

import cc.miaooo.prodcallstats.gateway.GatewayClient
import cc.miaooo.prodcallstats.gateway.GatewayException
import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.settings.StatsSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped cache of [CallStats] keyed by [HandlerMethod.sign].
 *
 * Entries are considered fresh for [StatsSettingsState.refreshIntervalSeconds].
 * Network calls are scheduled on the IntelliJ application pool so they never
 * touch the EDT. Callers always get a future back and never block on I/O.
 */
@Service(Service.Level.APP)
class StatsCacheService {

    private val log = Logger.getInstance("ProdCallStats")

    private data class Entry(
        val stats: CallStats?,
        val error: GatewayException?,
        val fetchedAt: Long,
        val inflight: CompletableFuture<CallStats?>?,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val executor = AppExecutorUtil.getAppExecutorService()

    fun getNow(handler: HandlerMethod): CallStats? = cache[handler.sign]?.stats

    fun getError(handler: HandlerMethod): GatewayException? = cache[handler.sign]?.error

    /**
     * Returns a future that will complete with the cached value if fresh,
     * or the next fetched value otherwise. The future completes on a background
     * thread.
     */
    fun fetch(handler: HandlerMethod): CompletableFuture<CallStats?> {
        val ttlMs = Duration.ofSeconds(
            StatsSettingsState.getInstance().refreshIntervalSeconds.coerceAtLeast(30L)
        ).toMillis()
        val now = System.currentTimeMillis()
        val cached = cache[handler.sign]
        if (cached != null && now - cached.fetchedAt < ttlMs) {
            cached.inflight?.let { return it }
            return CompletableFuture.completedFuture(cached.stats)
        }
        // Single-handler fetch delegates to batch with one entry.
        val result = CompletableFuture<CallStats?>()
        cache[handler.sign] = Entry(null, null, now, result)
        executor.execute {
            try {
                val stats = GatewayClient.fetch(handler)
                cache[handler.sign] = Entry(stats, null, System.currentTimeMillis(), null)
                result.complete(stats)
            } catch (e: GatewayException) {
                cache[handler.sign] = Entry(null, e, System.currentTimeMillis(), null)
                result.complete(null)
            }
        }
        return result
    }

    /**
     * Triggers a batch fetch for the given handlers. Already-fresh entries are
     * skipped. Returns immediately after scheduling; callers re-read via
     * [getNow] once Code Vision re-renders.
     *
     * On completion a [StatsUpdateListener] event is published on the
     * application message bus with the signs whose cache entry changed, so
     * tooling (e.g. the class stats tool window) can repaint without polling.
     */
    fun fetchBatch(handlers: List<HandlerMethod>) {
        if (handlers.isEmpty()) return
        val ttlMs = Duration.ofSeconds(
            StatsSettingsState.getInstance().refreshIntervalSeconds.coerceAtLeast(30L)
        ).toMillis()
        val now = System.currentTimeMillis()
        val toFetch = handlers.filter { h ->
            val e = cache[h.sign]
            e == null || now - e.fetchedAt >= ttlMs
        }
        log.warn("[PCS] fetchBatch: total=${handlers.size} toFetch=${toFetch.size} ttlMs=$ttlMs")
        if (toFetch.isEmpty()) return
        // Mark inflight first so concurrent callers don't re-schedule.
        val placeholder = CompletableFuture<CallStats?>()
        toFetch.forEach { h -> cache[h.sign] = Entry(null, null, now, placeholder) }
        executor.execute {
            val updatedSigns: List<String> = try {
                val results = GatewayClient.fetchBatch(toFetch)
                log.warn("[PCS] fetchBatch got ${results.size} results")
                results.forEach { (sign, stats) ->
                    cache[sign] = Entry(stats, null, System.currentTimeMillis(), null)
                }
                results.keys.toList()
            } catch (e: GatewayException) {
                log.warn("[PCS] fetchBatch failed: ${e.javaClass.simpleName}: ${e.message}")
                toFetch.forEach { h ->
                    cache[h.sign] = Entry(null, e, System.currentTimeMillis(), null)
                }
                toFetch.map { it.sign }
            }
            placeholder.complete(null)
            publishUpdate(updatedSigns)
        }
    }

    private fun publishUpdate(signs: List<String>) {
        if (signs.isEmpty()) return
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(StatsUpdateListener.TOPIC)
            .onStatsUpdated(signs)
    }

    fun invalidateAll() {
        cache.clear()
    }

    companion object {
        @JvmStatic
        fun getInstance(): StatsCacheService = ApplicationManager.getApplication().service()
    }
}
