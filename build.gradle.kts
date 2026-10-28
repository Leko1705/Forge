import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.10"
    id("antlr")
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

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-XXLanguage:+ContextParameters", "-XXLanguage:+ExplicitBackingFields"))
}