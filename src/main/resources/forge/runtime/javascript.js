// ==== Forge runtime ============================================================================
// Attribute caches, fixpoints of circular attributes, graphs, flows and the parser engine.
// Plain ECMAScript 2020: runs in browsers and in Node.js. Internal names start with `$`.

/** An error while evaluating: a missing value, no matching case, an attribute that depends on itself. */
class ForgeError extends Error {
    constructor(message) {
        super(message);
        this.name = "ForgeError";
    }
}

function $panic(message) {
    throw new ForgeError(message);
}

const $MISSING = Symbol("missing");

function $noMatch(subject) {
    throw new ForgeError("no match for " + $show(subject));
}

/** Defines a property that may shadow a builtin one (`name`, `length` of functions). */
function $def(target, name, value) {
    Object.defineProperty(target, name, { value: value, writable: true, configurable: true, enumerable: true });
}

/** Makes an attribute readable like a field: `node.type`. */
function $getter(proto, name, fn) {
    Object.defineProperty(proto, name, { get() { return fn(this); }, configurable: true, enumerable: false });
}

/** Unique ids of objects, for keys made of several objects. */
const $ids = new WeakMap();
let $nextId = 0;
function $oid(x) {
    let id = $ids.get(x);
    if (id === undefined) {
        id = ++$nextId;
        $ids.set(x, id);
    }
    return id;
}

// ---- values -------------------------------------------------------------------------------------

function $div(a, b) {
    if (b === 0) throw new ForgeError("/ by zero");
    return (a / b) | 0;
}

function $mod(a, b) {
    if (b === 0) throw new ForgeError("/ by zero");
    return (a % b) | 0;
}

function $f2i(v) {
    if (v !== v) return 0;
    if (v >= 2147483647) return 2147483647;
    if (v <= -2147483648) return -2147483648;
    return Math.trunc(v) | 0;
}

// ---- long: a BigInt of 64 bits ------------------------------------------------------------------

function $f2l(v) {
    if (v !== v) return 0n;
    if (v >= 9223372036854775807) return 9223372036854775807n;
    if (v <= -9223372036854775808) return -9223372036854775808n;
    return BigInt(Math.trunc(v));
}

function $ldiv(a, b) {
    if (b === 0n) throw new ForgeError("/ by zero");
    return BigInt.asIntN(64, a / b);
}

function $lmod(a, b) {
    if (b === 0n) throw new ForgeError("/ by zero");
    return a % b;
}

function $toLong(s) {
    if (!$INT.test(s)) throw new ForgeError("not a long: \"" + s + "\"");
    const v = BigInt(s);
    if (v !== BigInt.asIntN(64, v)) throw new ForgeError("not a long: \"" + s + "\"");
    return v;
}

function $nn(v) {
    if (v == null) throw new ForgeError("null value where a value is required (!!)");
    return v;
}

function $fold(items, acc, body) {
    for (const x of items) acc = body(acc, x);
    return acc;
}

function $get(xs, i) {
    const k = i < 0 ? i + xs.length : i;
    if (k < 0 || k >= xs.length) throw new ForgeError("index " + i + " out of bounds for length " + xs.length);
    return xs[k];
}

function $bound(b, size, otherwise) {
    const k = b == null ? otherwise : b < 0 ? b + size : b;
    return k < 0 ? 0 : k > size ? size : k;
}

/** Elements (characters) lo to hi - 1 of a list (str); a bound may be null, below 0 counts from the end. */
function $slice(xs, lo, hi) {
    const a = $bound(lo, xs.length, 0);
    const b = $bound(hi, xs.length, xs.length);
    return a >= b ? xs.slice(0, 0) : xs.slice(a, b);
}

function $range(a, b) {
    const result = [];
    for (let i = a; i < b; i++) result.push(i);
    return result;
}

function $indexOf(xs, x) {
    for (let i = 0; i < xs.length; i++) if ($eq(xs[i], x)) return i;
    return -1;
}

function $inList(xs, x) {
    return $indexOf(xs, x) >= 0;
}

const $INT = /^[+-]?[0-9]+$/;
const $FLOAT = /^[+-]?([0-9]+[.]?[0-9]*|[.][0-9]+)([eE][+-]?[0-9]+)?$/;

function $toInt(s) {
    if (!$INT.test(s)) throw new ForgeError("not an int: \"" + s + "\"");
    const v = Number(s);
    if (v < -2147483648 || v > 2147483647) throw new ForgeError("not an int: \"" + s + "\"");
    return v | 0;
}

function $toFloat(s) {
    if (!$FLOAT.test(s)) throw new ForgeError("not a float: \"" + s + "\"");
    return Math.fround(Number(s));
}

function $charAt(s, i) {
    return $get(s, i);
}

function $code(s, i) {
    return $get(s, i).charCodeAt(0);
}

function $split(s, separator) {
    if (separator.length === 0) throw new ForgeError("the separator of split is empty");
    return s.split(separator);
}

function $replace(s, old, replacement) {
    if (old.length === 0) throw new ForgeError("the text to replace is empty");
    return s.split(old).join(replacement);
}

const $encoder = new TextEncoder();
function $utf8(s) {
    return Array.from($encoder.encode(s));
}

function $unescape(s) {
    let out = "";
    for (let i = 0; i < s.length; i++) {
        const c = s[i];
        if (c === "\\" && i + 1 < s.length) {
            i++;
            const d = s[i];
            out += d === "n" ? "\n" : d === "t" ? "\t" : d === "r" ? "\r" : d === "0" ? "\0" : d;
        } else {
            out += c;
        }
    }
    return out;
}

function $sum(xs) {
    let s = 0;
    for (const x of xs) s += x;
    return s;
}

// ---- equality and keys --------------------------------------------------------------------------

