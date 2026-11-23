import com.leko.forge.analysis.Analyzer
import com.leko.forge.context.Context
import com.leko.forge.parse.ImportException
import com.leko.forge.parse.parseForge
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Static checks of the new declarations. */
class CheckTest {

    private fun errors(source: String): List<String> {
        val context = Context()
        Analyzer.instance(context).analyze(source.parseForge())
        return Log.instance(context).diagnostics.filter { it.severity == Severity.ERROR }.map { it.message }
    }

    private fun assertError(fragment: String, source: String) {
        val errors = errors(source)
        assertTrue(errors.any { fragment in it }, "expected an error containing '$fragment', got $errors")
    }

    private val language = """
        node Function(body: Stmt)
        node Stmt
        node Skip(): Stmt
        node While(cond: Expr, body: Stmt): Stmt
        node Expr
        node Var(name: str): Expr
        port onTrue, onFalse
        graph cfg: Function
    """

    @Test
    fun cyclesAreAllowedOnlyThroughCircularAttributes() {
        assertError("cyclic dependency", "node A(n: int)\nsyn A.x: int = this.y\nsyn A.y: int = this.x")
        assertEquals(emptyList(), errors("node A(n: int)\ncircular(0) A.x: int = this.y\nsyn A.y: int = this.x"))
        assertEquals(emptyList(), errors("node A(n: int)\ncircular(0) A.x: int = this.x"))
    }

    @Test
    fun circularAttributesAreTyped() {
        assertError("expected type 'int'", "node A(n: int)\ncircular(\"\") A.x: int = 0")
        assertError("expected type 'int'", "node A(n: int)\ncircular(0) A.x: int = \"a\"")
        assertError("defined as circular and as synthesized", "node A(n: int)\nsyn A.x: int = 0\ncircular(0) A.x: int = 0")
    }

    @Test
    fun flowsAreChecked() {
        assertEquals(emptyList(), errors(language + """
            flow cfg: Function(body) { entry(body); body -> next }
            flow cfg: Skip { entry(this); this -> next }
            flow cfg: While(cond, body) { entry(cond); onTrue(cond) -> body; onFalse(cond) -> next; body -> cond }
            flow cfg: Expr { entry(this); this -> onTrue with "true"; this -> onFalse with "false" }
        """))
        assertError("graph 'nope' not found", language + "flow nope: Skip { entry(this) }")
        assertError("a port of the node itself can not be connected", language + "flow cfg: Skip { entry(this); next -> this }")
        assertError("the port of a child can not be a target", language + "flow cfg: While(cond, body) { entry(cond); body -> onTrue(cond) }")
        assertError("can not be used in a flow", language + "flow cfg: Var(name) { entry(name) }")
        assertError("invalid flow statement", language + "flow cfg: Skip { this }")
        assertError("too many names", language + "flow cfg: Var(a, b) { entry(this) }")
        assertError("already defined", language + "flow cfg: Skip { entry(this) }\nflow cfg: Skip { entry(this) }")
        assertError("can only be used inside flows", language + "fun f(): bool = onTrue == null")
    }

    @Test
    fun graphsAreChecked() {
        assertError("can not be created directly", language + "fun f(s: Skip): Graph = Graph(s, [], [], null)")
        assertError("node 'Nope' not found", "graph g: Nope")
        assertError("not available on 'Vertex'", language + "inh depth: int = 0\nsyn Vertex.d: int = this.depth")
        assertEquals(emptyList(), errors(language + """
            fun calls(a: Function, b: Function): Graph = graphOf(a, [Edge(a, b, "calls")])
            fun firstOf(g: Graph): Vertex = g.entry
            fun isSkip(v: Vertex): bool = v is Skip
            fun asSkip(v: Vertex): Skip = v as Skip
        """))
        assertError("can not cast", language + "fun f(v: Var): Skip = v as Skip")
    }

    @Test
    fun fieldsMustNotHideAttributes() {
        assertError("same name as the inherited attribute", "node A(scope: int)\ninh scope: int = 0")
        assertError("same name as the attribute", "node A(x: int)\nsyn A.x: int = 0")
    }

    private val grammar = """
        node Expr
        node Num(v: str): Expr
        node Plus(l: Expr, r: Expr): Expr
        parser P
        rule P.expr: Expr
        rule P.expr: Num -> v:'\d+' { Num(v) }
    """

