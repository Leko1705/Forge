package com.leko.forge.intellij

import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.intellij.annotator.ForgeCompilerAnnotator
import com.leko.forge.intellij.annotator.ForgeSource
import com.leko.forge.intellij.wizard.ForgeNewProjectWizard
import com.leko.forge.intellij.wizard.ForgeProjectKind
import com.leko.forge.intellij.wizard.ForgeProjectOptions
import com.leko.forge.intellij.wizard.ForgeProjectTemplate
import com.leko.forge.intellij.wizard.ForgeTarget
import java.nio.file.Files
import java.nio.file.Path

class ForgeProjectWizardTest : BasePlatformTestCase() {

    fun testTheWizardIsInTheNewProjectDialog() {
        assertTrue(GeneratorNewProjectWizard.EP_NAME.extensionList.any { it is ForgeNewProjectWizard })
    }

    fun testTheProgramsAreCorrect() {
        for (kind in ForgeProjectKind.entries) for (target in ForgeTarget.entries) {
            val options = ForgeProjectOptions("my-lang", kind, target)
            val files = ForgeProjectTemplate.textFiles(options)
            val program = files.getValue("src/main/forge/my_lang.forge")
            val errors = ForgeCompilerAnnotator.analyze(ForgeSource(program, null)).filter { it.severity == HighlightSeverity.ERROR }
            assertEquals("$kind $target", emptyList<Any>(), errors)
            assertTrue("-t ${target.id}" in files.getValue("build.gradle.kts").replace("\", \"", " "))
            assertEquals(target == ForgeTarget.KOTLIN, "src/main/kotlin/mylang/Main.kt" in files)
        }
    }

    fun testOptions() {
        assertEquals("my_lang", ForgeProjectOptions("My Lang!").fileName.lowercase().replace("__", "_").trimEnd('_'))
        assertEquals("mylang", ForgeProjectOptions.defaultPackage("My-Lang"))
        assertEquals("_2d", ForgeProjectOptions.defaultPackage("2d"))
        val noGradle = ForgeProjectTemplate.textFiles(ForgeProjectOptions("calc", gradle = false))
        assertEquals(setOf("src/main/forge/calc.forge", ".gitignore", "README.md"), noGradle.keys)
    }

    fun testWritingAProject() {
        // the compiler is the jar in the plugin's lib (also in the tests, which run the plugin of the sandbox)
        val jar = ForgeNewProjectWizard.compilerJar()
        assertNotNull("the compiler of the plugin is found", jar)
        // FORGE_WIZARD_OUT keeps the projects, e.g. to build them
        val out = System.getenv("FORGE_WIZARD_OUT")?.let { Path.of(it) } ?: Files.createTempDirectory("forge-projects")
        for (kind in ForgeProjectKind.entries) for (target in listOf(ForgeTarget.KOTLIN, ForgeTarget.JAVA)) {
            ForgeProjectTemplate.write(out.resolve("${kind.name.lowercase()}-${target.id}"), ForgeProjectOptions("calc", kind, target), jar)
        }
        val directory = out.resolve("sample-kotlin")
        for (path in listOf("build.gradle.kts", "settings.gradle.kts", "gradlew", "gradle/wrapper/gradle-wrapper.jar",
            "lib/forge-compiler.jar", "src/main/forge/calc.forge", "src/main/kotlin/calc/Main.kt")) {
            assertTrue(path, Files.isRegularFile(directory.resolve(path)))
        }
        assertTrue(Files.isExecutable(directory.resolve("gradlew")))
    }
}