/** Equality of values: lists, sets, maps, records and enum items by value; nodes and vertices by identity. */
function $eq(a, b) {
    if (a === b) return true;
    if (a === null || b === null || typeof a !== "object" || typeof b !== "object") return false;
    if (Array.isArray(a)) {
        if (!Array.isArray(b) || a.length !== b.length) return false;
        for (let i = 0; i < a.length; i++) if (!$eq(a[i], b[i])) return false;
        return true;
    }
    if (a instanceof $Record) {
        if (a.constructor !== b.constructor) return false;
        for (const f of a.$fields) if (!$eq(a[f], b[f])) return false;
        return true;
    }
    if (a instanceof $Set) {
        if (!(b instanceof $Set) || a.size !== b.size) return false;
        for (const k of a.m.keys()) if (!b.m.has(k)) return false;
        return true;
    }
    if (a instanceof $Map) {
        if (!(b instanceof $Map) || a.size !== b.size) return false;
        for (const [k, e] of a.m) {
            const o = b.m.get(k);
            if (o === undefined || !$eq(e[1], o[1])) return false;
        }
        return true;
    }
    return false;
}

/** The key of a value in a map or set: values that are equal have the same key. */
function $key(v) {
    if (v === null || typeof v !== "object") return v;
    if (Array.isArray(v) || v instanceof $Record || v instanceof $Set || v instanceof $Map) return "\u0000" + $skey(v);
    return v;
}

function $skey(v) {
    if (v === null) return "n";
    switch (typeof v) {
        case "number": return "i" + v;
        case "bigint": return "l" + v;
        case "string": return JSON.stringify(v);
        case "boolean": return v ? "T" : "F";
    }
    if (Array.isArray(v)) return "[" + v.map($skey).join(",") + "]";
    if (v instanceof $Record) return "R" + $oid(v.constructor) + "(" + v.$fields.map(f => $skey(v[f])).join(",") + ")";
    if (v instanceof $Set) return "{" + Array.from(v.m.values(), $skey).sort().join(",") + "}";
    if (v instanceof $Map) return "<" + Array.from(v.m.values(), e => $skey(e[0]) + ":" + $skey(e[1])).sort().join(",") + ">";
    return "o" + $oid(v);
}

/** A map (immutable): entries in the order they were added, keys compared by value. */
class $Map {
    constructor(m) {
        this.m = m || new Map();
    }
    get size() { return this.m.size; }
    at(k) {
        const e = this.m.get($key(k));
        if (e === undefined) throw new ForgeError("key " + $show(k) + " is missing in the map");
        return e[1];
    }
    has(k) { return this.m.has($key(k)); }
    put(k, v) {
        const m = new Map(this.m);
        m.set($key(k), [k, v]);
        return new $Map(m);
    }
    merge(other) {
        const m = new Map(this.m);
        for (const [k, e] of other.m) m.set(k, e);
        return new $Map(m);
    }
    remove(k) {
        const m = new Map(this.m);
        m.delete($key(k));
        return new $Map(m);
    }
    keys() { return Array.from(this.m.values(), e => e[0]); }
    values() { return Array.from(this.m.values(), e => e[1]); }
    toString() { return $show(this); }
}

function $map(entries) {
    const m = new Map();
    for (const [k, v] of entries) m.set($key(k), [k, v]);
    return new $Map(m);
}

/** A set (immutable): no element twice, in the order they were added. */
class $Set {
    constructor(m) {
        this.m = m || new Map();
    }
    get size() { return this.m.size; }
    [Symbol.iterator]() { return this.m.values(); }
    has(x) { return this.m.has($key(x)); }
    union(other) {
        const m = new Map(this.m);
        for (const [k, x] of other.m) if (!m.has(k)) m.set(k, x);
        return new $Set(m);
    }
    remove(x) {
        const m = new Map(this.m);
        m.delete($key(x));
        return new $Set(m);
    }
    toList() { return Array.from(this.m.values()); }
    toString() { return $show(this); }
}

function $setOf(elements) {
    const m = new Map();
    for (const x of elements) {
        const k = $key(x);
        if (!m.has(k)) m.set(k, x);
    }
    return new $Set(m);
}

// ---- showing values -----------------------------------------------------------------------------

const $f32buffer = new Float32Array(1);

/** Like Kotlin's Float.toString: the shortest decimal that reads back as the same float. */
function $showFloat(v) {
    if (v !== v) return "NaN";
    if (v === Infinity) return "Infinity";
    if (v === -Infinity) return "-Infinity";
    if (v === 0) return Object.is(v, -0) ? "-0.0" : "0.0";
    let s = "";
    for (let digits = 1; digits <= 9; digits++) {
        s = v.toExponential(digits - 1);
        if (Math.fround(Number(s)) === v) break;
    }
    const e = s.indexOf("e");
    let mantissa = s.substring(0, e);
    const exponent = Number(s.substring(e + 1));
    const negative = mantissa.startsWith("-");
    mantissa = mantissa.replace("-", "").replace(".", "");
    let text;
    if (exponent >= -3 && exponent < 7) {
        let whole, fraction;
        if (exponent >= 0) {
            whole = mantissa.substring(0, exponent + 1).padEnd(exponent + 1, "0");
            fraction = mantissa.substring(exponent + 1) || "0";
        } else {
            whole = "0";
            fraction = "0".repeat(-exponent - 1) + mantissa;
        }
        text = whole + "." + fraction;
    } else {
        text = mantissa[0] + "." + (mantissa.substring(1) || "0") + "E" + exponent;
    }
    return (negative ? "-" : "") + text;
}

