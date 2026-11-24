package com.leko.forge.intellij.annotator

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.ide.diagnostics.ForgeProblem
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgeRuleType
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameElement

/**
 * Quick fixes (Alt+Enter) for errors of the compiler:
 * - `can not find 'left'` where `this.left` is meant: a child of the node or an attribute;
 * - `can not find 'Stmt'` for a type, `function 'Leaf' not found` for a constructor: creates the node;
 * - `Synthetic attribute 'Expr.type' is not implemented for Num`: adds the equation `syn Num.type`;
 * - `function 'f' not found`, `method 'R.f' not found`, `attribute 'R.x' not found`: creates the function, the
 *   extension function, the `val` (of a node: the `syn` attribute), with `TODO("not yet implemented")` as value.
 */
object ForgeQuickFixes {

    private val notFound = Regex("(?:can not find|function) '([A-Za-z_][A-Za-z_0-9]*)'(?: not found)?")
    private val notImplemented = Regex("Synthetic attribute '([A-Za-z_][A-Za-z_0-9]*)\\.([A-Za-z_][A-Za-z_0-9]*)' is not implemented for ([A-Za-z_][A-Za-z_0-9]*)")

    private fun PsiElement.rule(): String? = (node.elementType as? ForgeRuleType)?.ruleName

    /** The declaration of the whole file [element] is in: a child of the Root rule. */
    private fun topLevel(element: PsiElement): PsiElement? =
        generateSequence(element) { it.parent }.firstOrNull { it.parent?.rule() == "Root" }

    fun fixesFor(file: PsiFile, problem: ForgeProblem): List<IntentionAction> =
        problem.fixes.map { InsertFix(it) } + compilerFixes(file, problem)

    private fun compilerFixes(file: PsiFile, problem: ForgeProblem): List<IntentionAction> {
        val text = problem.text ?: return emptyList()
        val range = problem.range?.textRange ?: return emptyList()
        notImplemented.find(text)?.let { match ->
            val (_, attribute, node) = match.destructured
            return listOf(ImplementSynFix(node, attribute))
        }
        val name = notFound.find(text)?.groupValues?.get(1) ?: return emptyList()
        // the name in the range of the error: the compiler reports some on a whole type, e.g. `List<Stmt>`
        val element = PsiTreeUtil.findChildrenOfType(file, ForgeNameElement::class.java)
            .firstOrNull { it.name == name && range.contains(it.textRange) } ?: return emptyList()
        val fixes = mutableListOf<IntentionAction>()
        // in an equation of a node: `this.` before a child or an attribute
        if (element.role == ForgeNameRole.IDENTIFIER) {
            val decl = topLevel(element)
            val owner = decl?.let { ownerNode(it) }
            if (owner != null && (name in childrenOf(file, owner) || name in attributeNames(file))) fixes += AddThisFix(name)
        }
        if (name.first().isUpperCase() && element.role in setOf(ForgeNameRole.TYPE_REFERENCE, ForgeNameRole.FUNCTION_CALL, ForgeNameRole.IDENTIFIER)) {
            fixes += CreateNodeFix(name, constructor = element.role == ForgeNameRole.FUNCTION_CALL)
        }
        return fixes
    }

    /** The node whose attribute [decl] defines: `Add` of `syn Add.type`, `inh Add.depth`, `contrib Add -> ...`. */
    private fun ownerNode(decl: PsiElement): String? {
        if (decl.rule() !in setOf("SynDef", "InhImpl", "CircularDef", "Contrib", "Coll", "FlowDef")) return null
        return PsiTreeUtil.findChildrenOfType(decl, ForgeNameElement::class.java).firstOrNull { it.role == ForgeNameRole.TYPE_REFERENCE }?.name
    }

    private fun childrenOf(file: PsiFile, node: String): Set<String> =
        PsiTreeUtil.findChildrenOfType(file, ForgeDeclaredName::class.java)
            .filter { it.role == ForgeNameRole.NODE_DECLARATION && it.name == node }
            .flatMap { decl -> PsiTreeUtil.findChildrenOfType(decl.parent, ForgeDeclaredName::class.java).filter { it.role == ForgeNameRole.PARAMETER }.mapNotNull { it.name } }
            .toSet()

    private fun attributeNames(file: PsiFile): Set<String> =
        PsiTreeUtil.findChildrenOfType(file, ForgeDeclaredName::class.java).filter { it.role == ForgeNameRole.ATTRIBUTE }.mapNotNull { it.name }.toSet()

    private abstract class Fix : IntentionAction {
        override fun getFamilyName(): String = "Forge"
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file != null
        override fun startInWriteAction(): Boolean = true
        override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

        fun document(project: Project, file: PsiFile) =
            PsiDocumentManager.getInstance(project).getDocument(file)!!

        fun commit(project: Project, file: PsiFile) =
            PsiDocumentManager.getInstance(project).commitDocument(document(project, file))
    }

    /** A change the analysis worked out: a declaration that is missing, inserted where it belongs. */
    private class InsertFix(private val fix: com.leko.forge.ide.diagnostics.ForgeFix) : Fix() {
        override fun getText(): String = fix.title

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val document = document(project, file)
            if (fix.offset > document.textLength) return
            document.insertString(fix.offset, fix.text)
            commit(project, file)
            // the caret on the TODO, to write the body
            val todo = document.charsSequence.indexOf("TODO(", fix.offset)
            if (todo >= 0) editor?.caretModel?.moveToOffset(todo)
        }
    }

    /** `left` -> `this.left`, at the caret's error. */
    private class AddThisFix(private val name: String) : Fix() {
        override fun getText(): String = "Replace '$name' with 'this.$name'"

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val offset = editor?.caretModel?.offset ?: return
            val text = document(project, file).charsSequence
            // the start of the identifier at (or just before) the caret
            var start = offset.coerceAtMost(text.length)
            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
            if (text.subSequence(start, (start + name.length).coerceAtMost(text.length)).toString() != name) return
            document(project, file).insertString(start, "this.")
            commit(project, file)
        }
    }

    /** `node Stmt` (or `node Stmt()` for a constructor) at the end of the file. */
    private class CreateNodeFix(private val name: String, private val constructor: Boolean) : Fix() {
        override fun getText(): String = "Create node '$name'"

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val document = document(project, file)
            val separator = if (document.textLength == 0 || document.charsSequence.endsWith("\n")) "" else "\n"
            document.insertString(document.textLength, separator + "node $name" + (if (constructor) "()" else "") + "\n")
            commit(project, file)
        }
    }

    /** `syn Num.type: Type = TODO("not yet implemented")`, after the other equations of the attribute. */
    private class ImplementSynFix(private val node: String, private val attribute: String) : Fix() {
        override fun getText(): String = "Implement 'syn $node.$attribute'"

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val declarations = PsiTreeUtil.findChildrenOfType(file, ForgeDeclaredName::class.java)
                .filter { it.role == ForgeNameRole.ATTRIBUTE && it.name == attribute && it.parent?.rule() == "SynDef" }
            val definition = declarations.firstOrNull { decl -> decl.parent.text.let { !it.contains("=") } } ?: declarations.firstOrNull() ?: return
            val type = definition.parent.text.substringAfter(":", "").substringBefore("=").trim().ifEmpty { "int" }
            val document = document(project, file)
            val anchor = declarations.maxOf { it.parent.textRange.endOffset }
            val lineEnd = document.getLineEndOffset(document.getLineNumber(anchor))
            document.insertString(lineEnd, "\nsyn $node.$attribute: $type = TODO(\"not yet implemented\")")
            commit(project, file)
        }
    }
}
