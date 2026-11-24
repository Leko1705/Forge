package com.leko.forge.intellij.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.ide.project.ForgeProjectOptions
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeFile
import com.leko.forge.intellij.parser.ForgeRuleType
import java.io.File

/** Right click on a .forge file (in the editor or the project view) | Run: compiles it. */
class ForgeRunConfigurationProducer : LazyRunConfigurationProducer<ForgeRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(ForgeConfigurationType::class.java).factory

    private fun forgeFile(context: ConfigurationContext) =
        (context.psiLocation?.containingFile ?: context.psiLocation as? ForgeFile) as? ForgeFile

    override fun setupConfigurationFromContext(
        configuration: ForgeRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = forgeFile(context)?.virtualFile?.takeIf { it.isInLocalFileSystem } ?: return false
        configuration.file = file.path
        configuration.packageName = ForgeProjectOptions.defaultPackage(file.nameWithoutExtension)
        configuration.name = configuration.suggestedName()
        return true
    }

    override fun isConfigurationFromContext(configuration: ForgeRunConfiguration, context: ConfigurationContext): Boolean {
        val file = forgeFile(context)?.virtualFile ?: return false
        return File(configuration.file).absoluteFile == File(file.path).absoluteFile
    }
}

/** A run icon in the gutter of the first declaration of a .forge file. */
class ForgeRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.node.elementType !in ForgeTokenTypes.KEYWORDS) return null
        val file = element.containingFile as? ForgeFile ?: return null
        // the first token of the first declaration: the declarations are the children of the Root rule
        val root = file.node.getChildren(null).firstOrNull { (it.elementType as? ForgeRuleType)?.ruleName == "Root" } ?: return null
        val first = root.getChildren(null).firstOrNull { it.elementType is ForgeRuleType } ?: return null
        if (PsiTreeUtil.getDeepestFirst(first.psi) !== element) return null
        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { "Compile ${file.name}" }
    }
}

/**
 * Makes the positions of the compiler's diagnostics clickable: `[calc.forge:12:3] error E009: ...` (the line from
 * 1, the column from 0), relative to [directory], the directory of the compiled file, or anywhere in the project.
 */
class ForgeConsoleFilter(private val project: Project, private val directory: File) : Filter {
    private val position = Regex("""([\w./\\-]+\.forge):(\d+):(\d+)""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val start = entireLength - line.length
        val items = position.findAll(line).mapNotNull { match ->
            val name = match.groupValues[1]
            val file = LocalFileSystem.getInstance().findFileByIoFile(File(directory, name).takeIf { it.isFile } ?: File(name))
                ?: FilenameIndex.getVirtualFilesByName(File(name).name, GlobalSearchScope.projectScope(project)).firstOrNull()
                ?: return@mapNotNull null
            val info = OpenFileHyperlinkInfo(project, file, match.groupValues[2].toInt() - 1, match.groupValues[3].toInt())
            Filter.ResultItem(start + match.range.first, start + match.range.last + 1, info)
        }.toList()
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