/** A value as Kotlin's toString shows it. Fields typed float are shown as floats ([kind] 1, lists of them 2). */
function $show(v, kind) {
    if (v === null || v === undefined) return "null";
    if (v === true) return "true";
    if (v === false) return "false";
    if (typeof v === "number") return kind === 1 ? $showFloat(v) : String(v);
    if (typeof v === "string") return v;
    if (Array.isArray(v) || v instanceof $Set) return "[" + Array.from(v, x => $show(x, kind === 2 ? 1 : 0)).join(", ") + "]";
    if (v instanceof $Map) return "{" + Array.from(v.m.values(), e => $show(e[0]) + "=" + $show(e[1])).join(", ") + "}";
    return String(v);
}

/** A record: compared by value. */
class $Record {
    toString() {
        const kinds = this.$kinds;
        return this.$name + "(" + this.$fields.map((f, i) => f + "=" + $show(this[f], kinds[i])).join(", ") + ")";
    }
}
$Record.prototype.$fields = [];
$Record.prototype.$kinds = [];
$Record.prototype.$name = "";

/** An item of an enum; items without fields are shown by name. */
class $EnumItem extends $Record {
    toString() {
        return this.$fields.length === 0 ? this.$name : super.toString();
    }
}

// ---- nodes --------------------------------------------------------------------------------------

/** A vertex of a graph: a node, or a synthetic or exit vertex of a flow. */
class Vertex {
    static [Symbol.hasInstance](x) {
        return x != null && x.$is_Vertex === true;
    }
}

class $Node {
    constructor() {
        this.$parent = null;
        this.$span = null;
        this.$cache = new Map();
    }

    $adopt() {
        // a child that already has a parent is copied: a node is the child of one node
        for (const f of this.$fields) {
            const v = this[f];
            const c = $claim(v);
            if (c !== v) this[f] = c;
        }
        for (const c of this.$children()) c.$parent = this;
    }

    /** A copy of the tree below this node (with fresh attributes), without a parent. */
    $copy() {
        const n = new this.constructor(...this.$fields.map(f => $copyChildren(this[f])));
        n.$span = this.$span;
        return n;
    }

    $children() {
        const result = [];
        for (const f of this.$fields) {
            const v = this[f];
            if (v instanceof $Node) {
                result.push(v);
            } else if (Array.isArray(v)) {
                for (const x of v) if (x instanceof $Node) result.push(x);
            }
        }
        return result;
    }

    $root() {
        let n = this;
        while (n.$parent !== null) n = n.$parent;
        return n;
    }

    toString() {
        const kinds = this.$kinds;
        return this.$name + "(" + this.$fields.map((f, i) => $show(this[f], kinds[i])).join(", ") + ")";
    }
}
$Node.prototype.$fields = [];
$Node.prototype.$kinds = [];
$Node.prototype.$name = "";
$Node.prototype.$is_Vertex = true;

/** A child for a new node: v itself, or a copy of the nodes in it that already have a parent. */
function $claim(v) {
    if (v instanceof $Node) return v.$parent !== null ? v.$copy() : v;
    if (Array.isArray(v) && v.some(x => x instanceof $Node && x.$parent !== null)) return v.map($claim);
    return v;
}

/** Copies of the nodes in v (a node, null, or an array of them), for the copy of the node it is a field of. */
function $copyChildren(v) {
    if (v instanceof $Node) return v.$copy();
    if (Array.isArray(v)) return v.map($copyChildren);
    return v;
}

/**
 * The value of a child syn: the nodes in it (a node, null, an array of nodes or a map with nodes as values) become
 * children of owner; a node that already has a parent is copied. With spans (the tree a node forwards to) the nodes
 * without a span get the one of owner.
 */
function $adoptChildren(owner, v, spans) {
    const one = x => {
        if (!(x instanceof $Node)) return x;
        const child = x.$parent !== null ? x.$copy() : x;
        child.$parent = owner;
        if (spans && owner.$span !== null) for (const n of $preorder(child)) if (n.$span === null) n.$span = owner.$span;
        return child;
    };
    if (Array.isArray(v)) return v.map(one);
    if (v instanceof $Map) return new $Map(new Map(Array.from(v.m, ([key, [k, x]]) => [key, [k, one(x)]])));
    return one(v);
}

/** The nodes of the tree below node (including it), parents before children. */
function $preorder(node) {
    const result = [];
    const work = [node];
    while (work.length > 0) {
        const n = work.pop();
        result.push(n);
        const children = n.$children();
        for (let i = children.length - 1; i >= 0; i--) work.push(children[i]);
    }
    return result;
}

// ---- caches and fixpoints -----------------------------------------------------------------------

class $Cycle {
    constructor() {
        this.iteration = 0;
        this.changed = false;
        this.attributes = [];
    }
    involve(attribute) {
        if (!this.attributes.includes(attribute)) this.attributes.push(attribute);
    }
}

let $cycle = null;

/** Values of one syn, inh or coll attribute, per node. Nothing is cached while a fixpoint is computed. */
class $Cache {
    constructor(attribute) {
        this.attribute = attribute;
        this.key = Symbol(attribute);
        this.busy = Symbol(attribute + " busy");
    }

    get(node, compute) {
        const cache = node.$cache;
        const value = cache.get(this.key);
        if (value !== undefined) return value;
        if (cache.has(this.busy)) {
            throw new ForgeError("attribute '" + this.attribute + "' of " + $show(node) + " depends on itself; declare it as circular");
        }
        cache.set(this.busy, true);
        try {
            const result = compute();
            if ($cycle === null) cache.set(this.key, result);
            return result;
        } finally {
            cache.delete(this.busy);
        }
    }
}

/** A circular attribute, computed as a fixpoint starting at its initial value. */
class $Circular {
    constructor() {
        this.key = Symbol("circular");
        this.approx = new Map();
        this.visited = new Map();
    }

