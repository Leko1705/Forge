// The TScript example: a language defined in Forge. tscript.forge is compiled to Kotlin (package tscript) and built
// with the Kotlin sources in src:
//   ./gradlew :tscript:run --args="..."
plugins {
    kotlin("jvm")
    application
}

group = "com.leko"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// the Forge compiler, which generates the Kotlin code of tscript.forge
val forge: Configuration by configurations.creating
dependencies {
    forge(project(":"))
    testImplementation(kotlin("test"))
}

val forgeFile = file("tscript.forge")
val generatedOutput = layout.buildDirectory.dir("generated/tscript")
val generateTscript by tasks.registering(JavaExec::class) {
    group = "tscript"
    description = "Compiles examples/tscript/tscript.forge to Kotlin"
    onlyIf { forgeFile.isFile }
    classpath = forge
    mainClass.set("MainKt")
    val outDir = generatedOutput.get().asFile
    inputs.files(fileTree(projectDir) { include("*.forge") })
    inputs.files(forge)
    outputs.dir(outDir)
    args("-f", forgeFile.absolutePath, "-d", outDir.absolutePath, "-p", "tscript", "-opt")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDirs("src", generatedOutput) }
    sourceSets.test { kotlin.srcDir("test") }
}
tasks.named("compileKotlin") { dependsOn(generateTscript) }

application {
    mainClass.set("tscript.MainKt")
    applicationName = "tscript"
}

tasks.named<JavaExec>("run") { workingDir = rootDir }

tasks.test {
    useJUnitPlatform()
}
