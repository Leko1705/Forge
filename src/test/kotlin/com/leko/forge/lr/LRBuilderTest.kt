package com.leko.forge.lr

import forge.runtime.ForgeLRParser
import forge.runtime.ForgeParseException
import forge.runtime.ForgeTableCodec
import forge.runtime.ForgeTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LRBuilderTest {

    /** Builds a parser whose semantic values are s-expressions of the parse tree. */
    private fun parser(grammar: LRGrammar, layout: List<String> = listOf("\\s+")): Pair<ForgeLRParser, LRTables> {
        val tables = LRBuilder(grammar).build()
        val terminals = grammar.terminals.map { ForgeTerminal(it.name, it.pattern, it.literal) }
        val parser = ForgeLRParser(tables.encode(), terminals, layout) { p, rhs ->
            val prod = grammar.productions[p]
            "(" + grammar.nonterminals[prod.lhs] + rhs.joinToString("") { " $it" } + ")"
        }
        return parser to tables
    }

    private fun expressionGrammar(): LRGrammar {
        val g = LRGrammar()
        val expr = g.nonterminal("e")
        val plus = g.terminal("+", true)
        val minus = g.terminal("-", true)
        val times = g.terminal("*", true)
        val pow = g.terminal("^", true)
        val num = g.terminal("[0-9]+", false)
        val lp = g.terminal("(", true)
        val rp = g.terminal(")", true)
        val e = LRGrammar.nt(expr)
        g.production(expr, listOf(e, plus, e), Precedence(10, Assoc.LEFT))
        g.production(expr, listOf(e, minus, e), Precedence(10, Assoc.LEFT))
        g.production(expr, listOf(e, times, e), Precedence(20, Assoc.LEFT))
        g.production(expr, listOf(e, pow, e), Precedence(30, Assoc.RIGHT))
        g.production(expr, listOf(minus, e), Precedence(25, Assoc.NONE))
        g.production(expr, listOf(num))
        g.production(expr, listOf(lp, e, rp))
        g.entries += expr
        return g
    }

    @Test
    fun codecRoundTrip() {
        val values = intArrayOf(0, 1, 31, 32, 33, 1000, 65535, 1 shl 20, Int.MAX_VALUE)
        assertEquals(values.toList(), ForgeTableCodec.decode(ForgeTableCodec.encode(values)).toList())
    }

    @Test
    fun precedenceAndAssociativity() {
        val (parser, tables) = parser(expressionGrammar())
        assertTrue(tables.conflicts.isEmpty(), tables.conflicts.joinToString("\n"))
        assertEquals("(e (e (e (e 1) + (e (e 2) * (e 3))) - (e 4)) + (e 5))", parser.parse("1 + 2 * 3 - 4 + 5", 0))
        assertEquals("(e (e 2) ^ (e (e 3) ^ (e 4)))", parser.parse("2^3^4", 0))
        assertEquals("(e (e - (e 2)) * (e 3))", parser.parse("-2 * 3", 0))
        assertEquals("(e - (e (e 2) ^ (e 3)))", parser.parse("-2 ^ 3", 0))
        assertEquals("(e ( (e (e 1) + (e 2)) ))", parser.parse(" ( 1+2 ) ", 0))
    }

    @Test
    fun unresolvedConflictsAreReportedAndShifted() {
        val g = LRGrammar()
        val expr = g.nonterminal("e")
        val plus = g.terminal("+", true)
        val num = g.terminal("[0-9]+", false)
        g.production(expr, listOf(LRGrammar.nt(expr), plus, LRGrammar.nt(expr)))
        g.production(expr, listOf(num))
        g.entries += expr
        val (parser, tables) = parser(g)
        assertEquals(1, tables.conflicts.size)
        assertTrue("shift/reduce" in tables.conflicts.first().resolution)
        // shifting makes + right associative
        assertEquals("(e (e 1) + (e (e 2) + (e 3)))", parser.parse("1+2+3", 0))
    }

    @Test
    fun nonassocRejectsChains() {
        val g = LRGrammar()
        val expr = g.nonterminal("e")
        val lt = g.terminal("<", true)
        val num = g.terminal("[0-9]+", false)
        g.production(expr, listOf(LRGrammar.nt(expr), lt, LRGrammar.nt(expr)), Precedence(1, Assoc.NONASSOC))
        g.production(expr, listOf(num))
        g.entries += expr
        val (parser, tables) = parser(g)
        assertTrue(tables.conflicts.isEmpty())
        assertEquals("(e (e 1) < (e 2))", parser.parse("1 < 2", 0))
        assertFailsWith<ForgeParseException> { parser.parse("1 < 2 < 3", 0) }
    }

    @Test
    fun contextAwareScanning() {
        // keywords are only keywords where the grammar expects them: `let let = 1` is fine
        val g = LRGrammar()
        val stmt = g.nonterminal("s")
        val let = g.terminal("let", true)
        val ident = g.terminal("[a-z]+", false)
        val eq = g.terminal("=", true)
        val num = g.terminal("[0-9]+", false)
        g.production(stmt, listOf(let, ident, eq, num))
        g.production(stmt, listOf(ident, eq, num))
        g.entries += stmt
        val (parser, tables) = parser(g)
        assertTrue(tables.conflicts.isEmpty())
        assertEquals("(s let let = 1)", parser.parse("let let = 1", 0))
        assertEquals("(s let = 1)", parser.parse("let = 1", 0))
        assertEquals("(s letter = 1)", parser.parse("letter = 1", 0))
    }

    @Test
    fun layoutAndComments() {
        val (parser, _) = parser(expressionGrammar(), listOf("\\s+", "//[^\\n]*", "/\\*(?s:.)*?\\*/"))
        assertEquals("(e (e 1) + (e 2))", parser.parse("1 // one\n + /* two */ 2 // end", 0))
    }

    @Test
    fun syntaxErrorsHavePositions() {
        val (parser, _) = parser(expressionGrammar())
        val e = assertFailsWith<ForgeParseException> { parser.parse("1 +\n  * 2", 0) }
        assertEquals(2, e.line)
        assertEquals(3, e.column)
        assertTrue("expected one of" in e.reason, e.reason)
    }

    @Test
    fun lr1GrammarThatIsNotLalr1() {
        // S → a A d | b B d | a B e | b A e ; A → c ; B → c  (classic LR(1) but not LALR(1))
        val g = LRGrammar()
        val s = g.nonterminal("S")
        val a = g.nonterminal("A")
        val b = g.nonterminal("B")
        val ta = g.terminal("a", true)
        val tb = g.terminal("b", true)
        val tc = g.terminal("c", true)
        val td = g.terminal("d", true)
        val te = g.terminal("e", true)
        val A = LRGrammar.nt(a)
        val B = LRGrammar.nt(b)
        g.production(s, listOf(ta, A, td))
        g.production(s, listOf(tb, B, td))
        g.production(s, listOf(ta, B, te))
        g.production(s, listOf(tb, A, te))
        g.production(a, listOf(tc))
        g.production(b, listOf(tc))
        g.entries += s
        val (parser, tables) = parser(g)
        assertTrue(tables.conflicts.isEmpty(), tables.conflicts.joinToString("\n"))
        assertEquals("(S a (A c) d)", parser.parse("a c d", 0))
        assertEquals("(S a (B c) e)", parser.parse("a c e", 0))
        assertEquals("(S b (B c) d)", parser.parse("b c d", 0))
        assertEquals("(S b (A c) e)", parser.parse("b c e", 0))
    }

    @Test
    fun multipleEntries() {
        val g = expressionGrammar()
        val list = g.nonterminal("list")
        val comma = g.terminal(",", true)
        g.production(list, listOf(LRGrammar.nt(0)))
        g.production(list, listOf(LRGrammar.nt(list), comma, LRGrammar.nt(0)))
        g.entries += list
        val (parser, _) = parser(g)
        assertEquals("(e (e 1) + (e 2))", parser.parse("1+2", 0))
        assertEquals("(list (list (e 1)) , (e 2))", parser.parse("1, 2", 1))
    }
}
