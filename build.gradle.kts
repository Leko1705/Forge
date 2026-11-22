import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.10"
    id("antlr")
    application
}

application {
    mainClass.set("MainKt")
    applicationName = "forge"
}

group = "com.leko"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    antlr("org.antlr:antlr4:4.13.1")
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-no-listener")
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated-src/antlr/main"))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// the tests use the compiler's classes, so they need the same language features
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.set(listOf("-XXLanguage:+ContextParameters", "-XXLanguage:+ExplicitBackingFields"))
    }
}

// Every src/test/forge/<name>.forge is compiled to Kotlin (package forgetest.<name>) and compiled with the tests,
// so the tests run the generated code.
val forgeTestOutput = layout.buildDirectory.dir("generated/forge-test")
val forgeTestFiles = fileTree("src/test/forge") { include("*.forge") }
val generateForgeTestSources by tasks.registering {
    group = "verification"
    description = "Compiles the Forge test programs to Kotlin"
}
forgeTestFiles.files.sortedBy { it.name }.forEach { file ->
    val name = file.nameWithoutExtension
    val task = tasks.register<JavaExec>("forgeTest_$name") {
        dependsOn(tasks.classes)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("MainKt")
        val outDir = forgeTestOutput.get().dir(name).asFile
        inputs.file(file)
        inputs.files(sourceSets.main.get().runtimeClasspath)
        outputs.dir(outDir)
        args("-f", file.absolutePath, "-d", outDir.absolutePath, "-p", "forgetest.$name")
    }
    generateForgeTestSources { dependsOn(task) }
}
kotlin.sourceSets.test { kotlin.srcDir(forgeTestOutput) }
tasks.named("compileTestKotlin") { dependsOn(generateForgeTestSources) }

// ---- the Kiln example: a language compiled to JVM class files (examples/kiln) ------------------------------
// kiln.forge (which imports jvm.forge) is compiled to Kotlin and built with the driver in examples/kiln/src:
//   ./gradlew kilnc --args="examples/kiln/programs/fib.kiln -d build/kiln --run"
val kilnOutput = layout.buildDirectory.dir("generated/kiln")
val generateKiln by tasks.registering(JavaExec::class) {
    group = "kiln"
    description = "Compiles examples/kiln/kiln.forge to Kotlin"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("MainKt")
    val outDir = kilnOutput.get().asFile
    inputs.files(fileTree("examples/kiln") { include("*.forge") })
    inputs.files(sourceSets.main.get().runtimeClasspath)
    outputs.dir(outDir)
    args("-f", file("examples/kiln/kiln.forge").absolutePath, "-d", outDir.absolutePath, "-p", "kiln")
}
val kiln: SourceSet by sourceSets.creating
kotlin.sourceSets.named("kiln") { kotlin.srcDirs("examples/kiln/src", kilnOutput) }
tasks.named("compileKilnKotlin") { dependsOn(generateKiln) }
dependencies { "kilnImplementation"(kotlin("stdlib")) }

tasks.register<JavaExec>("kilnc") {
    group = "kiln"
    description = "Runs the Kiln compiler: --args=\"<file.kiln> [-d <dir>] [--listing] [--cfg] [--run]\""
    classpath = kiln.runtimeClasspath
    mainClass.set("kiln.MainKt")
    workingDir = rootDir
}

// the tests compile Kiln programs and run the class files
dependencies { testImplementation(kiln.output) }
