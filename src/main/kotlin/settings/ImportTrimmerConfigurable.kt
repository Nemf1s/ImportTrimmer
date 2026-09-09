package io.github.nemf1s.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import io.github.nemf1s.ImportTrimmerProjectService
import io.github.nemf1s.MyMessageBundle.message
import javax.swing.*

class ImportTrimmerConfigurable(private val project: Project) : Configurable {
    private var enabled: JCheckBox? = null
    private var mode: JComboBox<RemovalMode>? = null
    private var debounce: JSpinner? = null
    private var timeout: JSpinner? = null
    override fun getDisplayName() = message("settings.title")
    override fun createComponent(): JComponent = panel {
        row { enabled = checkBox(message("settings.enabled")).component }
        row(message("settings.mode")) { mode = comboBox(RemovalMode.entries).component }
        row(message("settings.debounce")) { debounce = spinner(250..3000, 50).component }
        row(message("settings.timeout")) { timeout = spinner(3..60).component }
    }.also { reset() }
    private fun value() = Preferences(enabled!!.isSelected, mode!!.selectedItem as RemovalMode,
        debounce!!.value as Int, timeout!!.value as Int)
    override fun isModified() = enabled != null && value() != project.service<ImportTrimmerSettings>().state
    override fun apply() {
        project.service<ImportTrimmerSettings>().loadState(value())
        project.service<ImportTrimmerProjectService>().settingsChanged()
    }
    override fun reset() {
        val state = project.service<ImportTrimmerSettings>().state
        enabled?.isSelected = state.enabled
        mode?.selectedItem = state.mode
        debounce?.value = state.debounceMs
        timeout?.value = state.timeoutSeconds
    }
    override fun disposeUIResources() { enabled = null; mode = null; debounce = null; timeout = null }
}
