package cc.miaooo.prodcallstats.settings

import cc.miaooo.prodcallstats.ProdCallStatsBundle
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.ButtonGroup

class StatsSettingsPanel {

    val root: JPanel = JPanel(GridBagLayout())

    private val enableCheckBox = JBCheckBox(ProdCallStatsBundle.message("settings.enable"))
    private val useMockCheckBox = JBCheckBox(ProdCallStatsBundle.message("settings.use.mock"))
    private val envProd = JRadioButton("prod")
    private val envPre = JRadioButton("pre")
    private val gatewayUrlField = JBTextField()
    private val apiTokenField = JBPasswordField()
    private val refreshField = JBTextField()
    private val verboseCheckBox = JBCheckBox(ProdCallStatsBundle.message("settings.verbose"))
    private val p99WarnField = JBTextField()
    private val p99ErrorField = JBTextField()
    private val errorRateWarnField = JBTextField()
    private val errorRateErrorField = JBTextField()
    private val testConnectionButton = JButton(ProdCallStatsBundle.message("settings.test.connection"))

    var isEnabled: Boolean
        get() = enableCheckBox.isSelected
        set(v) { enableCheckBox.isSelected = v }

    var useMock: Boolean
        get() = useMockCheckBox.isSelected
        set(v) { useMockCheckBox.isSelected = v }

    var environment: String
        get() = if (envProd.isSelected) "prod" else "pre"
        set(v) {
            when (v) {
                "prod" -> envProd.isSelected = true
                "pre" -> envPre.isSelected = true
            }
        }

    var gatewayUrl: String
        get() = gatewayUrlField.text
        set(v) { gatewayUrlField.text = v }

    var apiToken: String
        get() = String(apiTokenField.password)
        set(v) { apiTokenField.text = v }

    var refreshIntervalSeconds: Long
        get() = refreshField.text.toLongOrNull() ?: 60L
        set(v) { refreshField.text = v.toString() }

    var verbose: Boolean
        get() = verboseCheckBox.isSelected
        set(v) { verboseCheckBox.isSelected = v }

    var p99WarnMillis: Long
        get() = p99WarnField.text.toLongOrNull() ?: 500L
        set(v) { p99WarnField.text = v.toString() }

    var p99ErrorMillis: Long
        get() = p99ErrorField.text.toLongOrNull() ?: 2000L
        set(v) { p99ErrorField.text = v.toString() }

    var errorRateWarnPercent: Double
        get() = errorRateWarnField.text.toDoubleOrNull() ?: 0.1
        set(v) { errorRateWarnField.text = v.toString() }

    var errorRateErrorPercent: Double
        get() = errorRateErrorField.text.toDoubleOrNull() ?: 1.0
        set(v) { errorRateErrorField.text = v.toString() }

    init {
        ButtonGroup().apply { add(envProd); add(envPre) }
        testConnectionButton.addActionListener {
            // commit current field values so the test uses what the user typed
            applyToState()
            StatsSettingsConfigurable.triggerTestConnection()
        }
        buildLayout()
    }

    private fun applyToState() {
        val s = StatsSettingsState.getInstance()
        s.useMock = useMock
        s.gatewayUrl = gatewayUrl.trim()
        s.apiToken = apiToken
        s.environment = environment
    }

    private fun buildLayout() {
        val form = FormBuilder.createFormBuilder()
            .addComponent(enableCheckBox)
            .addComponent(useMockCheckBox)
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.environment")),
                JPanel().apply { add(envProd); add(envPre) })
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.gateway.url")), gatewayUrlField)
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.api.token")),
                JPanel(GridBagLayout()).apply {
                    val c = GridBagConstraints().apply {
                        insets = Insets(0, 0, 0, 0); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
                    }
                    add(JPanel().apply { add(apiTokenField) }, c)
                    add(testConnectionButton, GridBagConstraints().apply { gridx = 1; insets = Insets(0, 4, 0, 0) })
                })
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.refresh.interval")), refreshField)
            .addComponent(verboseCheckBox)
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.p99.warn")), p99WarnField)
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.p99.error")), p99ErrorField)
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.error.rate.warn")), errorRateWarnField)
            .addLabeledComponent(JBLabel(ProdCallStatsBundle.message("settings.error.rate.warn") + " (error)"), errorRateErrorField)
            .panel

        val c = GridBagConstraints().apply {
            insets = Insets(8, 12, 8, 12); fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
        }
        root.add(form, c)
        root.background = UIUtil.getPanelBackground()
    }

    fun resetFrom(state: StatsSettingsState) {
        isEnabled = state.enabled
        useMock = state.useMock
        environment = state.environment
        gatewayUrl = state.gatewayUrl
        apiToken = state.apiToken
        refreshIntervalSeconds = state.refreshIntervalSeconds
        verbose = state.verbose
        p99WarnMillis = state.p99WarnMillis
        p99ErrorMillis = state.p99ErrorMillis
        errorRateWarnPercent = state.errorRateWarnPercent
        errorRateErrorPercent = state.errorRateErrorPercent
    }

    @Suppress("unused")
    private fun compat(): JComponent = JBLabel()
}
