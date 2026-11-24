// The editor support for Forge that does not depend on an IDE: lexing and parsing with the compiler's ANTLR
// grammar, the compiler's analysis (diagnostics, what names resolve to, the hierarchy of declarations),
// completion, and the project template. The IntelliJ plugin (forge-intellij) builds on it, and so can other
// editors, e.g. a language server.
plugins {
    kotlin("jvm")
}

group = "com.leko"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // the compiler, with the ANTLR runtime (not the ANTLR tool, which only generates the parser)
    api(project(":")) {
        exclude(group = "org.antlr", module = "antlr4")
    }
    api("org.antlr:antlr4-runtime:4.13.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // the compiler is built with experimental language features (see the root build), which marks its classes
        // as pre-release
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

tasks.test {
    useJUnitPlatform()
    // the tests read the .forge files of the repository
    systemProperty("forge.root", rootDir.absolutePath)
}
