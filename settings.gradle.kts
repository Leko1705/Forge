plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Forge"

// the IntelliJ plugin for .forge files; it uses the compiler's lexer, parser and analysis
include("forge-intellij")
