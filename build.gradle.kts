import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin { jvmToolchain(25) }
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2026.2.2")
        testFramework(TestFrameworkType.Platform)

        testFramework(TestFrameworkType.Plugin.Java)
        bundledPlugin("com.intellij.java")
        pluginVerifier()
    }
}

intellijPlatform {
    instrumentCode = false
    pluginConfiguration {
        name = "Import Trimmer"
        ideaVersion {
            sinceBuild = "262.10315.125"
            untilBuild = "262.10315.125"
        }
    }
    pluginVerification { ides { current() } }
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.load.plugins.id", "com.intellij,com.intellij.java,io.github.nemf1s.ImportTrimmer")
}
