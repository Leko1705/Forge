package com.leko.forge.parse

import ForgeLexer
import ForgeParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ListTokenSource
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTree
import java.util.Collections
import java.util.WeakHashMap

/**
 * The parts of a string with interpolations, `"a ${x} b"`: the text between them as written (escapes not yet
 * replaced) and the expressions, which are children of the string in the tree; `null` for a plain string.
 */
val ForgeParser.StringValueContext.interpolated: List<Any>? get() = interpolations[this]

private val interpolations: MutableMap<ForgeParser.StringValueContext, List<Any>> = Collections.synchronizedMap(WeakHashMap())

/**
 * What the grammar can not express, done after parsing [root]:
 * - the expressions of strings with interpolations (`"${x}"`) are parsed, at their positions in the file;
 * - `e where a = 1 where b = 2`, which parses as `e where a = (1 where b = 2)`, becomes `e where a = 1, b = 2`.
 *
 * Returns the number of syntax errors in interpolations; [listener] receives them (by default ANTLR's, which
 * prints them, as for the rest of the file).
 */
fun postParse(root: ParserRuleContext, listener: org.antlr.v4.runtime.ANTLRErrorListener? = null): Int {
    var errors = 0
    fun walk(t: ParseTree) {
        if (t is ForgeParser.StringValueContext) errors += expand(t, listener)
        if (t is ForgeParser.TrailingLambdaContext) {
            walk(trailingLambda(t))
            return
        }
        for (i in 0 until t.childCount) walk(t.getChild(i))
        // bottom up: a where moved out of an `if` may move out of the enclosing one
        if (t is ForgeParser.WhereExprContext) flatten(t)
        if (t is ForgeParser.IfElseContext || t is ForgeParser.ListComprehensionContext) hoist(t as ParserRuleContext)
    }
    walk(root)
    return errors
}

/**
 * A `where` at the end of an `if` or a comprehension is for all of it: `if c then a else b where x = 1` and
 * `f(x) for x in xs with acc = 0 where y = 2` parse with the `where` for `b` and `0` alone.
 */
private fun hoist(node: ParserRuleContext) {
    val trailing = when (node) {
        is ForgeParser.IfElseContext -> node.alt
        is ForgeParser.ListComprehensionContext -> if (node.WITH() != null) node.accInit else node.iterable
        else -> null
    } as? ForgeParser.WhereExprContext ?: return
    val parent = node.parent as? ParserRuleContext ?: return
    replaceChild(node, trailing, trailing.expr)
    replaceChild(parent, node, trailing)
    replaceChild(trailing, trailing.expr, node)
}

/** Puts [new] where [old] is in [parent]: in its children and in the fields of its labels. */
private fun replaceChild(parent: ParserRuleContext, old: ParserRuleContext, new: ParserRuleContext) {
    val index = parent.children.indexOfFirst { it === old }
    if (index < 0) return
    parent.children[index] = new
    new.parent = parent
    var c: Class<*>? = parent.javaClass
    while (c != null && c != ParserRuleContext::class.java) {
        for (f in c.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) || !ParserRuleContext::class.java.isAssignableFrom(f.type)) continue
            f.isAccessible = true
            if (f.get(parent) === old) f.set(parent, new)
        }
        c = c.superclass
    }
}

/**
 * `f(x) { y -> ... }`, `f { ... }`: the lambda after a call becomes a lambda among its arguments, the last one. One
 * without `->` (`xs.map { it * 2 }`) is a lambda without the arrow; Attr gives it the parameter `it`, if one is expected.
 */
private fun trailingLambda(t: ForgeParser.TrailingLambdaContext): ForgeParser.LambdaContext {
    val call = t.parent as ParserRuleContext
    val lambda = ForgeParser.LambdaContext(ForgeParser.ExpressionContext(call, t.invokingState))
    lambda.start = t.start
    lambda.stop = t.stop
    for (child in t.children.orEmpty()) {
        lambda.addAnyChild(child)
        when (child) {
            is ParserRuleContext -> child.parent = lambda
            is org.antlr.v4.runtime.tree.TerminalNodeImpl -> child.parent = lambda
        }
    }
    lambda.body = t.body
    val index = call.children.indexOfFirst { it === t }
    call.children[index] = lambda
    return lambda
}

