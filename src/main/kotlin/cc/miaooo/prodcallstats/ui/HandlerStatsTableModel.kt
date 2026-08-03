package cc.miaooo.prodcallstats.ui

import cc.miaooo.prodcallstats.gateway.GatewayException
import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.stats.CallStats
import com.intellij.psi.PsiMethod
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * One row in the class stats table. Holds the resolved [HandlerMethod], the
 * live [PsiMethod] used for click-to-navigate, and the latest snapshot from
 * the cache. [stats] / [error] are mutated in place by [ClassStatsPanel]
 * whenever the [cc.miaooo.prodcallstats.stats.StatsUpdateListener] fires,
 * then [fireTableRowsUpdated] is called.
 */
data class HandlerRow(
    val handler: HandlerMethod,
    val method: PsiMethod,
    var stats: CallStats? = null,
    var error: GatewayException? = null,
) {
    val sign: String get() = handler.sign
}

/**
 * Read-only 12-column table model for the class stats tool window.
 *
 * Column types matter: by declaring [getColumnClass] we let [TableRowSorter]
 * pick sensible default comparators, and we get right-aligned number columns
 * for free. Column-specific comparators (registered by the panel) handle the
 * null-stats case so a row that is still loading sorts to the bottom in asc
 * order and to the bottom in desc order — never jumping around.
 */
class HandlerStatsTableModel(rows: List<HandlerRow> = emptyList()) : AbstractTableModel() {

    private val _rows = rows.toMutableList()

    fun rows(): List<HandlerRow> = _rows

    fun setRows(newRows: List<HandlerRow>) {
        val oldSize = _rows.size
        _rows.clear()
        if (oldSize > 0) fireTableRowsDeleted(0, oldSize - 1)
        _rows.addAll(newRows)
        if (_rows.isNotEmpty()) fireTableRowsInserted(0, _rows.size - 1)
    }

    fun rowAt(modelRow: Int): HandlerRow = _rows[modelRow]

    fun notifyRowChanged(modelRow: Int) {
        if (modelRow in _rows.indices) fireTableRowsUpdated(modelRow, modelRow)
    }

    override fun getRowCount(): Int = _rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(columnIndex: Int): String =
        COLUMNS[columnIndex].header

    override fun getColumnClass(columnIndex: Int): Class<*> =
        COLUMNS[columnIndex].type

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val row = _rows[rowIndex]
        val s = row.stats
        return when (COLUMNS[columnIndex].kind) {
            ColumnKind.METHOD -> row.handler.methodName
            ColumnKind.DESC -> row.handler.description
            ColumnKind.HTTP -> row.handler.httpMethod
            ColumnKind.URL -> row.handler.urlTemplate
            ColumnKind.TODAY -> s?.today
            ColumnKind.WEEK -> s?.week
            ColumnKind.MIN -> s?.minExecuteTimeRequired
            ColumnKind.AVG -> s?.avgExecuteTimeRequired
            ColumnKind.MAX -> s?.maxExecuteTimeRequired
            ColumnKind.P99 -> s?.p99Millis
            ColumnKind.ERR -> s?.errorRate
            ColumnKind.FETCHED -> s?.fetchedAt
        }
    }

    private enum class ColumnKind { METHOD, DESC, HTTP, URL, TODAY, WEEK, MIN, AVG, MAX, P99, ERR, FETCHED }

    private data class ColumnDef(val header: String, val type: Class<*>, val kind: ColumnKind)

    companion object {
        private val COLUMNS: List<ColumnDef> = listOf(
            ColumnDef("Method", String::class.java, ColumnKind.METHOD),
            ColumnDef("Description", String::class.java, ColumnKind.DESC),
            ColumnDef("HTTP", String::class.java, ColumnKind.HTTP),
            ColumnDef("URL", String::class.java, ColumnKind.URL),
            ColumnDef("Today", java.lang.Long::class.java, ColumnKind.TODAY),
            ColumnDef("7d", java.lang.Long::class.java, ColumnKind.WEEK),
            ColumnDef("P99 (ms)", java.lang.Long::class.java, ColumnKind.P99),
            ColumnDef("Min (ms)", java.lang.Long::class.java, ColumnKind.MIN),
            ColumnDef("Avg (ms)", java.lang.Long::class.java, ColumnKind.AVG),
            ColumnDef("Max (ms)", java.lang.Long::class.java, ColumnKind.MAX),
            ColumnDef("Err %", java.lang.Double::class.java, ColumnKind.ERR),
            ColumnDef("Fetched", java.lang.Long::class.java, ColumnKind.FETCHED),
        )

        const val COL_METHOD = 0
        const val COL_DESC = 1
        const val COL_HTTP = 2
        const val COL_URL = 3
        const val COL_TODAY = 4
        const val COL_WEEK = 5
        const val COL_P99 = 6
        const val COL_MIN = 7
        const val COL_AVG = 8
        const val COL_MAX = 9
        const val COL_ERR = 10
        const val COL_FETCHED = 11
    }
}
