package com.leko.forge.ide.diagnostics

import ForgeParser
import com.leko.forge.attr.Resolve
import com.leko.forge.attr.callee
import com.leko.forge.attr.expectedOfMissing
import com.leko.forge.attr.flowBuiltin
import com.leko.forge.attr.port
import com.leko.forge.attr.symbol
import com.leko.forge.attr.typeOrNull
import com.leko.forge.context.Context
import com.leko.forge.model.NodeSymbol
import com.leko.forge.types.*
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree

/**
 * A change of the text that fixes a problem: [text] inserted at [offset] in place of the [replace] characters there,
 * e.g. a declaration that is missing. [name] is the offset of the name whose problem it fixes.
 */
data class ForgeFix(val title: String, val offset: Int, val text: String, val name: Int, val replace: Int = 0)

/**
 * Declarations that a file uses but nobody declares, with what they would be: a function for a call of an unknown
 * function, a method for an unknown method, a `val` (of a node: a `syn` attribute) for an unknown property. Their
 * types are those of the arguments and what was expected where they are used; their value is
 * `TODO("not yet implemented")`. Computed while the attributes of the analysis are there.
 */
class ForgeMissingDeclarations(private val context: Context, private val text: String) {

    private val resolve = Resolve.instance(context)

    fun fixes(main: ForgeParser.RootContext): List<ForgeFix> = buildList {
        fun walk(t: ParseTree) {
            when (t) {
                is ForgeParser.FunctionCallContext -> function(t)?.let { add(it) }
                is ForgeParser.MethodCallContext -> method(t)?.let { add(it) }
                is ForgeParser.FieldAccessContext -> property(t)?.let { add(it) }
            }
            for (i in 0 until t.childCount) walk(t.getChild(i))
        }
        walk(main)
    }

    private val body = "TODO(\"not yet implemented\")"

    /** `fun name(a: int): R` for `name(a)`; a capitalized name is a node or a record, for which there is a fix of its own. */
    private fun function(call: ForgeParser.FunctionCallContext): ForgeFix? {
        if (call.symbol != null || call.callee != null || call.port != null || call.flowBuiltin != null) return null
        val name = call.name.text
        if (name.first().isUpperCase() || name.contains("::")) return null
        val params = parameters(call.expression())
        val result = resultOf(call)
        val declaration = "fun ${typeParams(params.map { it.second } + result)}$name(${params.joinToString(", ") { "${it.first}: ${it.second}" }}): $result = $body"
        return insert("Create function '$name'", call, call.name, declaration)
    }

    /** `fun Recv.name(a: int): R` for `x.name(a)` with `x` a `Recv`. */
    private fun method(call: ForgeParser.MethodCallContext): ForgeFix? {
        if (call.symbol != null || call.expectedOfMissing == null && !unresolved(call)) return null
        val receiver = receiverOf(call.receiver) ?: return null
        val params = parameters(call.expression().drop(1))
        val result = resultOf(call)
        val name = call.name.text
        val declaration = "fun ${typeParams(listOf(receiver) + params.map { it.second } + result)}$receiver.$name(" +
            params.joinToString(", ") { "${it.first}: ${it.second}" } + "): $result = $body"
        return insert("Create extension function '$receiver.$name'", call, call.name, declaration)
    }

    /** `val Recv.name: R` for `x.name`, or `syn Node.name: R` if `x` is a node. */
    private fun property(access: ForgeParser.FieldAccessContext): ForgeFix? {
        if (access.symbol != null || access.expectedOfMissing == null && !unresolved(access)) return null
        val receiver = receiverOf(access.receiver) ?: return null
        val result = resultOf(access)
        val name = access.name.text
        return if (resolve.resolve(receiver) is NodeSymbol) {
            insert("Create attribute 'syn $receiver.$name'", access, access.name, "syn $receiver.$name: $result = $body")
        } else {
            insert("Create property 'val $receiver.$name'", access, access.name, "val ${typeParams(listOf(receiver, result))}$receiver.$name: $result = $body")
        }
    }

    /** Whether the analysis checked [ctx] and found nothing for it (it has a type, the error type). */
    private fun unresolved(ctx: ParserRuleContext): Boolean = ctx.typeOrNull == ErrorType

    /** The type of a receiver, if it is one a declaration can be of: not a type name (`Type.INT`), not an error. */
    private fun receiverOf(receiver: ForgeParser.ExpressionContext): Type? {
        if (receiver is ForgeParser.IdentifierContext && receiver.symbol?.kind == com.leko.forge.model.Kind.TYP) return null
        val type = receiver.typeOrNull ?: return null
        val plain = if (type is OptionalType) type.elem else type
        return plain.takeIf { known(it) }
    }

    /** The names and types of the parameters for [args]: the name of a variable passed, else `p1`, `p2`, ... */
    private fun parameters(args: List<ForgeParser.ExpressionContext>): List<Pair<String, Type>> {
        val used = mutableSetOf<String>()
        return args.mapIndexed { i, arg ->
            val variable = (arg as? ForgeParser.IdentifierContext)?.IDENT()?.text?.takeIf { !it.contains("::") && it.first().isLowerCase() }
            val name = variable?.takeIf { used.add(it) } ?: "p${i + 1}".also { used.add(it) }
            name to (arg.typeOrNull?.takeIf { known(it) } ?: IntType).let { if (it == NullType) OptionalType(IntType) else it }
        }
    }

    /** What was expected where the declaration is used; `Nothing` (the type of the TODO) if nothing was. */
    private fun resultOf(use: ParserRuleContext): Type = use.expectedOfMissing?.takeIf { known(it) } ?: NothingType

    private fun known(type: Type): Boolean = type != ErrorType && type != ArbitraryType && !contains(type, ErrorType) && !contains(type, ArbitraryType)

    private fun contains(type: Type, part: Type): Boolean = when (type) {
        part -> true
        is ListType -> contains(type.elem, part)
        is SetType -> contains(type.elem, part)
        is MapType -> contains(type.key, part) || contains(type.value, part)
        is OptionalType -> contains(type.elem, part)
        is TupleType -> type.elems.any { contains(it, part) }
        is FunctionType -> type.parameters.any { contains(it, part) } || contains(type.returnType, part)
        is ComplexType -> type.args.any { contains(it, part) }
        else -> false
    }

    /** `<T> ` for the type variables of [types], which are those of the enclosing function. */
    private fun typeParams(types: List<Type>): String {
        val vars = types.flatMap { it.typeVars() }.distinct()
        return if (vars.isEmpty()) "" else vars.joinToString(", ", "<", "> ")
    }

    /** The declaration after the one [use] is in (of the file or of a module), with its indentation. */
    private fun insert(title: String, use: ParserRuleContext, name: Token, declaration: String): ForgeFix? {
        val enclosing = generateSequence(use) { it.parent as? ParserRuleContext }.firstOrNull {
            it.parent is ForgeParser.RootContext || it.parent is ForgeParser.ModDefContext
        } ?: return null
        val end = enclosing.stop?.stopIndex?.plus(1)?.coerceIn(0, text.length) ?: return null
        val lineStart = text.lastIndexOf('\n', (enclosing.start.startIndex - 1).coerceAtLeast(0)).let { if (enclosing.start.startIndex == 0) 0 else it + 1 }
        val indent = text.substring(lineStart, enclosing.start.startIndex.coerceAtLeast(lineStart)).takeWhile { it == ' ' || it == '\t' }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return ForgeFix(title, lineEnd, "\n\n$indent$declaration", name.startIndex)
    }
}
