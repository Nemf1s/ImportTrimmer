package io.github.nemf1s.settings

import com.intellij.openapi.components.*
import io.github.nemf1s.MyMessageBundle.message

enum class RemovalMode(val messageKey: String) {
    ASK("mode.ask"), AUTOMATIC("mode.automatic"), MANUAL("mode.manual");
    override fun toString(): String = message(messageKey)
}
data class Preferences(
    var enabled: Boolean = true,
    var mode: RemovalMode = RemovalMode.ASK,
    var debounceMs: Int = 750,
    var timeoutSeconds: Int = 10,
) {
    fun normalized() = copy(debounceMs = debounceMs.coerceIn(250, 3000), timeoutSeconds = timeoutSeconds.coerceIn(3, 60))
}
@Service(Service.Level.PROJECT)
@State(name = "ImportTrimmerSettings", storages = [Storage("importTrimmer.xml")])
class ImportTrimmerSettings : PersistentStateComponent<Preferences> {
    private var preferences = Preferences()
    override fun getState(): Preferences = preferences.copy()
    override fun loadState(state: Preferences) { preferences = state.normalized() }
}
