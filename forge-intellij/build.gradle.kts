import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// The IntelliJ plugin for Forge: highlighting with the compiler's own lexer and parser, and the compiler's
// diagnostics in the editor.
//   ./gradlew :forge-intellij:buildPlugin   -> forge-intellij/build/distributions/forge-intellij-<version>.zip
//   ./gradlew :forge-intellij:runIde        -> an IDE with the plugin installed
plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.leko"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // the compiler; it is bundled with the plugin together with the ANTLR runtime (not the ANTLR tool)
    implementation(project(":")) {
        exclude(group = "org.antlr", module = "antlr4")
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("org.antlr:antlr4-runtime:4.13.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2026.2.5")
        testFramework(TestFrameworkType.Platform)
    }
}

// the IDE has its own Kotlin standard library
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains", module = "annotations")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // the compiler is built with experimental language features (see the root build), which marks its classes
        // as pre-release
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.leko.forge"
        name = "Forge"
        version = project.version.toString()
        ideaVersion {
            // the compiler needs the Kotlin standard library 2.3, which the IDE provides since 2026.2
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    // the tests read the .forge files of the repository
    systemProperty("forge.root", rootDir.absolutePath)
}
