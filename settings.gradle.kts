plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "Forge"

// the editor support that does not depend on an IDE, for the IntelliJ plugin and other editors
include("forge-ide-core")
// the IntelliJ plugin for .forge files; it uses the compiler's lexer, parser and analysis
include("forge-intellij")
// the language server (LSP) for .forge files, for VS Code and other editors
include("forge-lsp")
// the TScript example: a language defined in Forge (examples/tscript)
include("tscript")
project(":tscript").projectDir = file("examples/tscript")
