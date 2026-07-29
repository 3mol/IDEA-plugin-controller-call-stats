import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Build against the minimum supported IDEA version (2024.3) so the compiled artifact
        // remains forward-compatible. See ideaVersion.sinceBuild in the intellijPlatform block.
        intellijIdea("2024.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Prod Call Stats"
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
}

// The task spins up a headless IDE to harvest Settings search text. It tends
// to throw H2 MVStoreException on shutdown (platform bug, unrelated to our
// plugin) and we don't need custom search entries during dev.
tasks.named("buildSearchableOptions") {
    enabled = false
}