    @Test
    fun grammarsAreChecked() {
        assertEquals(emptyList(), errors(grammar + "rule P.expr: Expr @infixLeft(10) -> l:expr '\\+' r:expr { Plus(l, r) }"))
        assertError("operators are only allowed in abstract rules", grammar + "rule P.num: Expr @prefix(10) -> '-' e:num { e }")
        assertError("unknown operator", grammar + "rule P.expr: Expr @infix(10) -> l:expr '\\+' r:expr { Plus(l, r) }")
        assertError("has the shape", grammar + "rule P.expr: Expr @infixLeft(10) -> l:expr '\\+' { l }")
        assertError("has the shape", grammar + "rule P.expr: Expr @prefix(10) -> e:expr '-' { e }")
        assertError("has no alternatives", grammar + "rule P.stmt: Expr")
        assertError("can not infer parsed value", grammar + "rule P.pair: Expr -> '\\(' e:expr '\\)'")
        assertError("is not a 'Expr'", grammar + "rule P.expr: str -> 'x'")
        assertError("expected type 'Expr'", grammar + "rule P.other: Expr -> 'x'")
    }

    @Test
    fun parserOptionsAndBinaryPrimitives() {
        assertError("unknown option", "parser P fast")
        assertError("only be used in binary parsers", "parser P\nrule P.x: int -> @u1")
        assertError("only be used in binary parsers", "parser P\nrule P.x: str -> 0xCAFE")
        assertError("unknown primitive", "parser P binary\nrule P.x: int -> @u3")
        assertError("too few arguments", "parser P binary\nrule P.x: int -> @uint")
        assertEquals(emptyList(), errors("parser P binary\nrule P.x: List<int> -> n:@u1 xs:@uint(3)[n] { xs }"))
        assertEquals(emptyList(), errors("parser P skip '' keywords 'if' 'else'\nrule P.x: str -> '[a-z]+'"))
    }

    @Test
    fun constantsAreChecked() {
        assertEquals(emptyList(), errors("const a = b + 1\nconst b: int = 2\nconst s: str? = null\nfun f(): int = a"))
        assertError("expected type 'int' but got str", "const c: int = \"x\"")
        assertError("depends on itself", "const a = b\nconst b = a")
        assertError("depends on itself", "const a: int = a")
        assertError("already defined", "const d = 1\nconst d = 2")
        assertError("already defined", "fun d(): int = 1\nconst d = 2")
        assertError("can not use 'this'", "const g = this")
        assertError("cyclic dependency", "const e = f()\nfun f(): int = e")
        assertError("can not find 'x'", "fun f(x: int): int = c\nconst c = x")
    }

    @Test
    fun operatorsAreChecked() {
        val vec = "record V { x: int }\n"
        assertEquals(emptyList(), errors(vec + "op fun V.plus(o: V): V = V(this.x + o.x)\nop fun V.get(i: int): int = this.x\nfun f(a: V): int = (a + a)[0]"))
        assertError("is no operator", vec + "op fun V.add(o: V): V = o")
        assertError("takes 1 parameter", vec + "op fun V.plus(): V = this")
        assertError("returns an int", vec + "op fun V.compareTo(o: V): bool = true")
        assertError("two int?", vec + "op fun V.slice(a: int, b: int): V = this")
        assertError("needs a receiver", "op fun plus(a: int): int = a")
        assertError("can not apply '+'", vec + "fun f(a: V): V = a + a")
        // a function that is no operator does not define one
        assertError("can not apply '+'", vec + "fun V.plus(o: V): V = o\nfun f(a: V): V = a + a")
    }

    @Test
    fun collectionsAndBuiltinsAreChecked() {
        assertEquals(emptyList(), errors("fun f(): Set<int> = {1, 2} ++ {}\nfun g(): Map<str, int> = {:}\nfun h(): str = \"abc\"[1:].upper() + (1 << 2 >> 1 >>> 1).str()"))
        assertError("an empty map is {:}", "fun f(): Map<str, int> = {}")
        assertError("without spaces", "fun f(a: int): int = a < < 2")
        assertError("expected type 'int' but got str", "fun f(): List<int> = [1, 2][\"a\":]")
        assertError("not found", "fun f(): int = \"x\".nope()")
        assertError("not found", "fun f(): str = [1].join(\",\")")
        // fail fits wherever a value is expected
        assertEquals(emptyList(), errors("fun f(x: int?): int = x ?: fail(\"none\")\nfun g(b: bool): str = if b then \"x\" else fail(\"no\")\nfun h(): List<str> = fail(\"never\")"))
        assertError("expected type 'str' but got int", "fun f(): int = fail(1)")
        // functions and attributes can be of type Nothing; then they must not return
        assertEquals(emptyList(), errors(
            "node A(v: int)\nfun stop(m: str): Nothing = fail(\"stop: \" + m)\nfun again(): Nothing = stop(\"again\")\n" +
                "fun both(b: bool): Nothing = if b then stop(\"a\") else again()\nfun use(b: bool): int = if b then 1 else stop(\"no\")\n" +
                "syn A.never: Nothing = fail(\"never\")\nfun v(a: A): str = a.never"))
        assertError("expected type 'Nothing' but got int", "fun f(): Nothing = 1")
        assertError("expected type 'Nothing' but got str", "fun f(b: bool): Nothing = if b then fail(\"x\") else \"y\"")
        assertError("expected type 'Nothing' but got int", "node A(v: int)\nsyn A.never: Nothing = this.v")
        // methods of the program come before builtins
        assertEquals(emptyList(), errors("fun str.upper(): int = 1\nfun f(): int = \"a\".upper()"))
    }

