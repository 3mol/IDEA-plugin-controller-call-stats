package cc.miaooo.prodcallstats.stats

/**
 * Production call stats payload returned by the gateway. All time fields are in
 * milliseconds. [errorRate] is a fraction in [0, 1].
 */
data class CallStats(
    val today: Long,
    val week: Long,
    val p99Millis: Long,
    val maxExecuteTimeRequired: Long,
    val minExecuteTimeRequired: Long,
    val avgExecuteTimeRequired: Long,
    val errorRate: Double,
    val fetchedAt: Long,
) {
    companion object {
        val EMPTY = CallStats(
            today = 0L,
            week = 0L,
            p99Millis = 0L,
            maxExecuteTimeRequired = 0L,
            minExecuteTimeRequired = 0L,
            avgExecuteTimeRequired = 0L,
            errorRate = 0.0,
            fetchedAt = 0L,
        )
    }
}
