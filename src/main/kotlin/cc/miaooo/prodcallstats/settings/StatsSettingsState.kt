package cc.miaooo.prodcallstats.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "ProdCallStatsSettings", storages = [Storage("prod-call-stats.xml")])
class StatsSettingsState : PersistentStateComponent<StatsSettingsState> {

    var enabled: Boolean = true
    var useMock: Boolean = true
    var environment: String = "prod"
    var gatewayUrl: String = ""
    var apiToken: String = ""
    /**
     * 网关接口协议版本。
     * - "v1": GET /api/v1/call-stats + POST /api/v1/call-stats/batch，results 为 map
     * - "v2": POST /api/v2/call-stats，results 为 array，每项含 className/methodName/sign 字段
     */
    var apiVersion: String = "v1"
    var refreshIntervalSeconds: Long = 60L
    var verbose: Boolean = false
    var p99WarnMillis: Long = 500L
    var p99ErrorMillis: Long = 2000L
    var errorRateWarnPercent: Double = 0.1
    var errorRateErrorPercent: Double = 1.0

    override fun getState(): StatsSettingsState = this

    override fun loadState(state: StatsSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(): StatsSettingsState =
            ApplicationManager.getApplication().service()
    }
}
