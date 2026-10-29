package examples

/** Runs the stack machine code the generated compiler produces. */
fun execute(code: List<String>): List<Int> {
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
            "halt" -> return output
            else -> error("unknown instruction ${code[pc - 1]}")
        }
    }
    return output
}
