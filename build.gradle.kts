import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.10"
    id("antlr")
    application
}

group = "com.leko"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
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

application {
    mainClass = "MainKt"
    applicationName = "forge"
}

// The runtime of generated compilers is compiled into Forge (so it is type checked and
// unit tested) and also shipped as a resource that is copied next to generated code.
tasks.processResources {
    from("src/main/kotlin/forge/runtime/ForgeRuntime.kt") {
        into("forge/runtime")
        rename { "ForgeRuntime.kt.txt" }
    }
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// End-to-end tests: every directory in examples/ is a Forge program. Forge compiles it to Kotlin,
// the generated compiler is compiled together with the tests in src/e2eTest and executed.
// ---------------------------------------------------------------------------------------------

val forgeExamplesDir = layout.buildDirectory.dir("generated/forge-examples")

val e2eTest: SourceSet = sourceSets.create("e2eTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
kotlin.sourceSets.getByName("e2eTest").kotlin.srcDir(forgeExamplesDir)

configurations.getByName("e2eTestImplementation").extendsFrom(configurations.testImplementation.get())
configurations.getByName("e2eTestRuntimeOnly").extendsFrom(configurations.testRuntimeOnly.get())

val generateForgeExamples = tasks.register("generateForgeExamples") {
    description = "Compiles the Forge programs in examples/ to Kotlin."
}
file("examples").listFiles { f -> f.isDirectory }?.sorted()?.forEach { dir ->
    val task = tasks.register<JavaExec>("generateForgeExample_${dir.name}") {
        val sources = dir.listFiles { f -> f.extension == "forge" }!!.sorted()
        val out = forgeExamplesDir.get().dir(dir.name)
        inputs.files(sources)
        inputs.files(sourceSets.main.get().runtimeClasspath)
        outputs.dir(out)
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "MainKt"
        args = listOf("-d", out.asFile.path, "-o", dir.name, "-p", "examples.${dir.name}", "--no-runtime") + sources.map { it.path }
        doFirst { delete(out) }
    }
    generateForgeExamples { dependsOn(task) }
}
tasks.named("compileE2eTestKotlin") { dependsOn(generateForgeExamples) }

val e2eTestTask = tasks.register<Test>("e2eTest") {
    description = "Runs the compilers generated from examples/."
    group = "verification"
    testClassesDirs = e2eTest.output.classesDirs
    classpath = e2eTest.runtimeClasspath
    useJUnitPlatform()
}
tasks.check { dependsOn(e2eTestTask) }

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-XXLanguage:+ContextParameters", "-XXLanguage:+ExplicitBackingFields")
    }
}

// The ANTLR plugin puts the full ANTLR tool on the compile classpath; the runtime suffices.
configurations.api {
    setExtendsFrom(extendsFrom.filter { it.name != "antlr" })
}
