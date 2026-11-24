// The Forge language server: the editor support of forge-ide-core over the Language Server Protocol, for VS Code and
// other editors. It talks LSP on stdin and stdout.
//   ./gradlew :forge-lsp:serverJar   -> forge-lsp/build/libs/forge-lsp-<version>-all.jar (java -jar ...)
plugins {
    kotlin("jvm")
    application
}

group = "com.leko"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":forge-ide-core"))
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.leko.forge.lsp.MainKt")
    applicationName = "forge-lsp"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // the compiler is built with experimental language features (see the root build), which marks its classes
        // as pre-release
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

// one jar with the server and everything it needs, for the VS Code extension
val serverJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Builds the language server as one runnable jar"
    archiveClassifier.set("all")
    manifest { attributes("Main-Class" to application.mainClass.get()) }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.test {
    useJUnitPlatform()
    // the tests read the .forge files of the repository
    systemProperty("forge.root", rootDir.absolutePath)
}
