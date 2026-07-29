package cc.miaooo.prodcallstats.codevision

import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.settings.StatsSettingsState
import cc.miaooo.prodcallstats.stats.CallStats
import cc.miaooo.prodcallstats.util.HumanizeUtil
import java.text.SimpleDateFormat
import java.util.Date

object StatsRenderer {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    fun colorFor(stats: CallStats): String {
        val s = StatsSettingsState.getInstance()
        val errPct = stats.errorRate * 100.0
        val p99 = stats.p99Millis
        val red = p99 >= s.p99ErrorMillis || errPct >= s.errorRateErrorPercent
        if (red) return "#E5484D"
        val orange = p99 >= s.p99WarnMillis || errPct >= s.errorRateWarnPercent
        if (orange) return "#F5A623"
        return "#5BA46B"
    }

    fun mainLine(handler: HandlerMethod, stats: CallStats?): String {
        val settings = StatsSettingsState.getInstance()
        if (stats == null) return "🔥 Prod: loading…"
        if (stats === CallStats.EMPTY) return "🔥 Prod: no data"
        val warn = colorFor(stats) != "#5BA46B"
        val prefix = if (warn) "🔥⚠ Prod:" else "🔥 Prod:"
        return buildString {
            append(prefix).append(' ')
            append(HumanizeUtil.count(stats.today)).append(" today")
            append(" ｜ ").append(HumanizeUtil.count(stats.week)).append(" 7d")
            append(" ｜ P99 ").append(stats.p99Millis).append("ms")
            append(" ｜ Err ").append(HumanizeUtil.percent(stats.errorRate)).append("%")
            if (settings.verbose) {
                append(" ｜ Min ").append(stats.minExecuteTimeRequired).append("ms")
                append(" ｜ Avg ").append(stats.avgExecuteTimeRequired).append("ms")
                append(" ｜ Max ").append(stats.maxExecuteTimeRequired).append("ms")
            }
        }
    }

    fun tooltip(handler: HandlerMethod, stats: CallStats?): String {
        if (stats == null) {
            return "<html><b>${escape(handler.sign)}</b><br>loading…</html>"
        }
        return buildString {
            append("<html>")
            append("<b>").append(escape(handler.sign)).append("</b>")
            if (handler.httpMethod != null || handler.urlTemplate != null) {
                append("<br>")
                append(handler.httpMethod ?: "?")
                append("  ")
                append(escape(handler.urlTemplate ?: ""))
            }
            append("<br>")
            append("今日调用: ").append(HumanizeUtil.count(stats.today))
            append(" ｜ 7 日调用: ").append(HumanizeUtil.count(stats.week))
            append("<br>")
            append("Min: ").append(stats.minExecuteTimeRequired).append("ms  ")
            append("Avg: ").append(stats.avgExecuteTimeRequired).append("ms  ")
            append("Max: ").append(stats.maxExecuteTimeRequired).append("ms  ")
            append("P99: ").append(stats.p99Millis).append("ms")
            append("<br>")
            append("错误率: ").append(HumanizeUtil.percent(stats.errorRate)).append("%")
            append("  ｜  采集于: ").append(timeFormat.format(Date(stats.fetchedAt)))
            append("</html>")
        }
    }

    @Suppress("unused")
    private fun html(text: String, color: String): String =
        "<html><font color='$color'>${escape(text)}</font></html>"

    @Suppress("unused")
    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