    get(node, initial, body) {
        const final = node.$cache.get(this.key);
        if (final !== undefined) return final;
        const cycle = $cycle;
        if (cycle !== null) {
            cycle.involve(this);
            if (this.visited.get(node) === cycle.iteration) return this.approxOf(node, initial);
            return this.step(node, initial, body, cycle);
        }
        const c = new $Cycle();
        $cycle = c;
        try {
            c.involve(this);
            do {
                c.changed = false;
                c.iteration++;
                this.step(node, initial, body, c);
            } while (c.changed);
            for (const attribute of c.attributes) attribute.finish(c.iteration);
            return node.$cache.get(this.key);
        } finally {
            $cycle = null;
            for (const attribute of c.attributes) {
                attribute.approx = new Map();
                attribute.visited = new Map();
            }
        }
    }

    approxOf(node, initial) {
        return this.approx.has(node) ? this.approx.get(node) : initial;
    }

    step(node, initial, body, cycle) {
        this.visited.set(node, cycle.iteration);
        const old = this.approxOf(node, initial);
        const value = body();
        const changed = !$eq(value, old);
        if (changed || !this.approx.has(node)) {
            if (changed) cycle.changed = true;
            this.approx.set(node, value);
        }
        return value;
    }

    finish(last) {
        for (const [node, iteration] of this.visited) {
            if (iteration === last) node.$cache.set(this.key, this.approx.get(node));
        }
    }
}

// ---- graphs -------------------------------------------------------------------------------------

/** A directed edge (compared by identity). */
class Edge {
    constructor(from, to, label = null) {
        this.from = from;
        this.to = to;
        this.label = label;
    }
    toString() {
        if (this.label === null) return $show(this.from) + " -> " + $show(this.to);
        return $show(this.from) + " -[" + this.label + "]-> " + $show(this.to);
    }
}

/** A graph from edges, e.g. a call graph. The vertices are entry and the endpoints of edges. */
function graphOf(entry, edges) {
    const vertices = [entry];
    for (const e of edges) vertices.push(e.from, e.to);
    return new Graph(null, entry, vertices, edges, new Map());
}

/** A directed graph. Vertices are compared by identity. */
class Graph {
    constructor(root, entry, vertices, edges, exits) {
        this.root = root;
        this.entry = entry;
        this.edges = Array.from(edges);
        this.$exits = exits;
        this.$index = new Map();
        this.vertices = [];
        const add = v => {
            if (!this.$index.has(v)) {
                this.$index.set(v, this.vertices.length);
                this.vertices.push(v);
            }
        };
        for (const v of vertices) add(v);
        for (const e of this.edges) {
            add(e.from);
            add(e.to);
        }
        this.$out = this.vertices.map(() => []);
        this.$in = this.vertices.map(() => []);
        for (const e of this.edges) {
            this.$out[this.$index.get(e.from)].push(e);
            this.$in[this.$index.get(e.to)].push(e);
        }
    }

    contains(v) { return this.$index.has(v); }

    indexOf(v) {
        const i = this.$index.get(v);
        return i === undefined ? -1 : i;
    }

    outEdges(v) {
        const i = this.$index.get(v);
        return i === undefined ? [] : this.$out[i].slice();
    }

    inEdges(v) {
        const i = this.$index.get(v);
        return i === undefined ? [] : this.$in[i].slice();
    }

    succ(v) { return this.outEdges(v).map(e => e.to); }

    pred(v) { return this.inEdges(v).map(e => e.from); }

    exit(port) {
        for (const [p, v] of this.$exits) if (p.name === port) return v;
        return null;
    }

    reachable() {
        const seen = new Array(this.vertices.length).fill(false);
        const result = [];
        const work = [this.entry];
        while (work.length > 0) {
            const v = work.pop();
            const i = this.indexOf(v);
            if (i < 0 || seen[i]) continue;
            seen[i] = true;
            result.push(v);
            const succ = this.succ(v);
            for (let k = succ.length - 1; k >= 0; k--) work.push(succ[k]);
        }
        return result;
    }

    postorder() {
        const seen = new Array(this.vertices.length).fill(false);
        const result = [];
        const stack = [];
        const push = v => {
            const i = this.indexOf(v);
            if (i < 0 || seen[i]) return;
            seen[i] = true;
            stack.push([v, this.succ(v), 0]);
        };
        push(this.entry);
        while (stack.length > 0) {
            const top = stack[stack.length - 1];
            if (top[2] < top[1].length) {
                push(top[1][top[2]++]);
            } else {
                stack.pop();
                result.push(top[0]);
            }
        }
        return result;
    }

    reversePostorder() { return this.postorder().reverse(); }

    toDot() {
        const esc = s => s.split("\\").join("\\\\").split("\"").join("\\\"");
        let out = "digraph G {\n";
        this.vertices.forEach((v, i) => {
            out += "  n" + i + " [label=\"" + esc($show(v)) + "\"];\n";
        });
        for (const e of this.edges) {
            out += "  n" + this.indexOf(e.from) + " -> n" + this.indexOf(e.to);
            if (e.label !== null) out += " [label=\"" + esc(e.label) + "\"]";
            out += ";\n";
        }
        return out + "}";
    }

    toString() {
        return "Graph(" + this.vertices.length + " vertices, " + this.edges.length + " edges)";
    }
}

/** A named exit of a flow. Inherited ports are looked up through the enclosing flows. */
class $Port {
    constructor(name, inherited) {
        this.name = name;
        this.inherited = inherited;
    }
    toString() { return this.name; }
}

const $PORT_NEXT = new $Port("next", false);

/** The port of a child, e.g. onTrue(cond). */
class $PortRef {
    constructor(node, port) {
        this.node = node;
        this.port = port;
    }
    toString() { return String(this.port) + "(" + $show(this.node) + ")"; }
}

/** The vertex an unbound port of a root leads to, e.g. the end of a function. */
class $ExitVertex {
    constructor(port) {
        this.port = port;
        this.$cache = new Map();
    }
    toString() { return "exit(" + String(this.port) + ")"; }
}
$ExitVertex.prototype.$is_Vertex = true;

