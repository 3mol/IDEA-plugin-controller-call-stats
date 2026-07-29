package cc.miaooo.prodcallstats.codevision

import cc.miaooo.prodcallstats.psi.HandlerMethod
import cc.miaooo.prodcallstats.psi.SpringControllerScanner
import cc.miaooo.prodcallstats.stats.StatsCacheService
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
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
    private val lastTrigger = ConcurrentHashMap<Project, Long>()
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
        val now = System.currentTimeMillis()
        val previous = lastTrigger[project] ?: 0L
        if (!forceRefresh && now - previous < COALESCE_MS) {
            log.warn("[PCS] schedule skip: coalesced (${now - previous}ms < $COALESCE_MS ms)")
            return
        }
        lastTrigger[project] = now
        log.warn("[PCS] schedule fetchBatch handlers=${resolved.size} forceRefresh=$forceRefresh file=${file.name}")

        val fileRef = WeakReference(file)
        val handlersRef = resolved.toList()
        ApplicationManager.getApplication().executeOnPooledThread {
            StatsCacheService.getInstance().fetchBatch(handlersRef)
            val f = fileRef.get() ?: run {
                log.warn("[PCS] schedule: file got GC'd before refresh")
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (f.isValid) {
                    log.warn("[PCS] triggering DaemonCodeAnalyzer.restart for ${f.name}")
                    DaemonCodeAnalyzer.getInstance(project).restart()
                } else {
                    log.warn("[PCS] file no longer valid, skip restart")
                }
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
}
