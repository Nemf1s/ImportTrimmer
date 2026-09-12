package io.github.nemf1s.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.panel
import io.github.nemf1s.ImportTrimmerProjectService
import io.github.nemf1s.MyMessageBundle.message
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JSpinner

class ImportTrimmerConfigurable(private val project: Project) : Configurable {
    private var enabled: JCheckBox? = null
    private var mode: JComboBox<RemovalMode>? = null
    private var debounce: JSpinner? = null
    private var timeout: JSpinner? = null

    override fun getDisplayName(): String = message("settings.title")

    override fun createComponent(): JComponent = panel {
        row {
            enabled = checkBox(message("settings.enabled")).component
        }
        row(message("settings.mode")) {
            mode = comboBox(RemovalMode.entries).component
        }
        row(message("settings.debounce")) {
            debounce = spinner(Preferences.MIN_DEBOUNCE_MS..Preferences.MAX_DEBOUNCE_MS, 50).component
        }
        row(message("settings.timeout")) {
            timeout = spinner(Preferences.MIN_TIMEOUT_SECONDS..Preferences.MAX_TIMEOUT_SECONDS).component
        }
    }.also { reset() }

    private fun value(): Preferences = Preferences(
        enabled = checkNotNull(enabled).isSelected,
        mode = checkNotNull(mode).selectedItem as RemovalMode,
        debounceMs = checkNotNull(debounce).value as Int,
        timeoutSeconds = checkNotNull(timeout).value as Int,
    )

    override fun isModified(): Boolean = enabled != null && value() != settings().state

    override fun apply() {
        settings().loadState(value())
        project.service<ImportTrimmerProjectService>().settingsChanged()
    }

    override fun reset() {
        val state = settings().state
        enabled?.isSelected = state.enabled
        mode?.selectedItem = state.mode
        debounce?.value = state.debounceMs
        timeout?.value = state.timeoutSeconds
    }

    override fun disposeUIResources() {
        enabled = null
        mode = null
        debounce = null
        timeout = null
    }

    private fun settings(): ImportTrimmerSettings = project.service()
}
