package com.leko.forge.lr

import forge.runtime.ForgeTableCodec
import java.util.ArrayDeque
import java.util.BitSet

/** A parse conflict, reported as a warning together with the way it was resolved. */
class Conflict(
    val state: Int,
    val terminal: String,
    /** Shortest sequence of grammar symbols that leads into the conflicting state. */
    val prefix: List<String>,
    val items: List<String>,
    val resolution: String,
    val productions: List<Production>,
) {
    override fun toString(): String = buildString {
        append("conflict on ").append(terminal)
        append(" after '").append(prefix.joinToString(" ")).append("': ")
        append(items.joinToString("  |  "))
        append(" -- ").append(resolution)
    }
}

class LRTables(
    val terminalCount: Int,
    val nonterminalCount: Int,
    val productionLhs: IntArray,
    val productionLength: IntArray,
    val entryStates: IntArray,
    /** per state: sorted pairs (terminal, action) */
    val actions: List<List<Pair<Int, Int>>>,
    /** per state: pairs (nonterminal, target state) */
    val gotos: List<List<Pair<Int, Int>>>,
    val conflicts: List<Conflict>,
) {
    val stateCount: Int get() = actions.size

    fun encode(): String {
        val data = ArrayList<Int>()
        data += listOf(1, terminalCount, nonterminalCount, stateCount, productionLhs.size, entryStates.size)
        for (p in productionLhs.indices) {
            data += productionLhs[p]
            data += productionLength[p]
        }
        entryStates.forEach { data += it }
        for (s in 0 until stateCount) {
            data += actions[s].size
            for ((t, a) in actions[s]) {
                data += t
                data += a
            }
            data += gotos[s].size
            for ((n, target) in gotos[s]) {
                data += n
                data += target
            }
        }
        return ForgeTableCodec.encode(data.toIntArray())
    }

    companion object {
        fun shift(state: Int): Int = (state shl 2) or 1
        fun reduce(production: Int): Int = (production shl 2) or 2
        const val ACCEPT: Int = 3
    }
}

/**
 * Builds LR(1) parse tables.
 *
 * The automaton is the canonical LR(1) automaton in which states with equal
 * cores are merged whenever they are *weakly compatible* (Pager, "A practical
 * general method for constructing LR(k) parsers", 1977). This gives the
 * recognition power of canonical LR(1) with automata about as small as LALR(1).
 * Precise lookaheads matter twice here: fewer conflicts, and a smaller set of
 * terminals the context-aware scanner has to consider in every state.
 *
 * Every entry nonterminal `R` gets an augmented production `R' → R` and its own
 * start state, so a single table serves all entry points.
 *
 * Conflicts are resolved by production precedence: a shift/reduce conflict
 * compares the precedence of the production to reduce with the precedence of
 * the productions the terminal would be shifted into; equal levels are decided
 * by the associativity of the production to reduce. Remaining conflicts are
 * resolved in favour of shifting resp. the earliest production and reported.
 */
class LRBuilder(private val grammar: LRGrammar) {

    private val T = grammar.terminals.size
    private val userProductions = grammar.productions.size
    private val N = grammar.nonterminals.size + grammar.entries.size

    /** rhs of every production (user productions followed by augmented ones) in the unified symbol space */
    private val rhs: Array<IntArray>
    private val lhs: IntArray
    private val prodsOf: Array<IntArray>

    private val itemBase: IntArray
    private val itemProd: IntArray
    private val itemDot: IntArray

    private val nullable = BooleanArray(N)
    private val first = Array(N) { BitSet(T) }

    /** FIRST of the symbols after the symbol behind the dot of an item, and whether those are nullable */
    private val firstAfter: Array<BitSet>
    private val nullableAfter: BooleanArray

