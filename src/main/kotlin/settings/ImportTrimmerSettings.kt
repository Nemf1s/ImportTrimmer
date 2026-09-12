package io.github.nemf1s.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import io.github.nemf1s.MyMessageBundle.message

enum class RemovalMode(val messageKey: String) {
    ASK("mode.ask"),
    AUTOMATIC("mode.automatic"),
    MANUAL("mode.manual");

    override fun toString(): String = message(messageKey)
}

data class Preferences(
    var enabled: Boolean = true,
    var mode: RemovalMode = RemovalMode.ASK,
    var debounceMs: Int = 750,
    var timeoutSeconds: Int = 10,
) {
    fun normalized(): Preferences = copy(
        debounceMs = debounceMs.coerceIn(MIN_DEBOUNCE_MS, MAX_DEBOUNCE_MS),
        timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
    )

    companion object {
        const val MIN_DEBOUNCE_MS = 250
        const val MAX_DEBOUNCE_MS = 3_000
        const val MIN_TIMEOUT_SECONDS = 3
        const val MAX_TIMEOUT_SECONDS = 60
    }
}

@Service(Service.Level.PROJECT)
@State(name = "ImportTrimmerSettings", storages = [Storage("importTrimmer.xml")])
class ImportTrimmerSettings : PersistentStateComponent<Preferences> {
    private var preferences = Preferences()

    override fun getState(): Preferences = preferences.copy()

    override fun loadState(state: Preferences) {
        preferences = state.normalized()
    }
}