/** `e where a = (v where b = w)` written without parentheses: the bindings of the inner `where` move out. */
private fun flatten(ctx: ForgeParser.WhereExprContext) {
    while (true) {
        val last = ctx.binding().lastOrNull() ?: return
        val inner = last.value as? ForgeParser.WhereExprContext ?: return
        // the value of the binding is the body of the inner where, its bindings are bindings of this one
        last.value = inner.expr
        val index = last.children.indexOf(inner)
        last.children[index] = inner.expr
        inner.expr.parent = last
        for (b in inner.binding()) {
            b.parent = ctx
            ctx.addChild(b)
        }
    }
}

private fun expand(ctx: ForgeParser.StringValueContext, listener: org.antlr.v4.runtime.ANTLRErrorListener?): Int {
    val token = ctx.STRING().symbol
    val text = token.text
    if (!text.contains("\${")) return 0
    val parts = mutableListOf<Any>()
    var errors = 0
    var i = 1
    var literal = StringBuilder()
    val end = text.length - 1
    while (i < end) {
        val c = text[i]
        if (c == '\\' && i + 1 < end) {
            literal.append(c).append(text[i + 1])
            i += 2
            continue
        }
        if (c == '$' && text.getOrNull(i + 1) == '{') {
            val close = closingBrace(text, i + 2)
            if (literal.isNotEmpty()) parts += literal.toString()
            literal = StringBuilder()
            val (expr, n) = parseExpression(token, i + 2, close, listener)
            errors += n
            if (expr != null) {
                expr.parent = ctx
                ctx.addAnyChild(expr)
                parts += expr
            }
            i = close + 1
            continue
        }
        literal.append(c)
        i++
    }
    if (literal.isNotEmpty()) parts += literal.toString()
    interpolations[ctx] = parts
    return errors
}

/** The index of the `}` that closes an interpolation whose expression starts at [from] (the lexer ensures one). */
private fun closingBrace(text: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> if (depth == 0) return i else depth--
            '"' -> i = closingQuote(text, i + 1)
        }
        i++
    }
    return text.length - 1
}

/** The index of the `"` that ends a string inside an interpolation, which starts after [from]. */
private fun closingQuote(text: String, from: Int): Int {
    var i = from
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '"' -> return i
            '$' -> if (text.getOrNull(i + 1) == '{') i = closingBrace(text, i + 2)
        }
        i++
    }
    return text.length - 1
}

/**
 * Parses the characters [from] until [to] of the string [token] as an expression: lexed from the file itself,
 * so the tokens have the positions they have in it.
 */
private fun parseExpression(token: Token, from: Int, to: Int, listener: org.antlr.v4.runtime.ANTLRErrorListener?): Pair<ForgeParser.ExpressionContext?, Int> {
    val input = token.inputStream
    val start = token.startIndex + from
    val stop = token.startIndex + to
    val lexer = ForgeLexer(input)
    input.seek(start)
    // the position of the first character: the string may span no lines, so it is on the line of the token
    lexer.line = token.line
    lexer.charPositionInLine = token.charPositionInLine + from
    val tokens = mutableListOf<Token>()
    while (true) {
        val t = lexer.nextToken()
        if (t.type == Token.EOF || t.startIndex >= stop) break
        tokens += t
    }
    val parser = ForgeParser(CommonTokenStream(ListTokenSource(tokens, token.tokenSource?.sourceName)))
    var errors = 0
    if (listener != null) {
        parser.removeErrorListeners()
        parser.addErrorListener(listener)
    }
    parser.addErrorListener(object : BaseErrorListener() {
        override fun syntaxError(r: Recognizer<*, *>?, o: Any?, line: Int, col: Int, msg: String?, e: RecognitionException?) { errors++ }
    })
    if (tokens.isEmpty()) {
        parser.notifyErrorListeners(token, "empty interpolation \${}", null)
        return null to errors
    }
    val expr = parser.expression()
    if (parser.currentToken.type != Token.EOF) {
        parser.notifyErrorListeners(parser.currentToken, "unexpected '${parser.currentToken.text}' in an interpolation", null)
    }
    return expr to errors
}