    init {
        val allRhs = ArrayList<IntArray>()
        val allLhs = ArrayList<Int>()
        for (p in grammar.productions) {
            allLhs += T + p.lhs
            allRhs += IntArray(p.rhs.size) { unify(p.rhs[it]) }
        }
        for ((i, entry) in grammar.entries.withIndex()) {
            allLhs += T + grammar.nonterminals.size + i
            allRhs += intArrayOf(T + entry)
        }
        rhs = allRhs.toTypedArray()
        lhs = allLhs.toIntArray()
        prodsOf = Array(N) { n -> lhs.indices.filter { lhs[it] == T + n }.toIntArray() }

        itemBase = IntArray(rhs.size)
        var count = 0
        for (p in rhs.indices) {
            itemBase[p] = count
            count += rhs[p].size + 1
        }
        itemProd = IntArray(count)
        itemDot = IntArray(count)
        for (p in rhs.indices) for (d in 0..rhs[p].size) {
            itemProd[itemBase[p] + d] = p
            itemDot[itemBase[p] + d] = d
        }

        computeFirstSets()

        firstAfter = Array(count) { BitSet(T) }
        nullableAfter = BooleanArray(count)
        for (item in 0 until count) {
            val p = itemProd[item]
            var allNullable = true
            for (k in itemDot[item] + 1 until rhs[p].size) {
                val sym = rhs[p][k]
                if (sym < T) {
                    firstAfter[item].set(sym)
                    allNullable = false
                    break
                }
                firstAfter[item].or(first[sym - T])
                if (!nullable[sym - T]) {
                    allNullable = false
                    break
                }
            }
            nullableAfter[item] = allNullable
        }
    }

    private fun unify(sym: Int): Int = if (sym >= 0) sym else T + (-sym - 1)

