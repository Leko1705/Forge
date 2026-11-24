package com.leko.forge.intellij.completion

import ForgeLexer
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.leko.forge.ide.parser.ForgeNameRole
import com.leko.forge.intellij.lexer.ForgeTokenTypes
import com.leko.forge.intellij.parser.ForgeRuleType
import com.leko.forge.intellij.psi.ForgeDeclaredName
import com.leko.forge.intellij.psi.ForgeNameReference
import com.leko.forge.intellij.semantics.ForgeImports
import com.leko.forge.intellij.structure.ForgeSymbolIndex
import com.leko.forge.parse.STD_SOURCE

/** The parameters of something that is called: `twice(x: int)`, `Num(value: int)`, `str.replace(old: str, replacement: str)`. */
data class ForgeSignature(val owner: String, val parameters: List<String>)

/**
 * Parameter info (Ctrl+P) in calls of functions, methods, constructors of nodes and records, and enum constants:
 * what the compiler resolved the call to, or, while the call is typed and can not be analyzed, what has its name
 * in the file, the project and the standard library.
 */
class ForgeParameterInfoHandler : ParameterInfoHandler<PsiElement, ForgeSignature> {

    private val lparen = ForgeTokenTypes.of(ForgeLexer.LPAREN)
    private val comma = ForgeTokenTypes.of(ForgeLexer.COMMA)

    private fun PsiElement.rule(): String? = (node.elementType as? ForgeRuleType)?.ruleName

    private val closing = mapOf(
        ForgeTokenTypes.of(ForgeLexer.RPAREN) to lparen,
        ForgeTokenTypes.of(ForgeLexer.RBRACKET) to ForgeTokenTypes.of(ForgeLexer.LBRACKET),
        ForgeTokenTypes.of(ForgeLexer.RBRACE) to ForgeTokenTypes.of(ForgeLexer.LBRACE),
    )
    private val opening = closing.values.toSet()

    private fun PsiElement.previousToken(): PsiElement? {
        var leaf = PsiTreeUtil.prevLeaf(this)
        while (leaf != null && (leaf.node.elementType in ForgeTokenTypes.WHITESPACES || leaf.node.elementType in ForgeTokenTypes.COMMENTS || leaf.textLength == 0)) {
            leaf = PsiTreeUtil.prevLeaf(leaf)
        }
        return leaf
    }

    /**
     * The `(` of the innermost call around [offset], from the tokens (balanced brackets are skipped), so that it
     * is found in calls that are typed, which the parser has no call for: a `(` right after a name that is not
     * declared there (`node Add(`, `fun f(` are no calls).
     */
    private fun findCall(file: PsiFile, offset: Int): PsiElement? {
        var leaf: PsiElement? = file.findElementAt(offset)
        if (leaf == null || leaf.textRange.startOffset >= offset) leaf = file.findElementAt(offset - 1)
        leaf = leaf?.takeIf { it.textRange.startOffset < offset } ?: return null
        val pending = ArrayDeque<com.intellij.psi.tree.IElementType>()
        while (leaf != null) {
            val type = leaf.node.elementType
            when {
                type in closing && leaf.textRange.startOffset < offset -> pending.addLast(closing.getValue(type))
                type in opening && pending.isNotEmpty() && pending.last() == type -> pending.removeLast()
                type == lparen && pending.isEmpty() -> {
                    val name = leaf.previousToken()
                    if (name?.node?.elementType == ForgeTokenTypes.IDENT && name.parent !is ForgeDeclaredName) return leaf
                }
                // a declaration starts at the start of a line: no call goes further back
                type in ForgeTokenTypes.KEYWORDS && leaf.textRange.startOffset == file.viewProvider.document?.let { d -> d.getLineStartOffset(d.getLineNumber(leaf!!.textRange.startOffset)) } &&
                    leaf.parent?.parent?.rule() == "Root" -> return null
            }
            leaf = leaf.previousToken()
        }
        return null
    }

    /** The name before the `(` of a call: `twice` of `twice(1)`, `upper` of `s.upper()`. */
    private fun callee(open: PsiElement): PsiElement? = open.previousToken()

