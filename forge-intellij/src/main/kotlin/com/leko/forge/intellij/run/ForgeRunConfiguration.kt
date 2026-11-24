package com.leko.forge.intellij.run

import ForgeLexer
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.vfs.LocalFileSystem
import com.leko.forge.intellij.ForgeIcons
import com.leko.forge.intellij.wizard.ForgeTarget
import com.leko.forge.parse.ImportException
import java.io.File
import java.nio.file.Path

/** Run | Edit Configurations... | Forge: runs the Forge compiler on a file. */
class ForgeConfigurationType : ConfigurationTypeBase(
    ID, "Forge", "Compiles a Forge program with the Forge compiler of the plugin",
    NotNullLazyValue.createValue { ForgeIcons.FILE },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                ForgeRunConfiguration(project, this, "Forge")
            override fun getOptionsClass(): Class<out BaseState> = ForgeRunConfigurationOptions::class.java
        })
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "ForgeRunConfiguration"
    }
}

class ForgeRunConfigurationOptions : LocatableRunConfigurationOptions() {
    /** The .forge file to compile. */
    var file by string("")
    /** Where the generated code goes; relative paths are relative to the project. */
    var outputDirectory by string("build/generated/forge")
    var packageName by string("")
    var target by string(ForgeTarget.KOTLIN.id)
    var optimize by property(false)
    /** For `-keep`: the names to keep with `-opt`, separated by commas. */
    var keep by string("")
}

class ForgeRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<ForgeRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): ForgeRunConfigurationOptions = super.getOptions() as ForgeRunConfigurationOptions

    var file: String
        get() = options.file.orEmpty()
        set(value) { options.file = value }
    var outputDirectory: String
        get() = options.outputDirectory.orEmpty()
        set(value) { options.outputDirectory = value }
    var packageName: String
        get() = options.packageName.orEmpty()
        set(value) { options.packageName = value }
    var target: String
        get() = options.target ?: ForgeTarget.KOTLIN.id
        set(value) { options.target = value }
    var optimize: Boolean
        get() = options.optimize
        set(value) { options.optimize = value }
    var keep: String
        get() = options.keep.orEmpty()
        set(value) { options.keep = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = ForgeRunConfigurationEditor(project)

    override fun checkConfiguration() {
        if (file.isBlank()) throw RuntimeConfigurationError("No Forge file")
        if (!File(file).isFile) throw RuntimeConfigurationError("$file does not exist")
        if (ForgeTarget.entries.none { it.id == target }) throw RuntimeConfigurationError("Unknown target '$target'")
    }

    override fun suggestedName(): String = "Compile " + File(file).name

    /** The output directory as an absolute path. */
    fun outputPath(): Path {
        val dir = Path.of(outputDirectory.ifBlank { "build/generated/forge" })
        return if (dir.isAbsolute) dir else Path.of(project.basePath ?: File(file).absoluteFile.parent).resolve(dir)
    }

    /** The command line of the compiler: `java -cp <compiler> MainKt -f <file> -d <dir> ...`. */
    fun commandLine(): GeneralCommandLine {
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val arguments = buildList {
            add("-f"); add(File(file).absolutePath)
            add("-d"); add(outputPath().toString())
            if (packageName.isNotBlank()) { add("-p"); add(packageName.trim()) }
            add("-t"); add(target)
            if (optimize) {
                add("-opt")
                if (keep.isNotBlank()) { add("-keep"); add(keep.replace(" ", "")) }
            }
        }
        return GeneralCommandLine(java.toString(), "-cp", compilerClasspath().joinToString(File.pathSeparator), "MainKt")
            .withParameters(arguments)
            // in the directory of the file: the compiler shows the names of the files in its messages
            .withWorkDirectory(File(file).absoluteFile.parent)
            .withCharset(Charsets.UTF_8)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : CommandLineState(environment) {
            init {
                addConsoleFilters(ForgeConsoleFilter(project, File(file).absoluteFile.parentFile))
            }

            override fun startProcess(): ProcessHandler {
                val handler = KillableColoredProcessHandler(commandLine())
                ProcessTerminatedListener.attach(handler)
                // the generated files appear in the project
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        val output = outputPath()
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(output)?.refresh(true, true)
                    }
                })
                return handler
            }
        }

    companion object {
        /**
         * The classpath of the compiler: its jar (in a development build: its classes and resources, which may be
         * in several directories), ANTLR's runtime and Kotlin's standard library.
         */
        fun compilerClasspath(): List<String> {
            val classes = listOf(ForgeLexer::class.java, ImportException::class.java, org.antlr.v4.runtime.Lexer::class.java, KotlinVersion::class.java)
                .map { type ->
                    // the class loaders of plugins have no code sources: the jar is found from the class file
                    PathManager.getJarPathForClass(type) ?: throw ExecutionException("${type.name} is not found")
                }
            // the standard library of Forge, a resource: in the jar, or in a directory of resources
            val std = ImportException::class.java.getResource("/forge/std.forge")
                ?.takeIf { it.protocol == "file" }
                ?.let { Path.of(it.toURI()).parent.parent.toString() }
            return (classes + listOfNotNull(std)).distinct()
        }
    }
}
