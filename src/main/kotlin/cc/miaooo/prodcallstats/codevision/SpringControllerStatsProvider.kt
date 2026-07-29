package cc.miaooo.prodcallstats.codevision

import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.psi.SpringControllerScanner
import cc.miaooo.prodcallstats.settings.StatsSettingsState
import cc.miaooo.prodcallstats.stats.StatsCacheService
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Code Vision provider that paints prod call stats above Spring MVC handler
 * methods.
 *
 * Type parameter [T] is the [PsiFile] captured during the UI-thread
 * precompute step so [computeCodeVision] — which runs on a background thread —
 * can scan it without touching the EDT again.
 */
class SpringControllerStatsProvider : CodeVisionProvider<PsiFile?> {

    private val log = Logger.getInstance("ProdCallStats")

    override val id: String = PROVIDER_ID
    override val name: String = "Prod Call Stats"
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top
    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    override fun precomputeOnUiThread(editor: Editor): PsiFile? {
        val project = editor.project
        val file = if (project == null) null
            else PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        log.warn("[PCS] precomputeOnUiThread project=${project?.name} file=${file?.name} (${file?.javaClass?.simpleName})")
        return file
    }

    override fun shouldRecomputeForEditor(editor: Editor, uiData: PsiFile?): Boolean = true

    override fun computeCodeVision(editor: Editor, uiData: PsiFile?): CodeVisionState {
        val settings = StatsSettingsState.getInstance()
        val file = uiData
        log.warn("[PCS] computeCodeVision file=${file?.name} kind=${file?.javaClass?.simpleName} enabled=${settings.enabled} useMock=${settings.useMock} gatewayUrl='${settings.gatewayUrl}'")
        if (file !is PsiJavaFile) {
            log.warn("[PCS] computeCodeVision -> not a PsiJavaFile, returning empty")
            return CodeVisionState.Ready(emptyList())
        }
        if (!settings.enabled) {
            log.warn("[PCS] computeCodeVision -> plugin disabled in settings, returning empty")
            return CodeVisionState.Ready(emptyList())
        }

        // CodeVisionHost runs us on a pooled thread — PSI access requires a read action.
        val handlers: List<Pair<TextRange, HandlerMethod>> = ReadAction.compute<List<Pair<TextRange, HandlerMethod>>, Throwable> {
            val acc = mutableListOf<Pair<TextRange, HandlerMethod>>()
            var classCount = 0
            var controllerCount = 0
            PsiTreeUtil.processElements(file, PsiClass::class.java) { cls ->
                classCount++
                if (!SpringControllerScanner.isController(cls)) return@processElements true
                controllerCount++
                val clsName = cls.qualifiedName ?: cls.name ?: "<anon>"
                log.warn("[PCS] found controller: $clsName")
                cls.methods.forEach method@{ m ->
                    val hm = SpringControllerScanner.resolve(m)
                    if (hm == null) {
                        log.warn("[PCS]   - ${m.name}() -> no @*Mapping annotation, skipped")
                        return@method
                    }
                    val ident = m.nameIdentifier ?: return@method
                    log.warn("[PCS]   - ${m.name}() -> sign=${hm.sign} url=${hm.urlTemplate} method=${hm.httpMethod}")
                    acc += ident.textRange to hm
                }
                true
            }
            log.warn("[PCS] scan summary: classes=$classCount controllers=$controllerCount handlers=${acc.size}")
            acc
        }

        if (handlers.isEmpty()) {
            log.warn("[PCS] computeCodeVision -> no handlers, returning empty")
            return CodeVisionState.Ready(emptyList())
        }

        editor.project?.let { project ->
            ScheduleFetcherTask.schedule(project, file, handlers.map { it.second })
        }

        val entries = handlers.map { (range, hm) ->
            val cache = StatsCacheService.getInstance()
            val stats = cache.getNow(hm)
            val error = cache.getError(hm)
            val text = if (error != null) StatsRenderer.errorLine(hm, error) else StatsRenderer.mainLine(hm, stats)
            val tip = StatsRenderer.tooltip(hm, stats, error)
            log.warn("[PCS] entry sign=${hm.sign} stats=$stats error=${error?.javaClass?.simpleName}")
            range to entry(text, tip)
        }
        log.warn("[PCS] computeCodeVision -> returning ${entries.size} entries")
        return CodeVisionState.Ready(entries)
    }

    override fun handleClick(editor: Editor, textRange: TextRange, entry: CodeVisionEntry) {
        log.warn("[PCS] handleClick entry=${entry.javaClass.simpleName}")
        val project = editor.project ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        ScheduleFetcherTask.schedule(project, file, forceRefresh = true)
    }

    override fun isAvailableFor(project: Project): Boolean {
        val v = StatsSettingsState.getInstance().enabled
        log.warn("[PCS] isAvailableFor project=${project.name} -> $v")
        return v
    }

    private fun entry(text: String, tooltip: String): CodeVisionEntry =
        TextCodeVisionEntry(text, id, null, "", tooltip, emptyList())

    companion object {
        const val PROVIDER_ID = "prod-call-stats"
    }
}
