import org.jetbrains.intellij.platform.gradle.*

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin { jvmToolchain(25) }

val intellijVersion = "2026.2.2"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.getByName(integrationTestSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    testImplementation(libs.junit)
    add(integrationTestSourceSet.implementationConfigurationName, "org.junit.jupiter:junit-jupiter:5.14.4")
    add(integrationTestSourceSet.implementationConfigurationName, "org.kodein.di:kodein-di-jvm:7.26.1")
    add(integrationTestSourceSet.implementationConfigurationName, "org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    add(integrationTestSourceSet.implementationConfigurationName, "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    add(integrationTestSourceSet.runtimeOnlyConfigurationName, "org.jetbrains.teamcity:serviceMessages:2024.07")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(intellijVersion)
        testFramework(TestFrameworkType.Platform)

        testFramework(TestFrameworkType.Plugin.Java)
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
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

val intellijPlatformHome = intellijPlatform.platformPath
dependencies {
    add(
        integrationTestSourceSet.compileOnlyConfigurationName,
        files(
            intellijPlatformHome.resolve("lib/intellij.platform.ide.impl.jar"),
            intellijPlatformHome.resolve("lib/intellij.platform.analysis.impl.jar"),
        ),
    )
}

tasks.test {
    maxHeapSize = "2g"
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.load.plugins.id", "com.intellij,com.intellij.java,io.github.nemf1s.ImportTrimmer")
}

intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform {
            excludeTags("stress")
        }
        systemProperty("integration.test.ide.version", intellijVersion)
        System.getProperty("javax.net.ssl.trustStoreType")?.let {
            systemProperty("javax.net.ssl.trustStoreType", it)
        }
    }
}

intellijPlatformTesting.testIdeUi.register("stressIntegrationTest") {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform {
            includeTags("stress")
        }
        systemProperty("integration.test.ide.version", intellijVersion)
        System.getProperty("integration.stress.duration.seconds")?.let {
            systemProperty("integration.stress.duration.seconds", it)
        }
        System.getProperty("integration.stress.heap.diagnostics")?.let {
            systemProperty("integration.stress.heap.diagnostics", it)
        }
        (System.getProperty("integration.stress.mat.home") ?: System.getenv("ECLIPSE_MAT_HOME"))?.let {
            systemProperty("integration.stress.mat.home", it)
        }
        System.getProperty("javax.net.ssl.trustStoreType")?.let {
            systemProperty("javax.net.ssl.trustStoreType", it)
        }
    }
}
