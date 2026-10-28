package examples

import examples.whilelang.WhileExt
import examples.whilelang.WhileLang
import examples.whilelang.compile
import forge.runtime.ForgeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs the stack machine code the generated compiler produces. */
private fun execute(code: List<String>): List<Int> {
    val labels = code.withIndex().filter { it.value.startsWith("label ") }.associate { it.value.removePrefix("label ") to it.index }
    val stack = ArrayDeque<Int>()
    val vars = mutableMapOf<String, Int>()
    val output = mutableListOf<Int>()
    var pc = 0
    var steps = 0
    fun bin(f: (Int, Int) -> Int) { val r = stack.removeLast(); val l = stack.removeLast(); stack.addLast(f(l, r)) }
    fun b(x: Boolean) = if (x) 1 else 0
    while (pc < code.size) {
        check(++steps < 1_000_000) { "does not terminate" }
        val parts = code[pc++].split(" ")
        when (parts[0]) {
            "push" -> stack.addLast(parts[1].toInt())
            "load" -> stack.addLast(vars.getValue(parts[1]))
            "store" -> vars[parts[1]] = stack.removeLast()
            "print" -> output += stack.removeLast()
            "add" -> bin { l, r -> l + r }
            "sub" -> bin { l, r -> l - r }
            "mul" -> bin { l, r -> l * r }
            "div" -> bin { l, r -> l / r }
            "mod" -> bin { l, r -> l % r }
            "lt" -> bin { l, r -> b(l < r) }
            "le" -> bin { l, r -> b(l <= r) }
            "gt" -> bin { l, r -> b(l > r) }
            "ge" -> bin { l, r -> b(l >= r) }
            "eq" -> bin { l, r -> b(l == r) }
            "ne" -> bin { l, r -> b(l != r) }
            "and" -> bin { l, r -> b(l != 0 && r != 0) }
            "or" -> bin { l, r -> b(l != 0 || r != 0) }
            "not" -> stack.addLast(b(stack.removeLast() == 0))
            "jmp" -> pc = labels.getValue(parts[1])
            "jz" -> if (stack.removeLast() == 0) pc = labels.getValue(parts[1])
            "label" -> {}
            else -> error("unknown instruction ${code[pc - 1]}")
        }
    }
    return output
}

class WhileLangTest {

    private val factorial = """
        // factorial and fibonacci
        n := 6; f := 1;
        while n > 1 do f := f * n; n := n - 1; end
        print f;
        a := 0; b := 1; i := 0;
        while i < 10 do
            if i == 9 || i == 4 then print a; end
            t := a + b; a := b; b := t; i := i + 1;
        end
    """

    @Test
    fun baseLanguage() {
        val result = WhileLang.program(factorial).compile()
        assertEquals(emptyList(), result.errors)
        assertEquals(listOf(720, 3, 34), execute(result.code))
    }

    @Test
    fun nestedControlFlowGetsDistinctLabels() {
        val program = """
            i := 0; s := 0;
            while i < 5 do
                if i % 2 == 0 then
                    j := 0;
                    while j < i do s := s + 1; j := j + 1; end
                else
                    if !(i == 3) then s := s + 100; else s := s + 1000; end
                end
                i := i + 1;
            end
            print s;
        """
        val result = WhileExt.program(program).compile()
        val labels = result.code.filter { it.startsWith("label") }
        assertEquals(labels.size, labels.toSet().size, "labels must be unique: $labels")
        assertEquals(listOf(0 + 2 + 4 + 100 + 1000), execute(result.code))
    }

    @Test
    fun extendedLanguage() {
        val program = """
            for k := 1 to 5 do print k * k; end
            x := 48; y := 18;
            repeat t := x % y; x := y; y := t; until y == 0;
            print x;
        """
        val result = WhileExt.program(program).compile()
        assertEquals(emptyList(), result.errors)
        assertEquals(listOf(1, 4, 9, 16, 25, 6), execute(result.code))
        // programs of the base language are programs of the extension
        assertEquals(listOf(720, 3, 34), execute(WhileExt.program(factorial).compile().code))
    }

    @Test
    fun baseParserDoesNotKnowTheExtension() {
        assertFailsWith<ForgeParseException> { WhileLang.program("repeat x := 1; until x == 1;") }
        assertFailsWith<ForgeParseException> { WhileLang.program("x := 5 % 2;") }
    }

    @Test
    fun errorsCarrySourcePositions() {
        val result = WhileLang.program("x := 1;\nprint x + y;\nwhile z do end").compile()
        assertEquals(listOf("2:11: variable 'y' is never assigned", "3:7: variable 'z' is never assigned"), result.errors)
        assertTrue(result.code.isEmpty())
    }

    @Test
    fun comparisonsAreNonAssociative() {
        assertFailsWith<ForgeParseException> { WhileLang.program("x := 1 < 2 < 3;") }
    }
}
