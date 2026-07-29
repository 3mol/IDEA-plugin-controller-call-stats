package cc.miaooo.prodcallstats.bootstrap

import cc.miaooo.prodcallstats.codevision.SpringControllerStatsProvider
import cc.miaooo.prodcallstats.settings.StatsSettingsState
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Logs Code Vision registration state on project open and ensures our provider
 * is enabled. Output is tagged `[PCS]` so it can be filtered in idea.log.
 */
class ProdCallStatsStartupActivity : ProjectActivity {

    private val log = Logger.getInstance("ProdCallStats")

    override suspend fun execute(project: Project) {
        val app = ApplicationManager.getApplication()
        if (app.isHeadlessEnvironment || app.isUnitTestMode) {
            // buildSearchableOptions / tests: skip side effects to avoid H2 writes.
            return
        }
        log.warn("[PCS] === plugin loaded in project: ${project.name} ===")
        val settings = StatsSettingsState.getInstance()
        log.warn("[PCS] plugin settings: enabled=${settings.enabled} useMock=${settings.useMock} gatewayUrl='${settings.gatewayUrl}'")

        val cvSettings = CodeVisionSettings.getInstance()
        log.warn("[PCS] CodeVision master enabled: ${cvSettings.codeVisionEnabled}")
        log.warn("[PCS] CodeVision disabled provider ids: ${cvSettings.disabledCodeVisionProviderIds}")

        // List every provider the engine knows about.
        val host = runCatching { project.service<CodeVisionHost>() }.getOrNull()
        if (host == null) {
            log.warn("[PCS] CodeVisionHost not available as a project service")
        } else {
            val providers: List<CodeVisionProvider<*>> = runCatching { host.providers }.getOrDefault(emptyList())
            log.warn("[PCS] CodeVisionHost providers (${providers.size}):")
            providers.forEach { p ->
                log.warn("[PCS]   - id='${p.id}' name='${p.name}' class=${p.javaClass.name}")
            }
            val mine = providers.firstOrNull { it.id == SpringControllerStatsProvider.PROVIDER_ID }
            if (mine == null) {
                log.warn("[PCS] *** OUR PROVIDER IS NOT REGISTERED ***")
            } else {
                log.warn("[PCS] our provider is registered: ${mine.javaClass.name}")
            }
        }

        // Ensure our provider is enabled (default is enabled unless in disabled set,
        // but a stale value may persist from earlier plugin loads).
        if (!cvSettings.isProviderEnabled(SpringControllerStatsProvider.PROVIDER_ID)) {
            log.warn("[PCS] our provider was disabled, re-enabling")
            cvSettings.setProviderEnabled(SpringControllerStatsProvider.PROVIDER_ID, true)
        } else {
            log.warn("[PCS] our provider is enabled in CodeVisionSettings")
        }

        // Fire one cache invalidate so existing lenses get re-computed with our provider.
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                runCatching {
                    com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                }
            }
        }
    }
}
