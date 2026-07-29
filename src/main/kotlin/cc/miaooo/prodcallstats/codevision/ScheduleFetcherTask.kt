package cc.miaooo.prodcallstats.codevision

import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.psi.SpringControllerScanner
import cc.miaooo.prodcallstats.stats.StatsCacheService
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces batch fetch requests from [SpringControllerStatsProvider].
 * Subsequent calls for the same project within [COALESCE_MS] are skipped so
 * we don't issue redundant network calls on every Code Vision recompute.
 */
object ScheduleFetcherTask {

    private const val COALESCE_MS = 5_000L
    private val lastTrigger = ConcurrentHashMap<String, Long>()
    private val log = Logger.getInstance("ProdCallStats")

    fun schedule(
        project: Project,
        file: PsiFile,
        handlers: List<HandlerMethod> = emptyList(),
        forceRefresh: Boolean = false,
    ) {
        val resolved = if (handlers.isEmpty()) extractHandlers(file) else handlers
        if (resolved.isEmpty()) {
            log.warn("[PCS] schedule skip: no handlers (file=${file.name})")
            return
        }
        // Coalesce per file instead of per project — switching files inside the
        // 5s window would otherwise leave the second file stuck on "loading…".
        val coalesceKey = "${project.locationHash}/${file.name}"
        val now = System.currentTimeMillis()
        val previous = lastTrigger[coalesceKey] ?: 0L
        if (!forceRefresh && now - previous < COALESCE_MS) {
            log.warn("[PCS] schedule skip: coalesced for $coalesceKey (${now - previous}ms < $COALESCE_MS ms)")
            return
        }
        lastTrigger[coalesceKey] = now
        log.warn("[PCS] schedule fetchBatch handlers=${resolved.size} forceRefresh=$forceRefresh file=${file.name}")

        val fileRef = WeakReference(file)
        val projectRef = WeakReference(project)
        val handlersRef = resolved.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            StatsCacheService.getInstance().fetchBatch(handlersRef)
            val f = fileRef.get() ?: run {
                log.warn("[PCS] schedule: file got GC'd before refresh")
                return@executeOnPooledThread
            }
            val p = projectRef.get() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!f.isValid || p.isDisposed) {
                    log.warn("[PCS] skip refresh: file/project gone")
                    return@invokeLater
                }
                invalidateCodeVision(p, f.virtualFile)
            }
        }
    }

    /**
     * Force every open editor showing [vFile] to recompute Code Vision entries
     * for our provider. Uses [CodeVisionHost.invalidateProvider] which is the
     * official refresh signal — DaemonCodeAnalyzer.restart() does not always
     * reach Code Vision.
     *
     * Safe to call during project indexing — if the project is in dumb mode,
     * the refresh is deferred until indexing finishes via [DumbService.runWhenSmart].
     */
    fun invalidateCodeVision(project: Project, vFile: VirtualFile?) {
        if (vFile == null || project.isDisposed) return
        if (DumbService.isDumb(project)) {
            log.warn("[PCS] invalidateCodeVision: project in dumb mode, deferring for ${vFile.name}")
            DumbService.getInstance(project).runWhenSmart { invalidateCodeVision(project, vFile) }
            return
        }
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return
        val editors: Array<Editor> = EditorFactory.getInstance().getEditors(document, project)
        if (editors.isEmpty()) {
            log.warn("[PCS] invalidateCodeVision: no editors for ${vFile.name}")
            return
        }
        val host = runCatching { project.service<CodeVisionHost>() }.getOrNull() ?: return
        for (editor in editors) {
            try {
                val signal = CodeVisionHost.LensInvalidateSignal(editor, listOf(SpringControllerStatsProvider.PROVIDER_ID))
                host.invalidateProvider(signal)
                log.warn("[PCS] invalidated Code Vision for ${vFile.name}")
            } catch (t: Throwable) {
                log.warn("[PCS] invalidateProvider failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun extractHandlers(file: PsiFile): List<HandlerMethod> {
        if (file !is PsiJavaFile) return emptyList()
        return ReadAction.compute<List<HandlerMethod>, Throwable> {
            val result = mutableListOf<HandlerMethod>()
            PsiTreeUtil.processElements(file, PsiClass::class.java) { cls ->
                if (!SpringControllerScanner.isController(cls)) return@processElements true
                cls.methods.forEach { m ->
                    SpringControllerScanner.resolve(m)?.let { result += it }
                }
                true
            }
            result
        }
    }

    @Suppress("unused")
    private fun docCompat(): Any = PsiDocumentManager::class.java
}
