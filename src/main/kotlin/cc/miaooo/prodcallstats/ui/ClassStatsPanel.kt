package cc.miaooo.prodcallstats.ui

import cc.miaooo.prodcallstats.codevision.ScheduleFetcherTask
import cc.miaooo.prodcallstats.codevision.StatsRenderer
import cc.miaooo.prodcallstats.gateway.GatewayException
import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.psi.SpringControllerScanner
import cc.miaooo.prodcallstats.stats.CallStats
import cc.miaooo.prodcallstats.stats.StatsCacheService
import cc.miaooo.prodcallstats.stats.StatsUpdateListener
import cc.miaooo.prodcallstats.util.HumanizeUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Comparator
import java.util.Date
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableCellRenderer
import java.lang.Long as JavaLong
import java.lang.Double as JavaDouble

/**
 * Bottom-docked panel listing every Spring handler in the currently active
 * controller class with the full [CallStats] field set, plus the description
 * pulled from `@ApiOperation` / `@Operation`. Click a row to jump to the
 * method definition; click a column header to sort.
 *
 * Lifecycle: built once per project by [ClassStatsToolWindowFactory]. Implements
 * [Disposable] so subscriptions on both the project and application message
 * buses are torn down with the tool window.
 *
 * PSI access is wrapped in [DumbService.runReadActionInSmartMode] to avoid
 * [com.intellij.openapi.project.IndexNotReadyException] during indexing.
 */
