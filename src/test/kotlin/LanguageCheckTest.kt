import com.leko.forge.analysis.Analyzer
import com.leko.forge.context.Context
import com.leko.forge.parse.parseForge
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Static checks of local bindings, functions as values, generic records and enums, tuples, traits, modules, long
 * and string interpolation. What the programs compute is tested by language.forge on every target.
 */
class LanguageCheckTest {

    private fun errors(source: String): List<String> {
        val context = Context()
        Analyzer.instance(context).analyze(source.parseForge())
        return Log.instance(context).diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }
    }

    private fun assertError(fragment: String, source: String) {
        val errors = errors(source)
        assertTrue(errors.any { fragment in it }, "expected an error containing '$fragment', got $errors")
    }

    private fun assertOk(source: String) = assertEquals(emptyList(), errors(source))

    @Test
    fun whereBindings() {
        assertOk("fun f(): int = a + b where a = 1, b: int = a * 2")
        // in any order, in the order of their dependencies
        assertOk("fun f(): int = a where a = b + 1, b = 2")
        assertOk("fun f(): int = a + b where a = 1 where b = 2")
        assertOk("fun f(t: (int, str)): str = s where (n, s) = t")
        assertError("depend on each other", "fun f(): int = a where a = b, b = a")
        assertError("already defined in this 'where'", "fun f(): int = a where a = 1, a = 2")
        assertError("expected type 'str'", "fun f(): str = a where a: str = 1")
        assertError("is not a tuple of 2 values", "fun f(): int = a where (a, b) = 1")
        // the where of an if is for all of it
        assertOk("fun f(c: bool): int = if c then x else x + 1 where x = 3")
    }

    @Test
    fun functionsAsValues() {
        assertOk("fun twice(f: (int) -> int, x: int): int = f(f(x))\nfun g(): int = twice({ x -> x + 1 }, 1)")
        assertOk("fun inc(x: int): int = x + 1\nfun g(): (int) -> int = inc")
        assertOk("record R { f: (str) -> int }\nfun g(r: R): int = r.f(\"a\")")
        assertOk("fun g(): int = ({ x: int -> x * 2 })(3)")
        assertError("can not infer the type of parameter 'x'", "fun g(): bool = { x -> x } == null")
        assertError("is not a function", "fun g(x: int): int = x(1)")
        assertError("too many arguments", "fun g(f: (int) -> int): int = f(1, 2)")
        assertError("expected type '(int) -> int'", "fun g(): (int) -> int = { x: str -> 1 }")
        assertError("functions can not be compared", "fun g(f: (int) -> int, h: (int) -> int): bool = f == h")
    }

    @Test
    fun trailingLambdas() {
        assertOk("fun f(xs: List<int>): List<str> = xs.map { it + 1 }.filter { x -> x > 1 }.map() { it.str() }")
        assertOk("fun apply(f: (int) -> int): int = f(1)\nfun g(): int = apply { it * 2 } + apply { x -> x }")
        assertOk("fun now(f: () -> int): int = f()\nfun g(): int = now { 42 }")
        assertOk("fun g(xs: List<int>): int = xs.fold(0) { acc, x -> acc + x }")
        // a block after a match is its cases, also after a name or a call
        assertOk("fun f(x: int, a: int): int = match x { a -> a  _ -> 0 }\nfun g(xs: List<int>): int = match xs.reversed() { [a] -> a  _ -> 0 }")
        assertOk("fun g(xs: List<int>): str = match xs.map { it } { [1] -> \"one\"  _ -> \"\" }")
        // flows: the blocks of `if` and `for`
        assertOk("node F(body: S)\nnode S\nnode A(): S\nport p\ngraph cfg: F\nflow cfg: F(_) { entry(this); this -> next }\n" +
            "flow cfg: A { entry(this); if bound(p) { this -> p } else { this -> next } }")
        assertError("expected type '(int, int) -> int'", "fun f(g: (int, int) -> int): int = 1\nfun h(): int = f { 3 }")
    }

    @Test
    fun genericTypes() {
        assertOk("record Pair<A, B> { a: A, b: B }\nfun f(): Pair<int, str> = Pair(1, \"x\")\nfun g(p: Pair<int, str>): str = p.b")
        assertOk("enum Option<T> { NONE, SOME(v: T) }\nfun f(): Option<int> = Option.NONE\nfun g(o: Option<int>): int = match o { Option.SOME(v) -> v  _ -> 0 }")
        assertError("takes 2 type arguments, not 1", "record Pair<A, B> { a: A, b: B }\nfun f(p: Pair<int>): int = 1")
        // what is expected binds the type arguments: the argument is reported
        assertError("expected type 'int' but got str", "record Pair<A, B> { a: A, b: B }\nfun f(): Pair<int, str> = Pair(\"a\", \"b\")")
        assertError("expected type 'int' but got str", "enum Option<T> { NONE, SOME(v: T) }\nfun g(o: Option<str>): int = match o { Option.SOME(v) -> v  _ -> 0 }")
    }

    @Test
    fun tuples() {
        assertOk("fun f(t: (int, (str, bool))): bool = t.1.1")
        assertOk("fun f(): str = match (1, \"a\") { (1, s) -> s  _ -> \"\" }")
        assertError("has no element 2", "fun f(t: (int, str)): int = t.2")
        assertError("expected type '(int, str)'", "fun f(): (int, str) = (\"a\", 1)")
    }

    @Test
    fun traitsAndBounds() {
        val trait = "trait Named { fun name(): str }\nrecord Dog { n: str }\nfun Dog.name(): str = this.n\n"
        assertOk(trait + "fun <T: Named> f(x: T): str = x.name()\nfun g(): str = f(Dog(\"a\"))")
        assertError("'int' is not a 'Named': it needs fun int.name(): str", trait + "fun <T: Named> f(x: T): str = x.name()\nfun g(): str = f(1)")
        assertError("add the bound", trait + "fun <T: Named> f(x: T): str = x.name()\nfun <U> g(x: U): str = f(x)")
        assertError("method 'T.size' not found", "fun <T> f(x: T): int = x.size()")
        assertOk("fun <T: Comparable> f(a: T, b: T): bool = a < b\nfun g(): bool = f(\"a\", \"b\") && f(1, 2)")
        assertError("is not a 'Expr'", "node Expr\nnode Num(n: int): Expr\nfun <T: Expr> f(x: T): int = 1\nfun g(): int = f(1)")
        assertError("a bound is a trait or a type", "fun <T, U: T> f(x: U): int = 1")
    }

    @Test
    fun modules() {
        assertOk("mod a { record R { x: int }\nfun make(): R = R(1) }\nfun f(): int = a::make().x")
        assertOk("mod a { record R { x: int } }\nuse a\nfun f(r: R): int = r.x")
        assertOk("mod a { record Item { x: int } }\nmod b { record Item { y: int } }\nfun f(i: a::Item, j: b::Item): int = i.x + j.y")
        assertError("can not find module 'nope'", "fun f(): int = nope::g()")
        assertError("module 'a' has no 'g'", "mod a { fun h(): int = 1 }\nfun f(): int = a::g()")
        assertError("can not find module or declaration 'nope'", "use nope")
        assertError("is ambiguous", "mod a { fun g(): int = 1 }\nmod b { fun g(): int = 2 }\nuse a\nuse b\nfun f(): int = g()")
        assertError("a list pattern is written with spaces", "fun f(xs: List<int>): int = match xs { x::rest -> x  _ -> 0 }")
    }

    @Test
    fun modulesAcrossFiles() {
        val dir = kotlin.io.path.createTempDirectory("forge").toFile()
        java.io.File(dir, "lib.forge").writeText("mod lib { record Item { x: int }\nfun make(): Item = Item(1) }\nuse lib\nfun libOwn(): int = make().x")
        fun check(main: String): List<String> {
            val file = java.io.File(dir, "main.forge").apply { writeText(main) }
            val context = Context()
            Analyzer.instance(context).analyze(file.parseForge())
            return Log.instance(context).diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }
        }
        assertEquals(emptyList(), check("import \"lib.forge\"\nfun f(): int = lib::make().x + libOwn()"))
        assertEquals(emptyList(), check("import \"lib.forge\"\nuse lib\nfun f(i: Item): int = make().x + i.x"))
        // the `use` of lib.forge is for lib.forge
        assertTrue(check("import \"lib.forge\"\nfun f(): int = make().x").any { "make" in it })
        dir.deleteRecursively()
    }

    @Test
    fun longsAndInterpolation() {
        assertOk("fun f(): long = 1 + 2L\nfun g(): long = 5\nfun h(x: long): int = x as int")
        assertError("expected type 'int' but got long", "fun f(): int = 1L")
        assertOk("record R { x: int }\nfun R.str(): str = \"r\"\nfun f(r: R): str = \"a ${'$'}{r} ${'$'}{1 + 1} ${'$'}{\"b\"}\"")
        assertError("can not be put into a string", "fun f(xs: List<int>): str = \"${'$'}{xs}\"")
        assertError("can not be put into a string", "fun f(x: int?): str = \"${'$'}{x}\"")
    }
}
