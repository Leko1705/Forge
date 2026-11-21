import forgetest.flow.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlowTest {

    // x = 1; while (x < 10) x = x + 1; print(x)
    private val init = Assign("x", Num(1))
    private val cond = Less(Var("x"), Num(10))
    private val step = Assign("x", Add(Var("x"), Num(1)))
    private val print = Print(Var("x"))
    private val loop = While(cond, step)
    private val function = Function("f", Block(listOf(init, loop, print)))

    @Test
    fun flowsBuildTheControlFlowGraph() {
        val g = cfg.get(function)
        assertSame(function, g.root)
        assertSame(init, g.entry)
        assertEquals(listOf<Vertex>(cond), g.succ(init))
        assertEquals(listOf<Vertex>(step, print), g.succ(cond))
        assertEquals(listOf("true", "false"), g.outEdges(cond).map { it.label })
        assertEquals(listOf<Vertex>(cond), g.succ(step))
        assertEquals(listOf<Vertex>(init, step), g.pred(cond))
        val exit = g.exit("next")!!
        assertEquals("exit(next)", exit.toString())
        assertEquals(listOf(exit), g.succ(print))
        assertFalse(g.contains(loop))   // a While is not a vertex: its flow consists of its parts
        assertEquals(5, g.vertices.size)
        assertEquals(4, statements(g) + 1)
        // in the order the flows found them: the statements first, then the condition (breadth first)
        assertEquals(listOf<Vertex>(init, print, cond, step, exit), g.vertices)
        assertEquals(listOf("x = ..", "other", "expr", "x = ..", "other"), names(g.vertices))
    }

    @Test
    fun graphsAreFoundFromAnyVertex() {
        assertSame(cfg.get(function), cfg.of(step))
        assertEquals(listOf<Vertex>(cond), cfg.succ(step))
        assertEquals(listOf<Vertex>(init, step), cfg.pred(cond))
        assertSame(cfg.get(function), cfg.get(function))
        val exit = cfg.get(function).exit("next")!!
        assertEquals(listOf<Vertex>(step, exit, print, cond, init), cfg.get(function).postorder())
        assertEquals(listOf<Vertex>(init, cond, print, exit, step), cfg.get(function).reversePostorder())
    }

    @Test
    fun livenessIsAFixpointOverTheCycle() {
        assertEquals(emptyList(), init.liveIn)
        assertEquals(listOf("x"), init.liveOut)
        assertEquals(listOf("x"), cond.liveIn)
        assertEquals(listOf("x"), step.liveIn)
        assertEquals(listOf("x"), step.liveOut)
        assertEquals(listOf("x"), print.liveIn)
        assertEquals(emptyList(), print.liveOut)
    }

    @Test
    fun inheritedPortsLeaveNestedStatements() {
        // while (c) { break; print(y) }; if (t) return r; print(z)
        val brk = Break()
        val dead = Print(Var("y"))
        val c = Var("c")
        val t = Var("t")
        val ret = Return(Var("r"))
        val after = Print(Var("z"))
        val f = Function("g", Block(listOf(While(c, Block(listOf(brk, dead))), If(t, ret, null), after)))
        val g = cfg.get(f)

        assertEquals(listOf<Vertex>(t), g.succ(brk))
        assertEquals(listOf<Vertex>(c), g.succ(dead))
        assertEquals(listOf<Vertex>(ret, after), g.succ(t))
        assertEquals(listOf(g.exit("return")!!), g.succ(ret))
        assertEquals("exit(return)", g.exit("return").toString())

        assertTrue(dead.dead)
        assertFalse(after.dead)
        assertFalse(brk.dead)

        assertEquals(setOf("c", "t", "r", "z"), c.liveIn.toSet())
        assertEquals(setOf("y", "c", "t", "r", "z"), dead.liveIn.toSet())
        assertEquals(emptyList(), ret.liveOut)
    }

    @Test
    fun graphsFromEdges() {
        val f = Function("f", Block(emptyList()))
        val g = Function("g", Block(emptyList()))
        val h = Function("h", Block(emptyList()))
        val unused = Function("unused", Block(emptyList()))
        val calls = calls(f, g, h)
        assertEquals(listOf<Vertex>(f, g, h), calls.vertices)
        assertEquals(listOf<Vertex>(f, g, h), calls.reachable())
        assertEquals(listOf<Vertex>(h, g, f), calls.postorder())
        assertEquals(listOf<Vertex>(g), calls.succ(h))
        assertEquals(listOf<Vertex>(f, h), calls.pred(g))
        assertEquals(-1, calls.indexOf(unused))
        assertTrue(calls.toDot().contains("n0 -> n1 [label=\"calls\"];"))
        // an empty block passes control on
        assertEquals(listOf(cfg.get(f).exit("next")!!), listOf(cfg.get(f).entry))
    }

    @Test
    fun syntheticVertices() {
        // fork { a = 1 | print(b) }; print(a)
        val left = Assign("a", Num(1))
        val right = Print(Var("b"))
        val last = Print(Var("a"))
        val f = Function("h", Block(listOf(Fork(listOf(left, right)), last)))
        val g = cfg.get(f)
        val split = g.entry
        assertEquals("split", split.toString())
        assertEquals(listOf<Vertex>(left, right), g.succ(split))
        val join = g.succ(left).single()
        assertEquals("join", join.toString())
        assertEquals(listOf(join), g.succ(right))
        assertEquals(listOf<Vertex>(last), g.succ(join))
        // attributes of Vertex apply to synthetic vertices too
        assertEquals(listOf("a"), join.liveIn)
        assertEquals(setOf("a", "b"), split.liveIn.toSet())   // b for the right branch, a when it is taken
    }
}