    private fun computeFirstSets() {
        var changed = true
        while (changed) {
            changed = false
            for (p in rhs.indices) {
                val a = lhs[p] - T
                var allNullable = true
                for (sym in rhs[p]) {
                    if (sym < T) {
                        if (!first[a].get(sym)) {
                            first[a].set(sym)
                            changed = true
                        }
                        allNullable = false
                        break
                    }
                    val before = first[a].cardinality()
                    first[a].or(first[sym - T])
                    if (first[a].cardinality() != before) changed = true
                    if (!nullable[sym - T]) {
                        allNullable = false
                        break
                    }
                }
                if (allNullable && !nullable[a]) {
                    nullable[a] = true
                    changed = true
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // automaton
    // ------------------------------------------------------------------

    private class State(val core: IntArray, val lookaheads: Array<BitSet>) {
        var id = -1
        var queued = false
        val transitions = HashMap<Int, State>()
        var closure: LinkedHashMap<Int, BitSet> = LinkedHashMap()
    }

    private val statesByCore = HashMap<List<Int>, MutableList<State>>()
    private val worklist = ArrayDeque<State>()

    private fun closure(state: State): LinkedHashMap<Int, BitSet> {
        val items = LinkedHashMap<Int, BitSet>()
        val queue = ArrayDeque<Int>()
        val queued = HashSet<Int>()
        for (i in state.core.indices) {
            items[state.core[i]] = state.lookaheads[i].clone() as BitSet
            queue.add(state.core[i])
            queued.add(state.core[i])
        }
        while (queue.isNotEmpty()) {
            val item = queue.poll()
            queued.remove(item)
            val p = itemProd[item]
            val d = itemDot[item]
            if (d >= rhs[p].size) continue
            val next = rhs[p][d]
            if (next < T) continue
            val la = firstAfter[item].clone() as BitSet
            if (nullableAfter[item]) la.or(items[item]!!)
            for (q in prodsOf[next - T]) {
                val target = itemBase[q]
                val existing = items[target]
                if (existing == null) {
                    items[target] = la.clone() as BitSet
                    if (queued.add(target)) queue.add(target)
                } else {
                    val before = existing.cardinality()
                    existing.or(la)
                    if (existing.cardinality() != before && queued.add(target)) queue.add(target)
                }
            }
        }
        return items
    }

    private fun enqueue(state: State) {
        if (!state.queued) {
            state.queued = true
            worklist.add(state)
        }
    }

    private fun weaklyCompatible(a: Array<BitSet>, b: Array<BitSet>): Boolean {
        var subset = true
        for (i in a.indices) {
            val extra = b[i].clone() as BitSet
            extra.andNot(a[i])
            if (!extra.isEmpty) {
                subset = false
                break
            }
        }
        if (subset) return true
        for (i in a.indices) for (j in i + 1 until a.size) {
            val ok = (!a[i].intersects(b[j]) && !a[j].intersects(b[i])) || a[i].intersects(a[j]) || b[i].intersects(b[j])
            if (!ok) return false
        }
        return true
    }

    private fun findOrCreate(core: IntArray, lookaheads: Array<BitSet>): State {
        val candidates = statesByCore.getOrPut(core.toList()) { mutableListOf() }
        for (candidate in candidates) {
            if (weaklyCompatible(candidate.lookaheads, lookaheads)) {
                var grew = false
                for (i in lookaheads.indices) {
                    val before = candidate.lookaheads[i].cardinality()
                    candidate.lookaheads[i].or(lookaheads[i])
                    if (candidate.lookaheads[i].cardinality() != before) grew = true
                }
                if (grew) enqueue(candidate)
                return candidate
            }
        }
        val state = State(core, Array(lookaheads.size) { lookaheads[it].clone() as BitSet })
        candidates += state
        enqueue(state)
        return state
    }

    private fun process(state: State) {
        val items = closure(state)
        state.closure = items
        val successors = sortedMapOf<Int, MutableList<Int>>()
        for (item in items.keys) {
            val p = itemProd[item]
            val d = itemDot[item]
            if (d < rhs[p].size) successors.getOrPut(rhs[p][d]) { mutableListOf() } += item
        }
        state.transitions.clear()
        for ((sym, from) in successors) {
            val advanced = from.map { it + 1 }.sorted()
            val core = advanced.toIntArray()
            val las = Array(core.size) { items[core[it] - 1]!! }
            state.transitions[sym] = findOrCreate(core, las)
        }
    }

    fun build(): LRTables {
        val eof = BitSet(T).also { it.set(0) }
        val starts = grammar.entries.indices.map { i ->
            val p = userProductions + i
            findOrCreate(intArrayOf(itemBase[p]), arrayOf(eof))
        }
        while (worklist.isNotEmpty()) {
            val state = worklist.poll()
            state.queued = false
            process(state)
        }

        // number the reachable states breadth first, remembering how each state was reached
        val ordered = ArrayList<State>()
        val parent = HashMap<State, Pair<State, Int>>()
        val queue = ArrayDeque<State>()
        for (s in starts) if (s.id < 0) {
            s.id = ordered.size
            ordered += s
            queue.add(s)
        }
        while (queue.isNotEmpty()) {
            val s = queue.poll()
            for ((sym, target) in s.transitions.toSortedMap()) {
                if (target.id < 0) {
                    target.id = ordered.size
                    ordered += target
                    parent[target] = s to sym
                    queue.add(target)
                }
            }
        }

        val conflicts = mutableListOf<Conflict>()
        val actions = ordered.map { s -> buildActions(s, conflicts) { prefixOf(s, parent) } }
        val gotos = ordered.map { s ->
            s.transitions.filterKeys { it >= T }.map { (sym, target) -> (sym - T) to target.id }.sortedBy { it.first }
        }
        return LRTables(
            terminalCount = T,
            nonterminalCount = N,
            productionLhs = IntArray(rhs.size) { lhs[it] - T },
            productionLength = IntArray(rhs.size) { rhs[it].size },
            entryStates = starts.map { it.id }.toIntArray(),
            actions = actions,
            gotos = gotos,
            conflicts = conflicts,
        )
    }

    private fun prefixOf(state: State, parent: Map<State, Pair<State, Int>>): List<String> {
        val symbols = ArrayList<String>()
        var s = state
        while (true) {
            val (from, sym) = parent[s] ?: break
            symbols += symbolName(sym)
            s = from
        }
        return symbols.reversed()
    }

    // ------------------------------------------------------------------
    // actions & conflict resolution
    // ------------------------------------------------------------------

    private fun symbolName(sym: Int): String =
        if (sym < T) grammar.terminals[sym].name
        else if (sym - T < grammar.nonterminals.size) grammar.nonterminals[sym - T]
        else grammar.nonterminals[grammar.entries[sym - T - grammar.nonterminals.size]] + "'"

    private fun describeItem(item: Int): String = buildString {
        val p = itemProd[item]
        append(symbolName(lhs[p])).append(" →")
        for (k in rhs[p].indices) {
            if (k == itemDot[item]) append(" •")
            append(' ').append(symbolName(rhs[p][k]))
        }
        if (itemDot[item] == rhs[p].size) append(" •")
    }

    private fun precedenceOf(p: Int): Precedence? = if (p < userProductions) grammar.productions[p].precedence else null

    /**
     * Precedence of shifting [terminal] in a state: the highest precedence of the
     * productions the terminal is shifted into. Items without precedence that
     * stem from the closure inherit it from the items that introduced them, so
     * that e.g. an operator written as a group `('+' | '-')` behaves like the
     * operators written inline.
     */
    private fun shiftPrecedence(items: Map<Int, BitSet>, terminal: Int): Int? {
        val visiting = HashSet<Int>()
        fun ofItem(item: Int): Int? {
            val p = itemProd[item]
            precedenceOf(p)?.let { return it.level }
            if (itemDot[item] != 0 || !visiting.add(lhs[p])) return null
            var best: Int? = null
            for (other in items.keys) {
                val q = itemProd[other]
                val d = itemDot[other]
                if (d < rhs[q].size && rhs[q][d] == lhs[p]) {
                    val level = ofItem(other) ?: continue
                    if (best == null || level > best) best = level
                }
            }
            return best
        }
        var best: Int? = null
        for (item in items.keys) {
            val p = itemProd[item]
            val d = itemDot[item]
            if (d < rhs[p].size && rhs[p][d] == terminal) {
                val level = ofItem(item) ?: continue
                if (best == null || level > best) best = level
            }
        }
        return best
    }

    private fun buildActions(state: State, conflicts: MutableList<Conflict>, prefix: () -> List<String>): List<Pair<Int, Int>> {
        val items = state.closure
        val shifts = HashMap<Int, Int>()
        val reduces = HashMap<Int, MutableList<Int>>()
        var accept = false
        for ((item, la) in items) {
            val p = itemProd[item]
            val d = itemDot[item]
            if (d < rhs[p].size) {
                val sym = rhs[p][d]
                if (sym < T) shifts[sym] = state.transitions[sym]!!.id
            } else if (p >= userProductions) {
                if (la.get(0)) accept = true
            } else {
                var t = la.nextSetBit(0)
                while (t >= 0) {
                    reduces.getOrPut(t) { mutableListOf() } += p
                    t = la.nextSetBit(t + 1)
                }
            }
        }

        val result = sortedMapOf<Int, Int>()
        for ((t, target) in shifts) result[t] = LRTables.shift(target)
        if (accept) result[0] = LRTables.ACCEPT

        for ((t, candidates) in reduces) {
            val involvedItems = {
                items.keys.filter { item ->
                    val p = itemProd[item]
                    val d = itemDot[item]
                    (d == rhs[p].size && p in candidates) || (d < rhs[p].size && rhs[p][d] == t)
                }.map { describeItem(it) }
            }
            candidates.sort()
            val reduce = candidates.first()
            if (candidates.size > 1 || (t == 0 && accept)) {
                conflicts += Conflict(
                    state.id, grammar.terminals[t].name, prefix(), involvedItems(),
                    "reduce/reduce conflict, resolved by reducing the production declared first: " +
                            grammar.describe(grammar.productions[reduce]),
                    candidates.map { grammar.productions[it] },
                )
                if (t == 0 && accept) continue
            }
            if (t !in shifts) {
                result[t] = LRTables.reduce(reduce)
                continue
            }
            val reducePrec = precedenceOf(reduce)
            val shiftPrec = shiftPrecedence(items, t)
            val decision: Boolean? = when {
                reducePrec == null || shiftPrec == null -> null
                reducePrec.level > shiftPrec -> true
                reducePrec.level < shiftPrec -> false
                reducePrec.assoc == Assoc.LEFT -> true
                reducePrec.assoc == Assoc.RIGHT -> false
                reducePrec.assoc == Assoc.NONASSOC -> {
                    result.remove(t)
                    continue
                }
                else -> null
            }
            when (decision) {
                true -> result[t] = LRTables.reduce(reduce)
                false -> {}
                null -> conflicts += Conflict(
                    state.id, grammar.terminals[t].name, prefix(), involvedItems(),
                    "shift/reduce conflict, resolved by shifting; annotate the rules with @left, @right, @nonassoc or @prec to decide",
                    listOf(grammar.productions[reduce]),
                )
            }
        }
        return result.map { it.key to it.value }
    }
}
