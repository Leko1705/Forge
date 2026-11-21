import com.leko.forge.analysis.Analyzer
import com.leko.forge.context.Context
import com.leko.forge.parse.parseForge
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
