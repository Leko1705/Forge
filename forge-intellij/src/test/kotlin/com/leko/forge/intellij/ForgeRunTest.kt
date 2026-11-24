package com.leko.forge.intellij

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.leko.forge.intellij.run.ForgeConfigurationType
import com.leko.forge.intellij.run.ForgeConsoleFilter
import com.leko.forge.intellij.run.ForgeRunConfiguration
import java.io.File
import java.nio.file.Files

class ForgeRunTest : BasePlatformTestCase() {

    private fun configuration(file: File, output: File): ForgeRunConfiguration {
        val type = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(ForgeConfigurationType::class.java)
        val settings = RunManager.getInstance(project).createConfiguration("test", type.factory)
        return (settings.configuration as ForgeRunConfiguration).apply {
            this.file = file.path
            outputDirectory = output.path
            packageName = "calc"
        }
    }

    fun testTheCompilerRunsAndWritesTheCode() {
        val dir = Files.createTempDirectory("forge-run").toFile()
        val source = File(dir, "calc.forge").apply { writeText("node Num(value: int)\nsyn Num.double: int = this.value * 2") }
        val output = File(dir, "out")
        val configuration = configuration(source, output)
        configuration.checkConfiguration()
        val result = ExecUtil.execAndGetOutput(configuration.commandLine())
        assertEquals(result.stdout + result.stderr, 0, result.exitCode)
        assertTrue(result.stdout, "wrote kotlin" in result.stdout)
        val generated = File(output, "calc.kt").readText()
        assertTrue(generated.startsWith("package calc") || "package calc" in generated)

        configuration.target = "python"
        configuration.optimize = true
        assertEquals(0, ExecUtil.execAndGetOutput(configuration.commandLine()).exitCode)
        assertTrue(File(output, "calc.py").isFile)
    }

    fun testErrorsAreReportedWithClickablePositions() {
        val dir = Files.createTempDirectory("forge-run").toFile()
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        val source = File(dir, "bad.forge").apply { writeText("node Num(value: int)\nsyn Num.double: int = \"text\"") }
        val result = ExecUtil.execAndGetOutput(configuration(source, File(dir, "out")).commandLine())
        assertEquals(1, result.exitCode)
        val line = result.stdout.lines().first { "E009" in it }
        // the compiler's position: bad.forge:2:22
        val filter = ForgeConsoleFilter(project, dir)
        val links = filter.applyFilter(line, line.length)!!.resultItems
        assertEquals(1, links.size)
        assertEquals("bad.forge:2:22", line.substring(links[0].highlightStartOffset, links[0].highlightEndOffset))
    }

    fun testTheConfigurationIsChecked() {
        val configuration = configuration(File("/nothere.forge"), File("out"))
        assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
        configuration.file = ""
        assertThrows(RuntimeConfigurationError::class.java) { configuration.checkConfiguration() }
    }

    fun testARunIconOnTheFirstDeclaration() {
        myFixture.configureByText("a.forge", "// the tree\nnode A(n: int)\nnode B(a: A)")
        val gutters = myFixture.findAllGutters().filter { it.tooltipText?.contains("Compile") == true || it.tooltipText?.contains("Run") == true }
        assertEquals(gutters.map { it.tooltipText }.toString(), 1, gutters.size)
    }
}
