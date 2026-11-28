package com.leko.forge.intellij.annotator

import com.intellij.codeInsight.generation.ClassMember
import com.intellij.codeInsight.generation.MemberChooserObject
import com.intellij.codeInsight.generation.MemberChooserObjectBase
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.icons.AllIcons
import com.intellij.ide.util.MemberChooser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.leko.forge.ide.diagnostics.ForgeProblem
import com.leko.forge.ide.diagnostics.ForgeUnimplemented
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgeRuleType
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameElement

/**
 * Quick fixes (Alt+Enter) for errors of the compiler:
 * - `can not find 'left'` where `this.left` is meant: a child of the node or an attribute;
 * - `can not find 'Stmt'` for a type, `function 'Leaf' not found` for a constructor: creates the node;
 * - `Synthetic attribute 'Expr.type' is not implemented for Num`: `Implement 'syn Expr.type'...` chooses (as
 *   "Implement members" of Kotlin and Java) which of the nodes that do not implement it get an equation;
 * - `unnecessary safe call`: `a?.b` -> `a.b`; `unnecessary '?:'`: `a ?: b` -> `a`, as in Kotlin;
 * - a `match` of an enum without a case for each item: `Add remaining cases` (computed by the analysis);
 * - `function 'f' not found`, `method 'R.f' not found`, `attribute 'R.x' not found`: creates the function, the
 *   extension function, the `val` (of a node: the `syn` attribute), with `TODO("not yet implemented")` as value.
 */
object ForgeQuickFixes {

    private val notFound = Regex("(?:can not find|function) '([A-Za-z_][A-Za-z_0-9]*)'(?: not found)?")

    private fun PsiElement.rule(): String? = (node.elementType as? ForgeRuleType)?.ruleName

    /** The declaration of the whole file [element] is in: a child of the Root rule. */
    private fun topLevel(element: PsiElement): PsiElement? =
        generateSequence(element) { it.parent }.firstOrNull { it.parent?.rule() == "Root" }

    fun fixesFor(file: PsiFile, problem: ForgeProblem): List<IntentionAction> =
        problem.fixes.map { InsertFix(it) } + compilerFixes(file, problem)

    private fun compilerFixes(file: PsiFile, problem: ForgeProblem): List<IntentionAction> {
        if (problem.unimplemented.isNotEmpty()) return listOf(ImplementAttributesFix(problem.unimplemented))
        val text = problem.text ?: return emptyList()
        val range = problem.range?.textRange ?: return emptyList()
        if (text.startsWith("unnecessary safe call")) return listOf(ReplaceSafeCallFix(range.startOffset))
        if (text.startsWith("unnecessary '?:'")) return listOf(RemoveElvisFix(range.startOffset))
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
            if (fix.offset + fix.replace > document.textLength) return
            document.replaceString(fix.offset, fix.offset + fix.replace, fix.text)
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

    /** `a?.b` -> `a.b`: [offset] is the one of `?.`. */
    private class ReplaceSafeCallFix(private val offset: Int) : Fix() {
        override fun getText(): String = "Replace '?.' with '.'"

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val document = document(project, file)
            if (document.charsSequence.subSequence(offset, (offset + 2).coerceAtMost(document.textLength)).toString() != "?.") return
            document.replaceString(offset, offset + 2, ".")
            commit(project, file)
        }
    }

    /** `a ?: b` -> `a`: [offset] is the one of `?:`; what follows it in the elvis goes, with the space before it. */
    private class RemoveElvisFix(private val offset: Int) : Fix() {
        override fun getText(): String = "Remove the elvis operator"

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val elvis = file.findElementAt(offset)?.parent?.takeIf { it.rule() == "Elvis" } ?: return
            val text = document(project, file).charsSequence
            var start = offset
            while (start > elvis.textRange.startOffset && text[start - 1].isWhitespace()) start--
            document(project, file).deleteString(start, elvis.textRange.endOffset)
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

