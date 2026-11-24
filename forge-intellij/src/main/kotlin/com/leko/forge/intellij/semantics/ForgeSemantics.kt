package com.leko.forge.intellij.semantics

import ForgeParser
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.leko.forge.analysis.Dependency
import com.leko.forge.attr.Attr
import com.leko.forge.attr.SymTab
import com.leko.forge.attr.TypeMap
import com.leko.forge.attr.clearTreeAttributes
import com.leko.forge.attr.port
import com.leko.forge.attr.symbol
import com.leko.forge.attr.local
import com.leko.forge.context.Context
import com.leko.forge.enter.Enter
import com.leko.forge.enter.MemberEnter
import com.leko.forge.intellij.parser.ForgeNameRole
import com.leko.forge.intellij.parser.ForgePsiParser
import com.leko.forge.intellij.parser.nameRole
import com.leko.forge.model.*
import com.leko.forge.parse.parseForgeImports
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.TerminalNode
import java.io.File

/**
 * What a name refers to, as the compiler resolved it: the declaration at [line]:[column] (ANTLR's: lines from 1,
 * columns from 0) of [source] (`null`: the analyzed file; [com.leko.forge.parse.STD_SOURCE]: the standard library;
 * otherwise the path of an imported file), and the [role] its use has, e.g. [ForgeNameRole.ATTRIBUTE] for
 * `this.type` if `type` is a syn attribute.
 */
data class ForgeResolution(val name: String, val source: String?, val line: Int, val column: Int, val role: ForgeNameRole?)

/** The names of a file the compiler resolved, by the offset where they start. */
class ForgeSemantics(val resolutions: Map<Int, ForgeResolution>) {

    companion object {
        private val LOG = logger<ForgeSemantics>()

        /** The compiler keeps the attributes of the trees in one global table, so analyses run one at a time. */
        val lock = Any()

        private val EMPTY = ForgeSemantics(emptyMap())

        /** The semantics of [file], computed once for each version of the program. */
        fun of(file: PsiFile): ForgeSemantics = CachedValuesManager.getCachedValue(file) {
            val path = file.originalFile.virtualFile?.takeIf { it.isInLocalFileSystem }?.path
            CachedValueProvider.Result.create(
                compute(file.text, path?.let(::File), ForgeImports::read),
                PsiModificationTracker.MODIFICATION_COUNT,
            )
        }

        /**
         * Analyzes [text], the text of [file] (`null` if it is not on disk; then its imports can not be found),
         * with the compiler, and records what its names resolve to. Imported files are read with [read]. Empty if
         * the analysis is not possible, e.g. for a missing import, or fails on an incomplete tree.
         */
        fun compute(text: String, file: File?, read: (File) -> CharStream): ForgeSemantics {
            val parser = ForgeParser(CommonTokenStream(ListTokenSource(ForgePsiParser.antlrTokens(text))))
            parser.removeErrorListeners()
            val main = parser.root()
            if (file == null && main.importDecl().any { it.path.text != "\"std\"" }) return EMPTY
            return synchronized(lock) {
                try {
                    val root = (file ?: File("<editor>")).parseForgeImports(main, read)
                    val context = Context()
                    Enter.instance(context).enter(root)
                    MemberEnter.instance(context).enter(root)
                    Attr.instance(context).attr(root)
                    Dependency.instance(context).check(root)
                    Collector(context).apply { visit(main) }.let { ForgeSemantics(it.resolutions) }
                } catch (e: Throwable) {
                    if (e is ControlFlowException) throw e
                    // the analysis needs a complete tree; while the file is edited, it may not be
                    if (parser.numberOfSyntaxErrors == 0) LOG.debug("the Forge analysis failed", e)
                    EMPTY
                } finally {
                    clearTreeAttributes()
                }
            }
        }
    }

    /** Walks the tree of the analyzed file while the attributes of the analysis are there. */
    private class Collector(context: Context) {
        private val symTab = SymTab.instance(context)
        private val typeMap = TypeMap.instance(context)
        val resolutions = mutableMapOf<Int, ForgeResolution>()

        fun visit(tree: ParseTree) {
            if (tree is TerminalNode) {
                val context = tree.parent as? ParserRuleContext ?: return
                val token = tree.symbol
                if (token.type != ForgeParser.IDENT || token.startIndex < 0) return
                val symbol = resolve(context, token) ?: return
                val span = symbol.span ?: return
                if (span.startLine <= 0) return
                resolutions[token.startIndex] = ForgeResolution(
                    symbol.name, span.source, span.startLine, span.startCol, role(symbol, nameRole(context, token)),
                )
                return
            }
            for (i in 0 until tree.childCount) visit(tree.getChild(i))
        }

