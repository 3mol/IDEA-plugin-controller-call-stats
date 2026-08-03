package cc.miaooo.prodcallstats.stats

import com.intellij.util.messages.Topic

/**
 * Fired by [StatsCacheService] whenever a batch fetch completes (success or
 * failure). Subscribers (e.g. the class stats tool window) use this to repaint
 * without polling. The payload is the list of handler signs whose cache entry
 * changed, so listeners can filter cheaply.
 *
 * Always published on a background thread — listeners must hop to EDT before
 * touching Swing.
 */
interface StatsUpdateListener {

    fun onStatsUpdated(updatedSigns: List<String>)

    companion object {
        @JvmField
        val TOPIC: Topic<StatsUpdateListener> =
            Topic.create("Prod Call Stats — stats updated", StatsUpdateListener::class.java)
    }
}