    /**
     * `Implement 'syn Expr.type'...`: a dialog, as "Implement members" of Kotlin and Java, with the nodes that do not
     * implement the attribute, all selected. Each one chosen gets an equation
     * `syn Num.type: Type = TODO("not yet implemented")` after the other equations of the attribute (at the end of
     * the file if there are none); the caret goes to the first TODO.
     */
    private class ImplementAttributesFix(private val missing: List<ForgeUnimplemented>) : Fix() {
        private val first = missing.first()

        override fun getText(): String = "Implement 'syn ${first.owner}.${first.attribute}'..."

        // the dialog is shown before the change, which is a command of its own
        override fun startInWriteAction(): Boolean = false

        override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
            val members = missing.map(::NodeMember)
            val chooser = MemberChooser(members.toTypedArray(), false, true, project)
            chooser.title = "Implement syn ${first.owner}.${first.attribute}"
            chooser.setCopyJavadocVisible(false)
            chooser.selectElements(members.toTypedArray())
            // tests take what the dialog selects at first; they can not show it
            val chosen = if (ApplicationManager.getApplication().isUnitTestMode) {
                // as if OK was pressed: the dialog has its selection only then
                chooser.close(DialogWrapper.OK_EXIT_CODE)
                chooser.selectedElements.orEmpty()
            } else {
                if (!chooser.showAndGet()) return
                chooser.selectedElements.orEmpty()
            }
            if (chosen.isEmpty()) return
            WriteCommandAction.writeCommandAction(project, file).withName("Implement Attribute").run<RuntimeException> {
                implement(project, editor, file, chosen.map { it.attribute })
            }
        }

        private fun implement(project: Project, editor: Editor?, file: PsiFile, attributes: List<ForgeUnimplemented>) {
            val document = document(project, file)
            PsiDocumentManager.getInstance(project).commitDocument(document)
            val equations = PsiTreeUtil.findChildrenOfType(file, ForgeDeclaredName::class.java)
                .filter { it.role == ForgeNameRole.ATTRIBUTE && it.parent?.rule() == "SynDef" }
                .groupBy({ it.name }, { it.parent.textRange.endOffset })
            fun equation(a: ForgeUnimplemented) = "syn ${a.node}.${a.attribute}: ${a.type} = TODO(\"not yet implemented\")"
            // after the last equation of the attribute: the end of its line; null for the end of the file
            val byAnchor = attributes.groupBy { a ->
                equations[a.attribute]?.max()?.let { document.getLineEndOffset(document.getLineNumber(it)) }
            }
            // from the end, so that the offsets before stay where they are
            for ((anchor, here) in byAnchor.entries.sortedByDescending { it.key ?: Int.MAX_VALUE }) {
                if (anchor != null) {
                    document.insertString(anchor, here.joinToString("") { "\n" + equation(it) })
                } else {
                    val separator = if (document.textLength == 0 || document.charsSequence.endsWith("\n")) "" else "\n"
                    document.insertString(document.textLength, separator + here.joinToString("") { equation(it) + "\n" })
                }
            }
            commit(project, file)
            val firstEquation = document.charsSequence.indexOf(equation(attributes.first()))
            if (firstEquation >= 0) editor?.caretModel?.moveToOffset(document.charsSequence.indexOf("TODO(", firstEquation))
        }
    }

    /**
     * A node in the dialog of [ImplementAttributesFix]: `Num` for `syn Num.type`, under the interface of the attribute
     * (the dialog needs a parent for each member).
     */
    private class NodeMember(val attribute: ForgeUnimplemented) :
        MemberChooserObjectBase(attribute.node, AllIcons.Nodes.Class), ClassMember {
        override fun getParentNodeDelegate(): MemberChooserObject = InterfaceGroup(attribute.owner)
        override fun equals(other: Any?): Boolean = other is NodeMember && other.attribute == attribute
        override fun hashCode(): Int = attribute.hashCode()
    }

    private class InterfaceGroup(val name: String) : MemberChooserObjectBase(name, AllIcons.Nodes.Interface) {
        override fun equals(other: Any?): Boolean = other is InterfaceGroup && other.name == name
        override fun hashCode(): Int = name.hashCode()
    }
}