class ClassStatsPanel(
    private val project: Project,
) : SimpleToolWindowPanel(/* vertical */ true, /* borderless */ false), com.intellij.openapi.Disposable {

    private val log = Logger.getInstance("ProdCallStats")
    private val model = HandlerStatsTableModel()
    private val table = JBTable(model)
    private val statusLabel = JBLabel(" ")
    private val refreshButton = JButton("Refresh")
    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    private var currentFile: VirtualFile? = null
    private var currentControllerName: String? = null
    private var currentHandlers: List<HandlerRow> = emptyList()

    init {
        val topBar = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
            border = JBUI.Borders.empty(4, 8)
        }

        val renderer = HandlerCellRenderer()
        table.apply {
            autoCreateRowSorter = false
            emptyText.text = "Open a Spring @Controller / @RestController to see stats"
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            setDefaultRenderer(Any::class.java, renderer)
            setDefaultRenderer(String::class.java, renderer)
            setDefaultRenderer(JavaLong::class.java, renderer)
            setDefaultRenderer(JavaDouble::class.java, renderer)
            rowSorter = buildSorter()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) navigateSelected()
                }
            })
        }

        setContent(JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        })

        refreshButton.addActionListener {
            val vFile = currentFile
            if (vFile != null) rebuildFromCurrentFile(forceFetch = true)
        }

        // Track active editor — project bus.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    onActiveFileChanged(event.newFile)
                }
            },
        )

        // Repaint when cache publishes fresh data — app bus, hop to EDT.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            StatsUpdateListener.TOPIC,
            object : StatsUpdateListener {
                override fun onStatsUpdated(updatedSigns: List<String>) {
                    if (project.isDisposed) return
                    if (currentHandlers.isEmpty()) return
                    val hit = updatedSigns.any { s -> currentHandlers.any { it.sign == s } }
                    if (!hit) return
                    ApplicationManager.getApplication().invokeLater { refreshRowsFromCache() }
                }
            },
        )

        // Seed with whatever file is active right now.
        val initial = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        onActiveFileChanged(initial)
    }

    private fun onActiveFileChanged(newFile: VirtualFile?) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            currentFile = newFile
            rebuildFromCurrentFile(forceFetch = false)
        }
    }

    private fun rebuildFromCurrentFile(forceFetch: Boolean) {
        val vFile = currentFile
        if (vFile == null) {
            renderEmpty(" ")
            return
        }
        val psiFile = ReadAction.compute<com.intellij.psi.PsiFile?, Throwable> {
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile)
                ?: return@compute null
            PsiDocumentManager.getInstance(project).getPsiFile(doc)
        }
        if (psiFile !is PsiJavaFile) {
            renderEmpty(" ")
            return
        }

        val handlers: List<Pair<HandlerMethod, PsiMethod>> = try {
            DumbService.getInstance(project).runReadActionInSmartMode<List<Pair<HandlerMethod, PsiMethod>>> {
                scanControllerMethods(psiFile)
            }
        } catch (t: Throwable) {
            log.warn("[PCS] tool window scan failed: ${t.javaClass.simpleName}: ${t.message}")
            renderEmpty("Scan failed — see idea.log")
            return
        }

        if (handlers.isEmpty()) {
            renderEmpty("Not a Spring @Controller / @RestController")
            return
        }

        val cache = StatsCacheService.getInstance()
        val rows = handlers.map { (hm, m) ->
            HandlerRow(handler = hm, method = m, stats = cache.getNow(hm), error = cache.getError(hm))
        }
        currentHandlers = rows
        currentControllerName = handlers.first().first.className.substringAfterLast('.')
        model.setRows(rows)
        updateStatusLabel()
        refreshButton.isEnabled = true

        val psiForSchedule = psiFile
        if (forceFetch) {
            cache.fetchBatch(rows.map { it.handler })
        } else {
            // ScheduleFetcherTask coalesces per file; safe to call every switch.
            ScheduleFetcherTask.schedule(project, psiForSchedule, rows.map { it.handler })
        }
    }

    private fun refreshRowsFromCache() {
        if (project.isDisposed) return
        val cache = StatsCacheService.getInstance()
        var changed = false
        currentHandlers.forEachIndexed { index, row ->
            val s = cache.getNow(row.handler)
            val e = cache.getError(row.handler)
            if (s !== row.stats || e !== row.error) {
                row.stats = s
                row.error = e
                model.notifyRowChanged(index)
                changed = true
            }
        }
        if (changed) updateStatusLabel()
    }

    private fun renderEmpty(message: String) {
        currentHandlers = emptyList()
        currentControllerName = null
        model.setRows(emptyList())
        statusLabel.text = message
        refreshButton.isEnabled = false
    }

    private fun updateStatusLabel() {
        val name = currentControllerName ?: return
        val now = timeFormat.format(Date())
        statusLabel.text = "$name  ·  ${currentHandlers.size} handlers  ·  refreshed $now"
    }

    private fun navigateSelected() {
        val viewRow = table.selectedRow
        if (viewRow < 0) return
        val modelRow = table.convertRowIndexToModel(viewRow)
        val row = model.rowAt(modelRow)
        val psi = row.method
        if (!psi.isValid) {
            log.warn("[PCS] navigate skipped: PsiMethod no longer valid for ${row.sign}")
            return
        }
        psi.navigate(true)
    }

    private fun scanControllerMethods(file: PsiJavaFile): List<Pair<HandlerMethod, PsiMethod>> {
        val acc = mutableListOf<Pair<HandlerMethod, PsiMethod>>()
        PsiTreeUtil.processElements(file, PsiClass::class.java) { cls ->
            if (!SpringControllerScanner.isController(cls)) return@processElements true
            cls.methods.forEach { m ->
                val hm = SpringControllerScanner.resolve(m) ?: return@forEach
                acc += hm to m
            }
            true
        }
        return acc
    }

    private fun buildSorter(): RowSorter<HandlerStatsTableModel> {
        val sorter = javax.swing.table.TableRowSorter(model)
        // Comparators operate on the cell value as stored in the model. We
        // declared column classes as java.lang.Long / Double so JTable renders
        // them right-aligned, but for sorting we treat them uniformly as
        // kotlin.Number and pull out the primitive value — keeps nulls last
        // regardless of direction and avoids Kotlin/Java type-alias pitfalls.
        val nullsLastNumber: Comparator<Any?> = Comparator { a, b ->
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> {
                    // toDouble handles both Long and Double without truncation.
                    val na = (a as Number).toDouble()
                    val nb = (b as Number).toDouble()
                    na.compareTo(nb)
                }
            }
        }
        val nullsLastString: Comparator<Any?> = Comparator { a, b ->
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> (a as String).compareTo(b as String, ignoreCase = true)
            }
        }
        for (col in 0 until model.columnCount) {
            val cmp: Comparator<Any?>? = when (model.getColumnClass(col)) {
                JavaLong::class.java, JavaDouble::class.java -> nullsLastNumber
                String::class.java -> nullsLastString
                else -> null
            }
            if (cmp != null) sorter.setComparator(col, cmp)
        }
        sorter.sortKeys = listOf(RowSorter.SortKey(HandlerStatsTableModel.COL_P99, SortOrder.DESCENDING))
        return sorter
    }

    /**
     * Per-column renderer. Numeric columns right-align and format with
     * thousands separators; Err % is rendered as percent; Fetched is rendered
     * as HH:mm:ss. Method/Desc/HTTP/URL fall back to "—" when blank.
     *
     * The Method cell foreground follows [StatsRenderer.colorFor] so the
     * worst-of-(P99, Err%) threshold semantics match Code Vision — green /
     * orange / red at a glance.
     */
    private class HandlerCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val modelCol = table.convertColumnIndexToModel(column)
            horizontalAlignment = when (table.getColumnClass(column)) {
                JavaLong::class.java, JavaDouble::class.java -> RIGHT
                else -> LEFT
            }
            text = format(value, modelCol)
            if (!isSelected && modelCol == HandlerStatsTableModel.COL_METHOD) {
                val modelRow = table.convertRowIndexToModel(row)
                val tm = table.model as? HandlerStatsTableModel
                val stats = tm?.rowAt(modelRow)?.stats
                foreground = statusColor(stats)
            }
            return this
        }

        private fun format(value: Any?, modelCol: Int): String = when (value) {
            is Long -> when (modelCol) {
                HandlerStatsTableModel.COL_TODAY, HandlerStatsTableModel.COL_WEEK -> HumanizeUtil.count(value)
                HandlerStatsTableModel.COL_FETCHED -> SimpleDateFormat("HH:mm:ss").format(Date(value))
                else -> String.format("%,d", value)
            }
            is Double -> when (modelCol) {
                HandlerStatsTableModel.COL_ERR -> "${HumanizeUtil.percent(value)}%"
                else -> value.toString()
            }
            is String -> value.ifBlank { "—" }
            null -> "—"
            else -> value.toString()
        }

        private fun statusColor(stats: CallStats?): java.awt.Color {
            if (stats == null || stats === CallStats.EMPTY) return JBColor.GRAY
            return java.awt.Color.decode(StatsRenderer.colorFor(stats))
        }
    }

    override fun dispose() {
        currentHandlers = emptyList()
    }
}