/** A vertex declared in a flow (vertex name). */
class $SyntheticVertex {
    constructor(name, owner) {
        this.name = name;
        this.owner = owner;
        this.$cache = new Map();
    }
    toString() { return this.name; }
}
$SyntheticVertex.prototype.$is_Vertex = true;

/** A graph definition: one graph per root node, built from the flows of the nodes below it. */
class $FlowGraph {
    constructor(isRoot, rule) {
        this.isRoot = isRoot;
        this.rule = rule;
        this.graphs = new Map();
        this.owners = new Map();
    }

    get(root) {
        const g = this.graphs.get(root);
        if (g !== undefined) return g;
        const graph = new $FlowBuild(root, this.rule).build();
        this.graphs.set(root, graph);
        for (const v of graph.vertices) this.owners.set(v, graph);
        return graph;
    }

    of(vertex) {
        const g = this.owners.get(vertex);
        if (g !== undefined) return g;
        let current = vertex;
        while (current != null) {
            if (this.isRoot(current)) return this.get(current);
            current = current instanceof $Node ? current.$parent : null;
        }
        return null;
    }

    succ(vertex) {
        const g = this.of(vertex);
        return g === null ? [] : g.succ(vertex);
    }

    pred(vertex) {
        const g = this.of(vertex);
        return g === null ? [] : g.pred(vertex);
    }
}

// flow targets: {kind: "vertex", x}, {kind: "child", x}, {kind: "port", x, port}

/** Where a flow sends control. The node itself and its declared vertices are vertices of the graph. */
class $FlowScope {
    constructor(node, build) {
        this.node = node;
        this.build = build;
        this.entrySpec = null;
        this.own = new Set();
    }

    entry(target) {
        if (this.entrySpec !== null) throw new ForgeError("the flow of " + $show(this.node) + " already has an entry");
        this.entrySpec = this.toTarget(target);
    }

    connect(source, target, label) {
        const to = this.toTarget(target);
        if (source instanceof $Port) {
            throw new ForgeError("a port of the node itself can not be connected; only ports of children: " + String(source) + "(" + $show(this.node) + ")");
        }
        if (source instanceof $PortRef) {
            if (label !== null) throw new ForgeError("only edges from vertices can be labeled: " + String(source));
            this.build.reference(source.node, this.node);
            this.build.bind(source.node, source.port, to);
        } else if (this.isVertex(source)) {
            this.build.addVertex(source);
            this.build.edges.push([source, to, label]);
        } else {
            if (label !== null) throw new ForgeError("only edges from vertices can be labeled: " + $show(source));
            this.build.reference(source, this.node);
            this.build.bind(source, $PORT_NEXT, to);
        }
    }

    vertex(v) {
        this.own.add(v);
        this.build.addVertex(v);
        return v;
    }

    bound(port) {
        return this.build.isBound(this.node, port);
    }

    sequence(items) {
        if (items.length === 0) {
            this.entry($PORT_NEXT);
            return;
        }
        this.entry(items[0]);
        for (let i = 0; i < items.length - 1; i++) this.connect(items[i], items[i + 1], null);
        this.connect(items[items.length - 1], $PORT_NEXT, null);
    }

    isVertex(x) {
        return x === this.node || this.own.has(x);
    }

    toTarget(x) {
        if (x instanceof $Port) return { kind: "port", x: this.node, port: x };
        if (x instanceof $PortRef) {
            throw new ForgeError("a port of a child can not be a target: " + String(x) + ". Connect it instead: " + String(x) + " -> ...");
        }
        if (this.isVertex(x)) {
            this.build.addVertex(x);
            return { kind: "vertex", x: x };
        }
        this.build.reference(x, this.node);
        return { kind: "child", x: x };
    }
}

/** Runs the flows of all nodes reachable from root and resolves them into a Graph. */
class $FlowBuild {
    constructor(root, rule) {
        this.root = root;
        this.rule = rule;
        this.scopes = new Map();
        this.flowParent = new Map();
        this.bindings = new Map();
        this.queue = [];
        this.vertices = [];
        this.vertexSet = new Set();
        this.exits = new Map();
        this.edges = [];
        this.resolved = new Map();
        this.resolving = new Set();
    }

    build() {
        this.queue.push(this.root);
        for (let q = 0; q < this.queue.length; q++) {
            const node = this.queue[q];
            if (this.scopes.has(node)) continue;
            const scope = new $FlowScope(node, this);
            this.scopes.set(node, scope);
            if (!this.rule(scope, node)) {
                const parent = this.flowParent.get(node);
                throw new ForgeError("no flow defined for " + node.$name +
                    (parent === undefined ? "" : ", used in the flow of " + parent.$name));
            }
        }
        const entry = this.resolve({ kind: "child", x: this.root });
        const edges = this.edges.map(([f, t, l]) => new Edge(f, this.resolve(t), l));
        const root = this.root instanceof Vertex ? this.root : null;
        return new Graph(root, entry, [entry].concat(this.vertices), edges, this.exits);
    }

    reference(child, by) {
        if (child === this.root || this.flowParent.has(child)) return;
        this.flowParent.set(child, by);
        this.queue.push(child);
    }

    bind(child, port, target) {
        let ports = this.bindings.get(child);
        if (ports === undefined) {
            ports = new Map();
            this.bindings.set(child, ports);
        }
        if (ports.has(port)) throw new ForgeError("port " + String(port) + " of " + $show(child) + " is connected twice");
        ports.set(port, target);
    }

    addVertex(v) {
        if (!this.vertexSet.has(v)) {
            this.vertexSet.add(v);
            this.vertices.push(v);
        }
    }

    isBound(node, port) {
        const ports = this.bindings.get(node);
        if (ports !== undefined && ports.has(port)) return true;
        if (!port.inherited) return false;
        const parent = this.flowParent.get(node);
        return parent !== undefined && this.isBound(parent, port);
    }