    private fun signatures(open: PsiElement): List<ForgeSignature> {
        val name = callee(open) ?: return emptyList()
        // what the compiler resolved the call to
        ((name.parent as? ForgeNameReference)?.reference?.resolve() as? ForgeDeclaredName)?.let { declaration ->
            signature(declaration)?.let { return listOf(it) }
        }
        // otherwise everything that can be called with this name
        val method = name.previousToken()?.node?.elementType == ForgeTokenTypes.of(ForgeLexer.DOT)
        return candidates(open.containingFile, name.text)
            .filter { method == isMethod(it) || it.role != ForgeNameRole.FUNCTION_DECLARATION }
            .filter { !method || it.role == ForgeNameRole.FUNCTION_DECLARATION || it.role == ForgeNameRole.ENUM_CONSTANT }
            .mapNotNull(::signature).distinct()
    }

    /** `fun T.name(...)`: a method of T. */
    private fun isMethod(declaration: ForgeDeclaredName): Boolean =
        declaration.parent?.node?.findChildByType(ForgeTokenTypes.of(ForgeLexer.DOT)) != null

    private fun candidates(file: PsiFile, name: String): List<ForgeDeclaredName> {
        val project = file.project
        val files = buildList {
            add(file)
            FileBasedIndex.getInstance().getContainingFiles(ForgeSymbolIndex.NAME, name, GlobalSearchScope.allScope(project))
                .mapNotNull { PsiManager.getInstance(project).findFile(it) }.forEach(::add)
            ForgeImports.fileOf(STD_SOURCE, file)?.let(::add)
        }.distinct()
        return files.flatMap { PsiTreeUtil.findChildrenOfType(it, ForgeDeclaredName::class.java) }.filter { it.name == name }
    }

    /** The signature of a callable declaration: a function, a node with fields, a record, an enum constant. */
    private fun signature(declaration: ForgeDeclaredName): ForgeSignature? {
        val decl = declaration.parent ?: return null
        val parameters = when (decl.rule()) {
            "Function" -> if (decl.node.findChildByType(lparen) == null) return null else parametersOf(decl, "Parameter")
            "NodeDef" -> if (decl.node.findChildByType(lparen) == null) return null else parametersOf(decl, "Parameter")
            "Record" -> parametersOf(decl, "RecordField")
            "EnumItem" -> if (decl.node.findChildByType(lparen) == null) return null else parametersOf(decl, "Parameter")
            else -> return null
        }
        val owner = when (decl.rule()) {
            "Function" -> decl.text.substringBefore("(").replace(Regex("\\s+"), " ").trim()
            "EnumItem" -> declaration.name.orEmpty()
            else -> declaration.name.orEmpty()
        }
        return ForgeSignature(owner, parameters)
    }

    private fun parametersOf(decl: PsiElement, rule: String): List<String> =
        decl.children.filter { it.rule() == rule }.map { it.text.replace(Regex("\\s+"), " ") }

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val call = findCall(context.file, context.offset) ?: return null
        val signatures = signatures(call)
        if (signatures.isEmpty()) return null
        context.itemsToShow = signatures.toTypedArray()
        return call
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset + 1, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        findCall(context.file, context.offset)

    /** The argument of the caret: the commas between the `(` and the caret, not inside other brackets. */
    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        var index = 0
        var depth = 0
        var leaf = PsiTreeUtil.nextLeaf(parameterOwner)
        while (leaf != null && leaf.textRange.startOffset < context.offset) {
            val type = leaf.node.elementType
            when {
                type in opening -> depth++
                type in closing -> if (depth == 0) break else depth--
                type == comma && depth == 0 -> index++
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        context.setCurrentParameter(index)
    }

    override fun updateUI(p: ForgeSignature, context: ParameterInfoUIContext) {
        if (p.parameters.isEmpty()) {
            context.setupUIComponentPresentation("<no parameters>", -1, -1, false, false, false, context.defaultParameterColor)
            return
        }
        val text = StringBuilder()
        var start = -1
        var end = -1
        p.parameters.forEachIndexed { i, parameter ->
            if (i > 0) text.append(", ")
            if (i == context.currentParameterIndex) start = text.length
            text.append(parameter)
            if (i == context.currentParameterIndex) end = text.length
        }
        context.setupUIComponentPresentation(text.toString(), start, end, false, false, false, context.defaultParameterColor)
    }
}
