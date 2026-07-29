package cc.miaooo.prodcallstats.util

object HumanizeUtil {
    fun count(value: Long): String = when {
        value < 0 -> "0"
        value < 1_000 -> value.toString()
        value < 10_000 -> trim(value.toDouble() / 1_000.0) + "K"
        value < 1_000_000 -> trim(value.toDouble() / 1_000.0) + "K"
        value < 1_000_000_000 -> trim(value.toDouble() / 1_000_000.0) + "M"
        else -> trim(value.toDouble() / 1_000_000_000.0) + "B"
    }

    private fun trim(v: Double): String {
        val rounded = (Math.round(v * 10.0) / 10.0)
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
        else rounded.toString()
    }

    fun percent(rate: Double): String {
        val pct = rate * 100.0
        return when {
            pct < 0.01 -> "0.00"
            pct < 1.0 -> String.format("%.2f", pct)
            else -> String.format("%.1f", pct)
        }
    }
}