    resolve(target) {
        const key = target.kind + ":" + $oid(target.x) + (target.port === undefined ? "" : ":" + $oid(target.port));
        if (this.resolved.has(key)) return this.resolved.get(key);
        if (this.resolving.has(key)) throw new ForgeError("flow cycle without a vertex at " + this.describe(target));
        this.resolving.add(key);
        try {
            let vertex;
            if (target.kind === "vertex") {
                vertex = target.x;
            } else if (target.kind === "child") {
                const scope = this.scopes.get(target.x);
                if (scope === undefined) throw new ForgeError("no flow for " + $show(target.x));
                if (scope.entrySpec === null) throw new ForgeError("the flow of " + $show(target.x) + " has no entry");
                vertex = this.resolve(scope.entrySpec);
            } else {
                vertex = this.resolvePort(target.x, target.port);
            }
            this.resolved.set(key, vertex);
            return vertex;
        } finally {
            this.resolving.delete(key);
        }
    }

    resolvePort(node, port) {
        const ports = this.bindings.get(node);
        const target = ports === undefined ? undefined : ports.get(port);
        if (target !== undefined) return this.resolve(target);
        if (node === this.root) {
            if (!this.exits.has(port)) {
                const v = new $ExitVertex(port);
                this.exits.set(port, v);
                this.addVertex(v);
            }
            return this.exits.get(port);
        }
        const parent = this.flowParent.get(node);
        if (port.inherited) return this.resolvePort(parent, port);
        throw new ForgeError("port " + String(port) + " of " + $show(node) + " is not connected; connect it in the flow of " + $show(parent));
    }

    describe(target) {
        if (target.kind === "vertex") return $show(target.x);
        if (target.kind === "child") return "entry of " + $show(target.x);
        return String(target.port) + "(" + $show(target.x) + ")";
    }
}

/** Where a parser read a node from: offset and length in characters (bytes for binary input). */
class Span extends $Record {
    constructor(source, line, column, offset, length, text) {
        super();
        this.source = source;
        this.line = line;
        this.column = column;
        this.offset = offset;
        this.length = length;
        this.text = text;
    }
    toString() {
        return (this.source === null ? "" : this.source + ":") + this.line + ":" + this.column;
    }
}
Span.prototype.$fields = ["source", "line", "column", "offset", "length", "text"];
Span.prototype.$kinds = [0, 0, 0, 0, 0, 0];
Span.prototype.$name = "Span";

/** Where node was parsed from, or null if no parser created it. */
function spanOf(node) {
    return node instanceof $Node ? node.$span : null;
}

// ==== parser runtime ===============================================================================

/** A failed parse: where it failed and what would have been accepted there. */
class ParseException extends Error {
    constructor(message, offset, line, column, expected, source) {
        super(message);
        this.name = "ParseException";
        this.offset = offset;
        this.line = line;
        this.column = column;
        this.expected = expected;
        this.source = source;
    }
}

const $MISMATCH = { mismatch: true };

function $rethrow(e) {
    if (e !== $MISMATCH) throw e;
}

let $ruleIds = 0;

class $Rule {
    constructor(name, body) {
        this.name = name;
        this.body = body;
        this.id = $ruleIds++;
    }
    run(scope, precedence) {
        return this.body(scope);
    }
}

/** A rule made of alternatives and operators with precedence and associativity. */
class $AbstractRule extends $Rule {
    constructor(name) {
        super(name, null);
        this.alternatives = []; // [precedence, operand precedence or null, parse]
        this.operators = [];    // [precedence, operand precedence, extend]
    }
    on(parse) { this.alternatives.push([2147483647, null, parse]); }
    prefix(precedence, parse) { this.alternatives.push([precedence, precedence, parse]); }
    infixLeft(precedence, extend) { this.operators.push([precedence, precedence + 1, extend]); }
    infixRight(precedence, extend) { this.operators.push([precedence, precedence, extend]); }
    postfix(precedence, extend) { this.operators.push([precedence, precedence, extend]); }
    run(scope, precedence) {
        return scope.climb(this, precedence);
    }
}

/** A regular expression (translated from Java's syntax). Literals must not end inside a word (fun does not match funny). */
class $Terminal {
    constructor(regex, flags, literal, display) {
        this.regex = regex;
        this.flags = flags;
        this.literal = literal;
        this.display = display;
        this.compiled = null;
    }
    get pattern() {
        if (this.compiled === null) this.compiled = $regex(this.regex, this.flags);
        return this.compiled;
    }
}

function $regex(source, flags) {
    try {
        return new RegExp(source, "y" + flags);
    } catch (e) {
        throw new ForgeError("invalid regular expression /" + source + "/: " + e.message);
    }
}

class $Bits {
    constructor(bits, display) {
        this.bits = Array.from(bits, b => b === "1");
        this.display = display;
    }
}

/** A label of a rule: assigned while parsing, read by the producer. */
class $Label {
    constructor(name) {
        this.name = name;
        this.isSet = false;
        this.value = null;
    }
    set(value) {
        this.value = value;
        this.isSet = true;
        return value;
    }
    get() {
        if (!this.isSet) throw new ForgeError("label '" + this.name + "' was not matched");
        return this.value;
    }
}

const $WORD = /[\p{L}\p{N}_]/u;
function $wordChar(c) {
    return $WORD.test(c);
}

class $ParseScope {
    constructor(text, data, skip, keywords, source) {
        this.text = text;
        this.data = data;
        this.skip = skip;
        this.keywords = keywords;
        this.source = source;
        this.unit = data === null ? 1 : 8;
        this.size = text.length * this.unit;
        this.pos = 0;
        this.memo = new Map();
        this.stack = [];
        this.leftRecursive = new Set();
        this.involved = new Set();
        this.furthest = -1;
        this.expected = [];
        this.operators = [];
    }