        private fun type(name: String): Symbol? = symTab.table.values.firstOrNull { it.name == name && it.kind == Kind.TYP }

        private fun resolve(context: ParserRuleContext, token: Token): Symbol? {
            val symbol: Symbol? = when (context) {
                is ForgeParser.IdentifierContext -> context.symbol ?: context.port ?: context.local
                is ForgeParser.FieldAccessContext -> context.symbol.takeIf { token === context.name }
                is ForgeParser.FunctionCallContext -> (context.symbol ?: context.port).takeIf { token === context.name }
                is ForgeParser.MethodCallContext -> context.symbol.takeIf { token === context.name }
                is ForgeParser.TypePatternContext -> when (token) {
                    context.name -> context.symbol
                    context.receiver -> type(token.text)
                    else -> null
                }
                is ForgeParser.NamedTypeContext -> type(token.text)
                is ForgeParser.NodeDefContext -> type(token.text).takeIf { token === context.parent }
                is ForgeParser.NodeInterfaceImplContext -> type(token.text)
                is ForgeParser.SynDefContext -> type(token.text).takeIf { token === context.node }
                is ForgeParser.CircularDefContext -> type(token.text).takeIf { token === context.node }
                is ForgeParser.CollContext -> type(token.text).takeIf { token === context.node }
                is ForgeParser.GraphDefContext -> type(token.text).takeIf { token === context.rootNode }
                is ForgeParser.InhImplContext -> when (token) {
                    context.node -> type(token.text)
                    context.attrName -> context.symbol ?: symTab.inheritedAttributes[token.text]
                    else -> null
                }
                is ForgeParser.ContribContext -> when (token) {
                    context.node -> type(token.text)
                    context.collName -> context.symbol
                    else -> null
                }
                is ForgeParser.FlowDefContext -> when (token) {
                    context.node -> type(token.text)
                    context.graph -> symTab.graphs.values.firstOrNull { it.name == token.text }
                    else -> null
                }
                is ForgeParser.InhDelegateContext -> {
                    val impl = context.parent as? ForgeParser.InhImplContext
                    (impl?.node?.text?.let(::type) as? ConcreteNodeSymbol)?.fields?.firstOrNull { it.name == token.text }
                }
                is ForgeParser.ParseRuleDefContext ->
                    symTab.parsers.values.firstOrNull { it.name == token.text }.takeIf { token === context.parserName }
                is ForgeParser.NonterminalRuleContext -> {
                    val rule = generateSequence(context as ParserRuleContext?) { it.parent as? ParserRuleContext }
                        .filterIsInstance<ForgeParser.ParseRuleDefContext>().firstOrNull()
                    val parser = rule?.let { r -> symTab.parsers.values.firstOrNull { it.name == r.parserName.text } }
                    parser?.let { p ->
                        typeMap.parserRuleDefs[p].orEmpty().firstOrNull { it.name == token.text }
                            ?: typeMap.parserRuleImpls[p].orEmpty().firstOrNull { it.name == token.text }
                    }
                }
                else -> null
            }
            return if (symbol is FunctionSymbol) symbol.declaration else symbol
        }

        /** The role a use of [symbol] has; the grammar's [grammarRole], unless the symbol says more. */
        private fun role(symbol: Symbol, grammarRole: ForgeNameRole?): ForgeNameRole? = when (symbol) {
            is VarSymbol -> when (symbol.varKind) {
                VarSymbol.VarKind.PARAMETER -> ForgeNameRole.PARAMETER
                VarSymbol.VarKind.VARIABLE -> ForgeNameRole.LOCAL_VARIABLE
                VarSymbol.VarKind.NODE_FIELD, VarSymbol.VarKind.RECORD_FIELD -> ForgeNameRole.FIELD
                VarSymbol.VarKind.PARENT_INH -> ForgeNameRole.ATTRIBUTE
            }
            is AttributeSymbol -> ForgeNameRole.ATTRIBUTE
            is FunctionSymbol -> if (symbol.isProperty) ForgeNameRole.PROPERTY else grammarRole
            is ConstSymbol -> ForgeNameRole.CONSTANT
            is EnumItemSymbol -> ForgeNameRole.ENUM_CONSTANT
            is PortSymbol -> ForgeNameRole.PORT
            is FlowGraphSymbol -> ForgeNameRole.GRAPH
            is NodeSymbol, is RecordSymbol, is EnumSymbol, is ParserSymbol ->
                if (grammarRole == ForgeNameRole.IDENTIFIER) ForgeNameRole.TYPE_REFERENCE else grammarRole
            else -> grammarRole
        }
    }
}
