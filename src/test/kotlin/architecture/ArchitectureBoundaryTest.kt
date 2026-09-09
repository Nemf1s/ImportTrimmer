package io.github.nemf1s.architecture

import io.github.nemf1s.editing.ImportRemovalExecutor
import io.github.nemf1s.tracking.ImportTransitionTracker
import io.github.nemf1s.ui.ImportSuggestionController
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

class ArchitectureBoundaryTest {
    @Test
    fun sharedTrackerNotificationAndExecutorBytecodeHaveNoJavaPsiDependency() {
        listOf(
            ImportTransitionTracker::class.java,
            ImportSuggestionController::class.java,
            ImportRemovalExecutor::class.java,
        ).forEach { type ->
            val bytecode = bytecodeText(type)
            assertFalse("${type.name} depends on PsiJavaFile", bytecode.contains("com/intellij/psi/PsiJavaFile"))
            assertFalse("${type.name} depends on Java import PSI", bytecode.contains("com/intellij/psi/PsiImportStatement"))
        }
    }

    @Test
    fun notificationControllerHasNoPopupOrGlobalInputListenerDependency() {
        val bytecode = bytecodeText(ImportSuggestionController::class.java)

        assertFalse(bytecode.contains("JBPopup"))
        assertFalse(bytecode.contains("JBPopupFactory"))
        assertFalse(bytecode.contains("AWTEventListener"))
        assertFalse(bytecode.contains("KeyboardFocusManager"))
        assertFalse(bytecode.contains("java/awt/event/KeyEvent"))
    }

    private fun bytecodeText(type: Class<*>): String {
        val resource = "/${type.name.replace('.', '/')}.class"
        val bytes = checkNotNull(type.getResourceAsStream(resource)) { "Missing class resource $resource" }
            .use { it.readAllBytes() }
        return String(bytes, StandardCharsets.ISO_8859_1)
    }
}