    // ---- text

    terminal(t) {
        this.skipIgnored();
        if (this.pos % this.unit !== 0) this.fail(t.display);
        const start = this.pos / this.unit;
        const pattern = t.pattern;
        pattern.lastIndex = start;
        const m = pattern.exec(this.text);
        if (m === null) this.fail(t.display);
        const value = m[0];
        const end = start + value.length;
        if (t.literal !== null) {
            if (t.literal.length > 0 && $wordChar(t.literal[t.literal.length - 1]) && end < this.text.length && $wordChar(this.text[end])) {
                this.fail(t.display);
            }
        } else if (this.keywords.has(value)) {
            this.fail(t.display);
        }
        this.pos = end * this.unit;
        return value;
    }

    /** The end of what skip matches at offset p (in characters). */
    skipEnd(p) {
        this.skip.lastIndex = p;
        const m = this.skip.exec(this.text);
        return m === null ? p : p + m[0].length;
    }

    skipIgnored() {
        if (this.skip === null || this.pos % this.unit !== 0) return;
        for (;;) {
            const p = this.pos / this.unit;
            const end = this.skipEnd(p);
            if (end === p) return;
            this.pos = end * this.unit;
        }
    }

    // ---- binary

    bits(pattern) {
        this.requireBinary();
        const width = pattern.bits.length;
        if (this.pos + width > this.size) this.fail(pattern.display);
        for (let i = 0; i < width; i++) if (this.bitAt(this.pos + i) !== pattern.bits[i]) this.fail(pattern.display);
        this.pos += width;
        return pattern.display;
    }

    uint(width) {
        this.requireBinary();
        if (width < 0 || width > 32) throw new ForgeError("width must be between 0 and 32");
        if (this.pos + width > this.size) this.fail(width + " more bits");
        let value = 0;
        for (let i = 0; i < width; i++) value = (value << 1) | (this.bitAt(this.pos + i) ? 1 : 0);
        this.pos += width;
        return value | 0;
    }

    sint(width) {
        if (width < 1 || width > 32) throw new ForgeError("width must be between 1 and 32");
        const value = this.uint(width);
        if (width < 32 && ((value >>> (width - 1)) & 1) === 1) return (value - (1 << width)) | 0;
        return value | 0;
    }

    bit() { return this.uint(1) === 1; }

    u1() { return this.uint(8); }

    u2() { return this.uint(16); }

    u4() { return this.uint(32); }

    bytes(count) {
        this.requireBinary();
        if (this.pos + count * 8 > this.size) this.fail(count + " more bytes");
        const result = [];
        for (let i = 0; i < count; i++) result.push(this.uint(8));
        return result;
    }

    bitAt(p) {
        return ((this.data[p >> 3] >> (7 - (p & 7))) & 1) === 1;
    }

    requireBinary() {
        if (this.data === null) throw new ForgeError("bit and byte primitives need binary input");
    }

    // ---- failure and combinators

    fail(expected) {
        if (this.pos > this.furthest) {
            this.furthest = this.pos;
            this.expected = [];
        }
        if (this.pos === this.furthest && !this.expected.includes(expected)) this.expected.push(expected);
        throw $MISMATCH;
    }

    optional(body) {
        const start = this.pos;
        try {
            return body();
        } catch (e) {
            $rethrow(e);
            this.pos = start;
            return null;
        }
    }

    many(body) {
        const result = [];
        for (;;) {
            const start = this.pos;
            try {
                result.push(body());
            } catch (e) {
                $rethrow(e);
                this.pos = start;
                return result;
            }
            if (this.pos === start) return result;
        }
    }

    some(body) {
        const first = body();
        return [first].concat(this.many(body));
    }

    repeat(count, body) {
        const result = [];
        for (let i = 0; i < count; i++) result.push(body());
        return result;
    }

    andP(body) {
        const start = this.pos;
        try {
            body();
        } finally {
            this.pos = start;
        }
        return null;
    }

    notP(description, body) {
        const start = this.pos;
        let matched;
        try {
            body();
            matched = true;
        } catch (e) {
            $rethrow(e);
            matched = false;
        } finally {
            this.pos = start;
        }
        if (matched) this.fail("not " + description);
        return null;
    }

    oneOf(...alternatives) {
        const start = this.pos;
        for (const alternative of alternatives) {
            try {
                return alternative();
            } catch (e) {
                $rethrow(e);
                this.pos = start;
            }
        }
        throw $MISMATCH;
    }

    /** A sequence: the parsers run in order; items are [parse, label or null]. */
    seq(items, valueIndex) {
        const values = [];
        for (const [parse, label] of items) {
            const v = parse();
            if (label !== null) label.set(v);
            values.push(v);
        }
        if (valueIndex === null) return null;
        if (valueIndex === -1) return values;
        return values[valueIndex];
    }

    // ---- abstract rules

    operand() {
        const context = this.operators.length > 0 ? this.operators[this.operators.length - 1] : null;
        if (context === null) throw new ForgeError("an operand outside of an operator");
        return this.apply(context[0], context[1]);
    }

    inOperator(rule, operandPrecedence, block) {
        this.operators.push(operandPrecedence === null ? null : [rule, operandPrecedence]);
        try {
            return block();
        } finally {
            this.operators.pop();
        }
    }

