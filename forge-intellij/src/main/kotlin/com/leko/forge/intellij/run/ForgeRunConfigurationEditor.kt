package com.leko.forge.intellij.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.leko.forge.intellij.ForgeFileType
import com.leko.forge.intellij.wizard.ForgeTarget
import javax.swing.JComponent

class ForgeRunConfigurationEditor(project: Project) : SettingsEditor<ForgeRunConfiguration>() {
    private val file = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor(ForgeFileType).withTitle("Forge File"))
    }
    private val output = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Output Directory"))
    }
    private val packageName = JBTextField()
    private val target = ComboBox(ForgeTarget.entries.toTypedArray())
    private val optimize = JBCheckBox("Optimize (-opt)")
    private val keep = JBTextField()

    override fun createEditor(): JComponent = panel {
        row("Forge file:") { cell(file).align(AlignX.FILL) }
        row("Output directory:") { cell(output).align(AlignX.FILL).comment("Relative to the project") }
        row("Package:") { cell(packageName).align(AlignX.FILL).comment("The package (namespace) of the generated code (-p)") }
        row("Target language:") { cell(target) }
        row { cell(optimize) }
        row("Keep:") { cell(keep).align(AlignX.FILL).comment("With -opt: also keep these names of imported files, e.g. <code>f,Recv.g,P.rule</code>") }
    }

    override fun resetEditorFrom(configuration: ForgeRunConfiguration) {
        file.text = configuration.file
        output.text = configuration.outputDirectory
        packageName.text = configuration.packageName
        target.selectedItem = ForgeTarget.entries.firstOrNull { it.id == configuration.target } ?: ForgeTarget.KOTLIN
        optimize.isSelected = configuration.optimize
        keep.text = configuration.keep
    }

    override fun applyEditorTo(configuration: ForgeRunConfiguration) {
        configuration.file = file.text.trim()
        configuration.outputDirectory = output.text.trim()
        configuration.packageName = packageName.text.trim()
        configuration.target = (target.selectedItem as? ForgeTarget ?: ForgeTarget.KOTLIN).id
        configuration.optimize = optimize.isSelected
        configuration.keep = keep.text.trim()
    }
}
