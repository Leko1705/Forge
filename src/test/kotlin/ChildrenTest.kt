import com.leko.forge.analysis.Analyzer
import com.leko.forge.context.Context
import com.leko.forge.parse.parseForge
import com.leko.forge.util.Log
import com.leko.forge.util.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `child syn` (nonterminal attributes) and forwarding (`node N(...): I by tree`). */
class ChildrenTest {

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
        node Program(stmts: List<Stmt>)
        node Decl(name: str)
        node Stmt
        node Put(name: str, value: int): Stmt
        node Block(stmts: List<Stmt>): Stmt
        syn Stmt.code: str
        syn Put.code: str = this.name
        syn Block.code: str = this.stmts.map { it.code }.join(";")
    """

    @Test
    fun childSynsAreTreesThatBecomeChildren() {
        assertEquals(
            listOf(
                "builtins builtin 0 | builtin 1 same true",
                "table a:key first | b:key second",
                "extra the extra none",
                "code zero=1 / zero=(zero+2) / incr=(incr+?x) / one=zero;one=zero",
                "size 1 1 1 2",
                "errors incr needs a number",
                "lowered zero=1 / zero=(zero+2) / incr=(?incr+?x) / one=zero",
                "lowered scope zero=1 true",
                "copied zero=1 zero=1 true 1",
            ),
            forgetest.conformance.children(),
        )
    }

    @Test
    fun forwardingNodesHaveTheFlowOfTheirTree() {
        assertEquals(
            listOf(
                "forwarded vertices 6, edges 6",
                "forwarded reachable i= i= cond stmt other stmt",
                "forwarded uses i defs i",
                "forwarded live out of first i",
                "forwarded dead false true",
                "forward is a copy true",
            ),
            forgetest.conformance.forwardedFlows(),
        )
    }

    @Test
    fun aNodeThatAlreadyHasAParentIsCopied() {
        val set = forgetest.conformance.MSet("x", forgetest.conformance.MNum(1))
        val first = forgetest.conformance.MBlock(listOf(set))
        val second = forgetest.conformance.MBlock(listOf(set))
        assertTrue(first.stmts[0] === set)
        assertTrue(second.stmts[0] !== set)
        assertEquals("x=1", second.stmts[0].code)
    }

    @Test
    fun childSynsAreValid() {
        assertEquals(emptyList(), errors(language + """
            child syn Program.builtins: List<Decl> = [Decl("int")]
            child syn Program.byName: Map<str, Decl> = {"int": Decl("int")}
            child syn Program.main: Decl? = null
            child syn Stmt.lowered: Stmt
            syn Put.lowered: Stmt = Put(this.name, this.value)
            child syn Block.lowered: Stmt = Block(this.stmts)
            inh env: List<str> = []
            inh Program.env {
                stmts[i] <- this.builtins.map { it.name }
                builtins[i] <- []
                byName[k] <- [k]
                main <- []
            }
        """))
        assertError("unexpected 'kid' before 'syn'", language + "kid syn Program.d: Decl = Decl(\"x\")")
        assertError("a child syn must be a node", language + "child syn Program.n: int = 1")
        assertError("a child syn must be a node", language + "child syn Program.n: Set<Decl> = {}")
        assertError("declare it there as a child syn", language + "syn Stmt.lowered: Stmt\nchild syn Put.lowered: Stmt = Put(\"a\", 1)\nsyn Block.lowered: Stmt = this")
        assertError("is an attribute, not a child", language + "syn Program.d: Decl = Decl(\"x\")\ninh env: int = 0\ninh Program.env { d <- 1 }")
        assertError("can not delegate to a map of children", language + "child syn Program.m: Map<str, Decl> = {:}\ninh env: int = 0\ninh Program.env { m <- 1 }")
    }

    @Test
    fun forwardingIsValid() {
        assertEquals(emptyList(), errors(language + """
            node Twice(body: Stmt): Stmt by Block([this.body, this.body])
            node Named
            node Skip(): Named
            for Skip impl Stmt by Block([])
            syn Named.label: str = "skip"
            inh env: int = 0
            inh Twice.env { forward <- 1 }
            syn Twice.size: int = this.forward.code.size
        """))
        // the forward implements the attributes the node has no equation for
        assertError("Twice", language + "node Twice(body: Stmt): Stmt")
        assertError("unexpected 'using' after 'Stmt'", language + "node Twice(body: Stmt): Stmt using Block([])")
        assertError("is an interface", language + "node Other: Stmt by Block([])")
        assertError("forwards to one", language + "node Named\nnode Twice(body: Stmt): Stmt by Block([])\nfor Twice impl Named by Block([])")
        assertError("expected type 'Stmt'", language + "node Twice(body: Stmt): Stmt by Decl(\"x\")")
        assertError("which comes from that tree", language + "node Twice(body: Stmt): Stmt by Put(this.code, 1)")
        assertError("which comes from that tree", language + "node Twice(body: Stmt): Stmt by this.forward")
        assertError("the name of the tree it forwards to", language + "node Twice(body: Stmt): Stmt by Block([])\nsyn Twice.forward: int = 1")
    }
}