    climb(rule, minPrecedence) {
        const start = this.pos;
        let left = $MISSING;
        for (const [, operandPrecedence, parse] of rule.alternatives) {
            try {
                left = this.inOperator(rule, operandPrecedence, () => parse(this));
                this.record(left, start, this.pos);
                break;
            } catch (e) {
                $rethrow(e);
                this.pos = start;
            }
        }
        if (left === $MISSING) {
            if (rule.alternatives.length === 0) throw new ForgeError("abstract rule '" + rule.name + "' has no alternatives");
            throw $MISMATCH;
        }
        for (;;) {
            let extendedAny = false;
            for (const [precedence, operandPrecedence, extend] of rule.operators) {
                if (precedence < minPrecedence) continue;
                const before = this.pos;
                try {
                    const current = left;
                    const extended = this.inOperator(rule, operandPrecedence, () => extend(this, current));
                    if (this.pos === before) continue;
                    left = extended;
                    this.record(extended, start, this.pos);
                    extendedAny = true;
                    break;
                } catch (e) {
                    $rethrow(e);
                    this.pos = before;
                }
            }
            if (!extendedAny) return left;
        }
    }

    // ---- engine

    /** Applies rule with memoization and left recursion (seed growing). */
    apply(rule, precedence = -2147483648) {
        const start = this.pos;
        const key = rule.id + ":" + start + ":" + precedence;
        let m = this.memo.get(key);
        if (m !== undefined) {
            if (m.head !== null) {
                this.leftRecursive.add(m.head);
                const index = this.stack.lastIndexOf(m.head);
                for (let i = index + 1; i < this.stack.length; i++) this.involved.add(this.stack[i]);
            }
            if (!m.ok) throw $MISMATCH;
            this.pos = m.end;
            return m.value;
        }
        const frame = {};
        m = { ok: false, value: null, end: start, head: frame };
        this.memo.set(key, m);
        this.stack.push(frame);
        this.operators.push(null);
        try {
            this.attempt(rule, precedence, start, m);
            if (this.leftRecursive.has(frame)) {
                while (m.ok) {
                    const value = m.value;
                    const end = m.end;
                    if (!this.attempt(rule, precedence, start, m) || m.end <= end) {
                        m.ok = true;
                        m.value = value;
                        m.end = end;
                        break;
                    }
                }
            }
        } finally {
            this.operators.pop();
            this.stack.pop();
            m.head = null;
            if (this.involved.has(frame)) this.memo.delete(key);
            this.leftRecursive.delete(frame);
            this.involved.delete(frame);
        }
        if (!m.ok) {
            this.pos = start;
            throw $MISMATCH;
        }
        this.pos = m.end;
        this.record(m.value, start, m.end);
        return m.value;
    }

    attempt(rule, precedence, start, into) {
        this.pos = start;
        try {
            into.value = rule.run(this, precedence);
            into.end = this.pos;
            into.ok = true;
        } catch (e) {
            $rethrow(e);
            into.ok = false;
        }
        return into.ok;
    }

    record(value, start, end) {
        if (!(value instanceof $Node) || value.$span !== null) return;
        const from = Math.floor(this.skippedFrom(start) / this.unit);
        const to = Math.floor((end + this.unit - 1) / this.unit);
        if (this.data === null) {
            value.$span = new Span(this.source, this.lineOf(from), this.columnOf(from), from, to - from, this.text.substring(from, Math.max(from, to)));
        } else {
            value.$span = new Span(this.source, 0, 0, from, to - from, "");
        }
    }

    skippedFrom(from) {
        if (this.skip === null || from % this.unit !== 0) return from;
        let p = from / this.unit;
        for (;;) {
            const end = this.skipEnd(p);
            if (end === p) return p * this.unit;
            p = end;
        }
    }

    lineOf(offset) {
        let line = 1;
        const end = Math.min(offset, this.text.length);
        for (let i = this.text.indexOf("\n"); i >= 0 && i < end; i = this.text.indexOf("\n", i + 1)) line++;
        return line;
    }

    columnOf(offset) {
        return offset - (offset === 0 ? 0 : this.text.lastIndexOf("\n", offset - 1) + 1) + 1;
    }

    parseAll(rule) {
        try {
            const result = this.apply(rule);
            this.skipIgnored();
            if (this.pos === this.size) return result;
            if (this.pos > this.furthest) {
                this.furthest = this.pos;
                this.expected = [];
            }
            if (this.pos === this.furthest && !this.expected.includes("end of input")) this.expected.push("end of input");
        } catch (e) {
            $rethrow(e);
        }
        throw this.error();
    }

    error() {
        const at = Math.max(this.furthest, 0);
        const described = this.expected.join(" or ");
        const prefix = this.source === null ? "" : this.source + ":";
        if (this.data === null) {
            const found = at < this.text.length ? "'" + this.text[at] + "'" : "end of input";
            const line = this.lineOf(at);
            const column = this.columnOf(at);
            return new ParseException(prefix + line + ":" + column + ": expected " + described + ", found " + found,
                at, line, column, this.expected.slice(), this.source);
        }
        const byte = Math.floor(at / 8);
        const bit = at % 8;
        const where = bit === 0 ? "byte " + byte : "byte " + byte + ", bit " + bit;
        const found = byte < this.data.length ? "0x" + this.data[byte].toString(16).toUpperCase().padStart(2, "0") : "end of input";
        return new ParseException(prefix + where + ": expected " + described + ", found " + found, at, 0, 0, this.expected.slice(), this.source);
    }
}

function $parseText(rule, text, skip, keywords, source) {
    return new $ParseScope(text, null, skip, keywords, source == null ? null : source).parseAll(rule);
}

/** Binary input: a list of ints, a Uint8Array or an ArrayBuffer. */
function $parseBinary(rule, input, skip, keywords, source) {
    const data = input instanceof ArrayBuffer ? new Uint8Array(input) : Uint8Array.from(input, b => b & 0xFF);
    let text = "";
    for (let i = 0; i < data.length; i += 8192) text += String.fromCharCode.apply(null, data.subarray(i, i + 8192));
    return new $ParseScope(text, data, skip, keywords, source == null ? null : source).parseAll(rule);
}

// ==== end of Forge runtime =========================================================================
