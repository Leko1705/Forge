package com.leko.forge.intellij.wizard

import ForgeLexer
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.ide.wizard.setupProjectFromBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.validation.DialogValidation
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.leko.forge.intellij.ForgeIcons
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon

/** Links a Gradle build to the IDE; implemented with the Gradle plugin, when it is there (forge-gradle.xml). */
interface ForgeGradleLinker {
    fun link(project: Project, directory: Path)

    companion object {
        val EP_NAME = ExtensionPointName.create<ForgeGradleLinker>("com.leko.forge.gradleLinker")
    }
}

/** File | New | Project... | Forge: a Forge program, a Gradle build that compiles it, and a sample. */
class ForgeNewProjectWizard : GeneratorNewProjectWizard {
    override val id: String = "forge"
    override val name: String = "Forge"
    override val icon: Icon = ForgeIcons.FILE
    override val description: String =
        "A compiler written in Forge: a .forge program with its tree, attributes and parser, and a Gradle build that generates and runs the code."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::GitNewProjectWizardStep)
            .nextStep(::Step)

    class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
        private val kindProperty = propertyGraph.property(ForgeProjectKind.SAMPLE)
        private val targetProperty = propertyGraph.property(ForgeTarget.KOTLIN)
        private val gradleProperty = propertyGraph.property(true)
        private val packageProperty = propertyGraph.lazyProperty { ForgeProjectOptions.defaultPackage(baseData?.name.orEmpty()) }

        init {
            baseData?.let { base -> packageProperty.dependsOn(base.nameProperty) { ForgeProjectOptions.defaultPackage(base.name) } }
        }

        override fun setupUI(builder: Panel) {
            with(builder) {
                row("Project:") {
                    comboBox(ForgeProjectKind.entries).bindItem(kindProperty)
                }
                row("Target language:") {
                    comboBox(ForgeTarget.entries).bindItem(targetProperty)
                        .comment("The language the Forge compiler generates (<code>-t</code>)")
                }
                row("Package:") {
                    textField().bindText(packageProperty).columns(COLUMNS_MEDIUM)
                        .comment("The package (namespace) of the generated code (<code>-p</code>)")
                        .validationOnInput(packageValidation)
                        .validationOnApply(packageValidation)
                }
                row {
                    checkBox("Gradle build").bindSelected(gradleProperty)
                        .comment("Runs the Forge compiler of this plugin; for Kotlin, also compiles and runs the generated code")
                }
            }
        }

        private val packageValidation = DialogValidation.WithParameter<javax.swing.JTextField> { field ->
            DialogValidation {
                val text = field.text
                if (text.isNotEmpty() && !text.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
                    com.intellij.openapi.ui.ValidationInfo("Not a package name: names separated by dots", field)
                } else null
            }
        }

        override fun setupProject(project: Project) {
            val base = baseData ?: return
            val directory = Path.of(base.path, base.name)
            val options = ForgeProjectOptions(
                name = base.name,
                kind = kindProperty.get(),
                target = targetProperty.get(),
                packageName = packageProperty.get().ifBlank { ForgeProjectOptions.defaultPackage(base.name) },
                gradle = gradleProperty.get(),
            )
            Files.createDirectories(directory)
            ForgeProjectTemplate.write(directory, options, compilerJar())

            // a module with the project directory as its content root, as for an empty project
            val moduleType = ModuleTypeManager.getInstance().findByID(GeneralModuleType.TYPE_ID)
            setupProjectFromBuilder(project, moduleType.createModuleBuilder())

            val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
            root?.refresh(false, true)
            val program = root?.findFileByRelativePath("src/main/forge/${options.fileName}.forge")
            StartupManager.getInstance(project).runAfterOpened {
                ApplicationManager.getApplication().invokeLater({
                    if (program != null) FileEditorManager.getInstance(project).openFile(program, true)
                    if (options.gradle) ForgeGradleLinker.EP_NAME.extensionList.forEach { it.link(project, directory) }
                }, project.disposed)
            }
        }
    }

    companion object {
        private val LOG = logger<ForgeNewProjectWizard>()

        /** The jar of the Forge compiler this plugin bundles; `null` if the compiler is not in a jar (a development build). */
        fun compilerJar(): Path? = runCatching {
            Path.of(ForgeLexer::class.java.protectionDomain.codeSource.location.toURI())
        }.onFailure { LOG.warn("the Forge compiler is not found", it) }.getOrNull()?.takeIf { Files.isRegularFile(it) && it.toString().endsWith(".jar") }
    }
}
