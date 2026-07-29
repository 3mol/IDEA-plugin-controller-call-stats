package cc.miaooo.prodcallstats.settings

import cc.miaooo.prodcallstats.ProdCallStatsBundle
import cc.miaooo.prodcallstats.gateway.GatewayClient
import cc.miaooo.prodcallstats.stats.StatsCacheService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.JComponent

class StatsSettingsConfigurable : Configurable {

    private val state get() = StatsSettingsState.getInstance()
    private var panel: StatsSettingsPanel? = null

    override fun getDisplayName(): String =
        ProdCallStatsBundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        val p = StatsSettingsPanel()
        p.resetFrom(state)
        panel = p
        return p.root
    }

    override fun isModified(): Boolean {
        val p = panel ?: return false
        return p.isEnabled != state.enabled ||
            p.useMock != state.useMock ||
            p.environment != state.environment ||
            p.gatewayUrl != state.gatewayUrl ||
            p.apiToken != state.apiToken ||
            p.refreshIntervalSeconds != state.refreshIntervalSeconds ||
            p.verbose != state.verbose ||
            p.p99WarnMillis != state.p99WarnMillis ||
            p.p99ErrorMillis != state.p99ErrorMillis ||
            p.errorRateWarnPercent != state.errorRateWarnPercent ||
            p.errorRateErrorPercent != state.errorRateErrorPercent
    }

    override fun apply() {
        val p = panel ?: return
        state.enabled = p.isEnabled
        state.useMock = p.useMock
        state.environment = p.environment
        state.gatewayUrl = p.gatewayUrl.trim()
        state.apiToken = p.apiToken
        state.refreshIntervalSeconds = p.refreshIntervalSeconds
        state.verbose = p.verbose
        state.p99WarnMillis = p.p99WarnMillis
        state.p99ErrorMillis = p.p99ErrorMillis
        state.errorRateWarnPercent = p.errorRateWarnPercent
        state.errorRateErrorPercent = p.errorRateErrorPercent
        StatsCacheService.getInstance().invalidateAll()
    }

    override fun reset() {
        panel?.resetFrom(state)
    }

    override fun disposeUIResources() {
        panel = null
    }

    override fun getHelpTopic(): String? = null

    companion object {
        @JvmStatic
        fun triggerTestConnection() {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    val ok = GatewayClient.ping()
                    notify(if (ok) NotificationType.INFORMATION else NotificationType.WARNING,
                        ProdCallStatsBundle.message("settings.test.connection.ok"),
                        if (ok) "" else ProdCallStatsBundle.message("settings.test.connection.fail", "non-200"))
                } catch (e: Exception) {
                    notify(NotificationType.ERROR,
                        ProdCallStatsBundle.message("settings.test.connection.ok"),
                        ProdCallStatsBundle.message("settings.test.connection.fail", e.message ?: ""))
                }
            }
        }

        private fun notify(type: NotificationType, title: String, content: String) {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("Prod Call Stats")
            val project = ProjectManager.getInstance().defaultProject
            group.createNotification(title, content, type).notify(project)
        }

        @Suppress("unused")
        private fun compat(): Unit = Unit
    }
}
