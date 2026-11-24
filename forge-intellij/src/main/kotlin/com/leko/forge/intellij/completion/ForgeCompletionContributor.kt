package com.leko.forge.intellij.completion

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.AddSpaceInsertHandler
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeFile
import com.leko.forge.intellij.semantics.ForgeImports
import java.io.File
import javax.swing.Icon

/** Completes names and keywords in Forge files; see [ForgeCompletion]. */
class ForgeCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val type = position.node.elementType
        if (type in ForgeTokenTypes.COMMENTS || type in ForgeTokenTypes.STRINGS) return
        val text = position.containingFile.text
        val path = parameters.originalFile.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
        val candidates = ForgeCompletion(text, parameters.offset, path?.let(::File), ForgeImports::read).candidates()
        result.addAllElements(candidates.map(::lookup))
    }

    private fun lookup(candidate: ForgeCandidate): LookupElement {
        var builder = LookupElementBuilder.create(candidate.name)
            .withIcon(icon(candidate.kind))
            .withTypeText(candidate.type)
            .withTailText(candidate.tail, true)
        builder = when {
            candidate.kind == ForgeCandidateKind.KEYWORD -> builder.bold().withInsertHandler(keywordInsertHandler(candidate.name))
            candidate.call -> builder.withInsertHandler(ParenthesesInsertHandler.getInstance(candidate.hasParameters))
            else -> builder
        }
        return PrioritizedLookupElement.withPriority(builder, priority(candidate.kind))
    }

    private fun keywordInsertHandler(keyword: String): InsertHandler<LookupElement>? = when (keyword) {
        "this", "true", "false", "null" -> null
        else -> AddSpaceInsertHandler.INSTANCE
    }

    private fun priority(kind: ForgeCandidateKind): Double = when (kind) {
        ForgeCandidateKind.LOCAL_VARIABLE, ForgeCandidateKind.PARAMETER -> 10.0
        ForgeCandidateKind.FIELD, ForgeCandidateKind.ATTRIBUTE, ForgeCandidateKind.PROPERTY, ForgeCandidateKind.ENUM_CONSTANT -> 8.0
        ForgeCandidateKind.METHOD, ForgeCandidateKind.PARSER_RULE -> 7.0
        ForgeCandidateKind.CONSTANT, ForgeCandidateKind.FUNCTION -> 5.0
        ForgeCandidateKind.KEYWORD -> 1.0
        else -> 3.0
    }

    private fun icon(kind: ForgeCandidateKind): Icon? = when (kind) {
        ForgeCandidateKind.KEYWORD -> null
        ForgeCandidateKind.NODE -> AllIcons.Nodes.Class
        ForgeCandidateKind.INTERFACE_NODE -> AllIcons.Nodes.Interface
        ForgeCandidateKind.RECORD -> AllIcons.Nodes.Record
        ForgeCandidateKind.ENUM -> AllIcons.Nodes.Enum
        ForgeCandidateKind.BUILTIN_TYPE -> AllIcons.Nodes.Type
        ForgeCandidateKind.ENUM_CONSTANT -> AllIcons.Nodes.Enum
        ForgeCandidateKind.FIELD -> AllIcons.Nodes.Field
        ForgeCandidateKind.ATTRIBUTE -> AllIcons.Nodes.Property
        ForgeCandidateKind.PROPERTY -> AllIcons.Nodes.Property
        ForgeCandidateKind.METHOD -> AllIcons.Nodes.Method
        ForgeCandidateKind.FUNCTION -> AllIcons.Nodes.Function
        ForgeCandidateKind.CONSTANT -> AllIcons.Nodes.Constant
        ForgeCandidateKind.PARAMETER -> AllIcons.Nodes.Parameter
        ForgeCandidateKind.LOCAL_VARIABLE -> AllIcons.Nodes.Variable
        ForgeCandidateKind.PORT -> AllIcons.Nodes.Constant
        ForgeCandidateKind.GRAPH -> AllIcons.Nodes.DataTables
        ForgeCandidateKind.PARSER -> AllIcons.Nodes.Class
        ForgeCandidateKind.PARSER_RULE -> AllIcons.Nodes.Method
        ForgeCandidateKind.ANNOTATION -> AllIcons.Nodes.Annotationtype
    }
}

/** Opens the completion popup after `.` and `@` in Forge files. */
class ForgeTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file is ForgeFile && (charTyped == '.' || charTyped == '@')) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
            return Result.STOP
        }
        return Result.CONTINUE
    }
}