    @Test
    fun genericFunctionsInferTheirTypeParameters() {
        val generic = "fun <T> first(xs: List<T>, d: T): T = match xs { x :: _ -> x  _ -> d }\nfun <T> List<T>.second(): T? = if this.size > 1 then this[1] else null\n"
        assertEquals(emptyList(), errors(generic + "fun f(): str = first([], \"y\")\nfun g(): int = [1, 2].second() ?: 0\nfun h(): List<int> = [[1]].second() ?: []"))
        assertError("expected type 'int' but got str", generic + "fun f(): int = first([\"a\"], \"b\")")
        assertError("expected type 'str' but got int", generic + "fun f(): str = first([\"a\"], 1)")
        assertError("expected type 'int?' but got str?", generic + "fun f(): int? = [\"a\"].second()")
        // a method for a specific receiver comes first
        assertEquals(emptyList(), errors(generic + "fun List<int>.second(): str = \"own\"\nfun f(): str = [1].second()\nfun g(): str? = [\"a\"].second()"))
    }

    @Test
    fun theStandardLibraryIsBundled() {
        val dir = createTempDirectory("std").toFile()
        val main = File(dir, "main.forge")
        main.writeText("import \"std\"\nfun f(): int = [1, 2].sum() + [3].max() + ([1] - [1]).size + [1].union([2]).size + 1.max(2)\nfun g(): float = [1.5].sum()")
        val context = Context()
        Analyzer.instance(context).analyze(main.parseForge())
        assertEquals(emptyList(), Log.instance(context).diagnostics.filter { it.severity == Severity.ERROR }.map { it.message })
    }

    @Test
    fun nodesShareTheirMostSpecificSuperType() {
        val nodes = "node Insn\nnode Push(v: int): Insn\nnode Label(name: str): Insn\nnode Jump(target: str): Insn\nnode Ref\nfor Push impl Ref\nfor Jump impl Ref\n"
        assertEquals(emptyList(), errors(nodes + """
            fun code(): List<Insn> = [Push(1), Label("end")] ++ [Jump("end")]
            fun more(xs: List<Insn>): List<Insn> = xs ++ [Push(2)]
            fun refs(): List<Ref> = [Push(1), Jump("x")]
            fun pick(b: bool): Insn = if b then Push(1) else Label("l")
        """))
        assertError("but got List<Insn>", nodes + "fun f(): List<Ref> = [Push(1), Label(\"x\")]")
    }

    @Test
    fun attributesOfInterfacesAreMembersOfTheirNodes() {
        assertEquals(emptyList(), errors("node I\nnode A(v: int): I\nsyn I.d: int = 1\nsyn I.e: int\nsyn A.e: int = this.d\nfun f(a: A): int = a.d + a.e"))
        assertEquals(emptyList(), errors("node I\nnode A(v: int)\nfor A impl I\nsyn I.d: int = 1\nfun f(a: A): int = a.d"))
    }

    @Test
    fun membersOfConstructedValues() {
        assertEquals(emptyList(), errors("node A(v: int)\nsyn A.d: int = this.v\nfun f(): int = A(1).d + A(2).v"))
        assertEquals(emptyList(), errors("record R { x: int }\nfun f(): int = R(1).x"))
    }

    @Test
    fun importsJoinFiles() {
        val dir = createTempDirectory("forge-import").toFile()
        try {
            File(dir, "base.forge").writeText("node Expr\nnode Num(v: int): Expr\nsyn Expr.size: int = 1")
            File(dir, "lib").mkdirs()
            File(dir, "lib/more.forge").writeText("import \"../base.forge\"\nfun two(): Num = Num(2)")
            val main = File(dir, "main.forge")
            main.writeText("import \"base.forge\"\nimport \"lib/more.forge\"\nnode Neg(e: Expr): Expr\nsyn Neg.size: int = 1 + this.e.size\nfun f(): int = Neg(two()).size")
            val context = Context()
            Analyzer.instance(context).analyze(main.parseForge())
            assertEquals(emptyList(), Log.instance(context).diagnostics.filter { it.severity == Severity.ERROR }.map { it.message })

            // a diagnostic names the file it is in
            File(dir, "bad.forge").writeText("node A(v: int)\nnode A(v: int)")
            main.writeText("import \"bad.forge\"\nfun f(): int = 1")
            val badContext = Context()
            Analyzer.instance(badContext).analyze(main.parseForge())
            assertTrue(Log.instance(badContext).diagnostics.any { it.span.toString().startsWith("bad.forge:2:") })

            main.writeText("import \"missing.forge\"")
            assertFailsWith<ImportException> { main.parseForge() }
        } finally {
            dir.deleteRecursively()
        }
    }
}
