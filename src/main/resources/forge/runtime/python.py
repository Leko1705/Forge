# ==== Forge runtime ============================================================================
# Attribute caches, fixpoints of circular attributes, graphs, flows and the parser engine.

import re as _re
import struct as _struct
import functools as _functools
import sys as _sys

_sys.setrecursionlimit(max(_sys.getrecursionlimit(), 200000))

class ForgeError(Exception):
    """An error while evaluating: a missing value, no matching case, an attribute that depends on itself."""

def _panic(message):
    raise ForgeError(message)

_MISSING = object()

class _NoMatch:
    __slots__ = ()
    def __repr__(self):
        return "<no match>"

_NOMATCH = _NoMatch()

def _match(subject, cases, alternative):
    """The first case (a function returning _NOMATCH if it does not apply) that matches."""
    for case in cases:
        result = case()
        if result is not _NOMATCH:
            return result
    if alternative is not None:
        return alternative()
    raise ForgeError("no match for " + _show(subject))

# ---- values -------------------------------------------------------------------------------------

def _i32(v):
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v & 0x80000000 else v

def _div(a, b):
    if b == 0:
        raise ZeroDivisionError("/ by zero")
    q = abs(a) // abs(b)
    return _i32(q if (a >= 0) == (b >= 0) else -q)

def _mod(a, b):
    if b == 0:
        raise ZeroDivisionError("/ by zero")
    return _i32(a - b * _div(a, b))

def _f32(v):
    return _struct.unpack("f", _struct.pack("f", v))[0]

def _f2i(v):
    if v != v:
        return 0
    if v >= 2147483647:
        return 2147483647
    if v <= -2147483648:
        return -2147483648
    return int(v)

def _nn(v):
    if v is None:
        raise ForgeError("null value where a value is required (!!)")
    return v

def _elvis(v, alternative):
    return alternative() if v is None else v

def _fold(items, acc, body):
    for x in items:
        acc = body(acc, x)
    return acc

def _get(xs, i):
    k = i + len(xs) if i < 0 else i
    if k < 0 or k >= len(xs):
        raise ForgeError("index " + str(i) + " out of bounds for length " + str(len(xs)))
    return xs[k]

class _Set(dict):
    """A set: the keys of an insertion-ordered dict."""
    __slots__ = ()
    def __repr__(self):
        return _show(self)

def _set(*elements):
    return _Set.fromkeys(_key(e) for e in elements)

def _set_union(a, b):
    s = _Set(a)
    s.update(b)
    return s

def _set_remove(s, x):
    r = _Set(s)
    r.pop(_key(x), None)
    return r

def _map_remove(m, k):
    r = dict(m)
    r.pop(_key(k), None)
    return r

def _index_of(xs, x):
    for i, y in enumerate(xs):
        if y == x:
            return i
    return -1

_INT = _re.compile(r"[+-]?[0-9]+")
_FLOAT = _re.compile(r"[+-]?([0-9]+[.]?[0-9]*|[.][0-9]+)([eE][+-]?[0-9]+)?")

def _to_int(s):
    if not _INT.fullmatch(s) or not -2147483648 <= int(s) <= 2147483647:
        raise ForgeError("not an int: \"" + s + "\"")
    return int(s)

def _to_float(s):
    if not _FLOAT.fullmatch(s):
        raise ForgeError("not a float: \"" + s + "\"")
    return _f32(float(s))

def _char_at(s, i):
    return _get(s, i)

def _split(s, separator):
    if not separator:
        raise ForgeError("the separator of split is empty")
    return s.split(separator)

def _replace(s, old, new):
    if not old:
        raise ForgeError("the text to replace is empty")
    return s.replace(old, new)

def _unescape(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            i += 1
            out.append({"n": "\n", "t": "\t", "r": "\r", "0": "\0"}.get(s[i], s[i]))
        else:
            out.append(c)
        i += 1
    return "".join(out)

def _key(k):
    """Map keys: lists become tuples so that they can be hashed."""
    return tuple(_key(x) for x in k) if isinstance(k, list) else k

def _map(*entries):
    m = {}
    for k, v in entries:
        m[_key(k)] = v
    return m

def _at(m, k):
    try:
        return m[_key(k)]
    except KeyError:
        raise ForgeError("key " + _show(k) + " is missing in the map")

def _in_map(m, k):
    return _key(k) in m

def _merge(a, b):
    m = dict(a)
    m.update(b)
    return m

def _put(a, k, v):
    m = dict(a)
    m[_key(k)] = v
    return m

def _show_float(v):
    """Like Kotlin's Float.toString: the shortest decimal that reads back as the same float."""
    if v != v:
        return "NaN"
    if v in (float("inf"), float("-inf")):
        return "Infinity" if v > 0 else "-Infinity"
    if v == 0:
        return "-0.0" if str(v).startswith("-") else "0.0"
    for digits in range(1, 10):
        s = "%.*e" % (digits - 1, v)
        if _f32(float(s)) == v:
            break
    mantissa, exponent = s.split("e")
    exponent = int(exponent)
    negative = mantissa.startswith("-")
    mantissa = mantissa.lstrip("-").replace(".", "")
    if -3 <= exponent < 7:
        if exponent >= 0:
            whole = mantissa[:exponent + 1].ljust(exponent + 1, "0")
            fraction = mantissa[exponent + 1:] or "0"
        else:
            whole = "0"
            fraction = "0" * (-exponent - 1) + mantissa
        text = whole + "." + fraction
    else:
        text = mantissa[0] + "." + (mantissa[1:] or "0") + "E" + str(exponent)
    return ("-" if negative else "") + text

def _show(v):
    """A value as Kotlin's toString shows it."""
    if v is None:
        return "null"
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return _show_float(v)
    if isinstance(v, str):
        return v
    if isinstance(v, (list, tuple, _Set)):
        return "[" + ", ".join(_show(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + ", ".join(_show(k) + "=" + _show(x) for k, x in v.items()) + "}"
    return str(v)

class _Record:
    """A record: compared by value."""
    __slots__ = ()
    _fields = ()
    _name = ""
    def __eq__(self, other):
        return type(self) is type(other) and all(getattr(self, f) == getattr(other, f) for f in self._fields)
    def __hash__(self):
        return hash((type(self),) + tuple(_key(getattr(self, f)) for f in self._fields))
    def __str__(self):
        return self._name + "(" + ", ".join(f.rstrip("_") + "=" + _show(getattr(self, f)) for f in self._fields) + ")"
    __repr__ = __str__

class _EnumItem(_Record):
    """An item of an enum; items without fields are shown by name."""
    __slots__ = ()
    def __str__(self):
        if not self._fields:
            return self._name
        return _Record.__str__(self)
    __repr__ = __str__

# ---- nodes --------------------------------------------------------------------------------------

class Vertex:
    """A vertex of a graph: a node, or a synthetic or exit vertex of a flow."""
    __slots__ = ()

class _Node(Vertex):
    __slots__ = ("_parent", "_span", "_cache", "__weakref__")
    _fields = ()
    _name = ""

    def _adopt(self):
        self._parent = None
        self._span = None
        self._cache = {}
        for c in self._children():
            c._parent = self

    def _children(self):
        result = []
        for f in self._fields:
            v = getattr(self, f)
            if isinstance(v, _Node):
                result.append(v)
            elif isinstance(v, list):
                for x in v:
                    if isinstance(x, _Node):
                        result.append(x)
        return result

    def _root(self):
        n = self
        while n._parent is not None:
            n = n._parent
        return n

    def __str__(self):
        return self._name + "(" + ", ".join(_show(getattr(self, f)) for f in self._fields) + ")"
    __repr__ = __str__

def _preorder(node):
    """The nodes of the tree below node (including it), parents before children."""
    result = []
    work = [node]
    while work:
        n = work.pop()
        result.append(n)
        work.extend(reversed(n._children()))
    return result

# ---- caches and fixpoints -----------------------------------------------------------------------

class _Cycle:
    def __init__(self):
        self.iteration = 0
        self.changed = False
        self.attributes = []

    def involve(self, attribute):
        if not any(a is attribute for a in self.attributes):
            self.attributes.append(attribute)

_cycle = [None]

class _Cache:
    """Values of one syn, inh or coll attribute, per node. Nothing is cached while a fixpoint is computed."""
    def __init__(self, attribute):
        self.attribute = attribute
        self.key = object()
        self.busy = self.attribute + " busy"

    def get(self, node, compute):
        cache = node._cache
        value = cache.get(self.key, _MISSING)
        if value is not _MISSING:
            return value
        if self.busy in cache:
            raise ForgeError("attribute '" + self.attribute + "' of " + _show(node) + " depends on itself; declare it as circular")
        cache[self.busy] = True
        try:
            value = compute()
            if _cycle[0] is None:
                cache[self.key] = value
            return value
        finally:
            del cache[self.busy]

class _Circular:
    """A circular attribute, computed as a fixpoint starting at its initial value."""
    def __init__(self):
        self.key = object()
        self.approx = {}
        self.visited = {}

    def get(self, node, initial, body):
        final = node._cache.get(self.key, _MISSING)
        if final is not _MISSING:
            return final
        cycle = _cycle[0]
        if cycle is not None:
            cycle.involve(self)
            if self.visited.get(id(node), (None, -1))[1] == cycle.iteration:
                return self.approx_of(node, initial)
            return self.step(node, initial, body, cycle)
        c = _Cycle()
        _cycle[0] = c
        try:
            c.involve(self)
            while True:
                c.changed = False
                c.iteration += 1
                self.step(node, initial, body, c)
                if not c.changed:
                    break
            for attribute in c.attributes:
                attribute.finish(c.iteration)
            return node._cache[self.key]
        finally:
            _cycle[0] = None
            for attribute in c.attributes:
                attribute.approx = {}
                attribute.visited = {}

    def approx_of(self, node, initial):
        entry = self.approx.get(id(node))
        return initial if entry is None else entry[1]

    def step(self, node, initial, body, cycle):
        self.visited[id(node)] = (node, cycle.iteration)
        old = self.approx_of(node, initial)
        new = body()
        if new != old or id(node) not in self.approx:
            if new != old:
                cycle.changed = True
            self.approx[id(node)] = (node, new)
        return new

    def finish(self, last):
        for key, (node, iteration) in self.visited.items():
            if iteration == last:
                node._cache[self.key] = self.approx[key][1]

# ---- graphs -------------------------------------------------------------------------------------

class Edge:
    """A directed edge (compared by identity)."""
    __slots__ = ("from_", "to", "label")
    def __init__(self, from_, to, label=None):
        self.from_ = from_
        self.to = to
        self.label = label
    def __str__(self):
        if self.label is None:
            return _show(self.from_) + " -> " + _show(self.to)
        return _show(self.from_) + " -[" + self.label + "]-> " + _show(self.to)
    __repr__ = __str__

def graphOf(entry, edges):
    """A graph from edges, e.g. a call graph. The vertices are entry and the endpoints of edges."""
    vertices = [entry]
    for e in edges:
        vertices.append(e.from_)
        vertices.append(e.to)
    return Graph(None, entry, vertices, edges, {})

class Graph:
    """A directed graph. Vertices are compared by identity."""
    def __init__(self, root, entry, vertices, edges, exits):
        self.root = root
        self.entry = entry
        self.edges = list(edges)
        self._exits = exits
        self._index = {}
        self.vertices = []
        for v in list(vertices) + [x for e in edges for x in (e.from_, e.to)]:
            if id(v) not in self._index:
                self._index[id(v)] = len(self.vertices)
                self.vertices.append(v)
        self._out = [[] for _ in self.vertices]
        self._in = [[] for _ in self.vertices]
        for e in self.edges:
            self._out[self._index[id(e.from_)]].append(e)
            self._in[self._index[id(e.to)]].append(e)

    def contains(self, v):
        return id(v) in self._index

    def indexOf(self, v):
        return self._index.get(id(v), -1)

    def outEdges(self, v):
        i = self._index.get(id(v))
        return [] if i is None else list(self._out[i])

    def inEdges(self, v):
        i = self._index.get(id(v))
        return [] if i is None else list(self._in[i])

    def succ(self, v):
        return [e.to for e in self.outEdges(v)]

    def pred(self, v):
        return [e.from_ for e in self.inEdges(v)]

    def exit(self, port):
        for p, v in self._exits.items():
            if p.name == port:
                return v
        return None

    def reachable(self):
        seen = [False] * len(self.vertices)
        result = []
        work = [self.entry]
        while work:
            v = work.pop()
            i = self.indexOf(v)
            if i < 0 or seen[i]:
                continue
            seen[i] = True
            result.append(v)
            work.extend(reversed(self.succ(v)))
        return result

    def postorder(self):
        seen = [False] * len(self.vertices)
        result = []
        stack = []
        def push(v):
            i = self.indexOf(v)
            if i < 0 or seen[i]:
                return
            seen[i] = True
            stack.append((v, iter(self.succ(v))))
        push(self.entry)
        while stack:
            v, children = stack[-1]
            child = next(children, _MISSING)
            if child is not _MISSING:
                push(child)
            else:
                stack.pop()
                result.append(v)
        return result

    def reversePostorder(self):
        return list(reversed(self.postorder()))

    def toDot(self):
        def esc(s):
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
        out = ["digraph G {\n"]
        for i, v in enumerate(self.vertices):
            out.append("  n" + str(i) + " [label=\"" + esc(_show(v)) + "\"];\n")
        for e in self.edges:
            out.append("  n" + str(self.indexOf(e.from_)) + " -> n" + str(self.indexOf(e.to)))
            if e.label is not None:
                out.append(" [label=\"" + esc(e.label) + "\"]")
            out.append(";\n")
        out.append("}")
        return "".join(out)

    def __str__(self):
        return "Graph(" + str(len(self.vertices)) + " vertices, " + str(len(self.edges)) + " edges)"
    __repr__ = __str__

class _Port:
    """A named exit of a flow. Inherited ports are looked up through the enclosing flows."""
    __slots__ = ("name", "inherited")
    def __init__(self, name, inherited):
        self.name = name
        self.inherited = inherited
    def __str__(self):
        return self.name
    __repr__ = __str__

_PORT_NEXT = _Port("next", False)

class _PortRef:
    """The port of a child, e.g. onTrue(cond)."""
    __slots__ = ("node", "port")
    def __init__(self, node, port):
        self.node = node
        self.port = port
    def __str__(self):
        return str(self.port) + "(" + _show(self.node) + ")"

class _ExitVertex(Vertex):
    """The vertex an unbound port of a root leads to, e.g. the end of a function."""
    __slots__ = ("port", "_cache")
    def __init__(self, port):
        self.port = port
        self._cache = {}
    def __str__(self):
        return "exit(" + str(self.port) + ")"
    __repr__ = __str__

class _SyntheticVertex(Vertex):
    """A vertex declared in a flow (vertex name)."""
    __slots__ = ("name", "owner", "_cache")
    def __init__(self, name, owner):
        self.name = name
        self.owner = owner
        self._cache = {}
    def __str__(self):
        return self.name
    __repr__ = __str__

class _FlowGraph:
    """A graph definition: one graph per root node, built from the flows of the nodes below it."""
    def __init__(self, is_root, rule):
        self.is_root = is_root
        self.rule = rule
        self.graphs = {}
        self.owners = {}

    def get(self, root):
        g = self.graphs.get(id(root))
        if g is not None:
            return g[1]
        graph = _FlowBuild(root, self.rule).build()
        self.graphs[id(root)] = (root, graph)
        for v in graph.vertices:
            self.owners[id(v)] = (v, graph)
        return graph

    def of(self, vertex):
        g = self.owners.get(id(vertex))
        if g is not None:
            return g[1]
        current = vertex
        while current is not None:
            if self.is_root(current):
                return self.get(current)
            current = current._parent if isinstance(current, _Node) else None
        return None

    def succ(self, vertex):
        g = self.of(vertex)
        return [] if g is None else g.succ(vertex)

    def pred(self, vertex):
        g = self.of(vertex)
        return [] if g is None else g.pred(vertex)

# flow targets: ("vertex", v), ("child", node), ("port", node, port)

class _FlowScope:
    """Where a flow sends control. The node itself and its declared vertices are vertices of the graph."""
    def __init__(self, node, build):
        self.node = node
        self.build = build
        self.entry_spec = None
        self.own = set()

    def entry(self, target):
        if self.entry_spec is not None:
            raise ForgeError("the flow of " + _show(self.node) + " already has an entry")
        self.entry_spec = self.to_target(target)

    def connect(self, source, target, label):
        to = self.to_target(target)
        if isinstance(source, _Port):
            raise ForgeError("a port of the node itself can not be connected; only ports of children: " + str(source) + "(" + _show(self.node) + ")")
        if isinstance(source, _PortRef):
            if label is not None:
                raise ForgeError("only edges from vertices can be labeled: " + str(source))
            self.build.reference(source.node, self.node)
            self.build.bind(source.node, source.port, to)
        elif self.is_vertex(source):
            self.build.add_vertex(source)
            self.build.edges.append((source, to, label))
        else:
            if label is not None:
                raise ForgeError("only edges from vertices can be labeled: " + _show(source))
            self.build.reference(source, self.node)
            self.build.bind(source, _PORT_NEXT, to)

    def vertex(self, v):
        self.own.add(id(v))
        self.build.add_vertex(v)
        return v

    def bound(self, port):
        return self.build.is_bound(self.node, port)

    def sequence(self, items):
        if not items:
            self.entry(_PORT_NEXT)
            return
        self.entry(items[0])
        for i in range(len(items) - 1):
            self.connect(items[i], items[i + 1], None)
        self.connect(items[-1], _PORT_NEXT, None)

    def is_vertex(self, x):
        return x is self.node or id(x) in self.own

    def to_target(self, x):
        if isinstance(x, _Port):
            return ("port", self.node, x)
        if isinstance(x, _PortRef):
            raise ForgeError("a port of a child can not be a target: " + str(x) + ". Connect it instead: " + str(x) + " -> ...")
        if self.is_vertex(x):
            self.build.add_vertex(x)
            return ("vertex", x)
        self.build.reference(x, self.node)
        return ("child", x)

class _FlowBuild:
    """Runs the flows of all nodes reachable from root and resolves them into a Graph."""
    def __init__(self, root, rule):
        self.root = root
        self.rule = rule
        self.scopes = {}
        self.flow_parent = {}
        self.bindings = {}
        self.queue = []
        self.vertices = []
        self.vertex_set = set()
        self.exits = {}
        self.edges = []
        self.resolved = {}
        self.resolving = set()

    def build(self):
        self.queue.append(self.root)
        while self.queue:
            node = self.queue.pop(0)
            if id(node) in self.scopes:
                continue
            scope = _FlowScope(node, self)
            self.scopes[id(node)] = scope
            if not self.rule(scope, node):
                parent = self.flow_parent.get(id(node))
                raise ForgeError("no flow defined for " + type(node).__name__ +
                                 ("" if parent is None else ", used in the flow of " + type(parent).__name__))
        entry = self.resolve(("child", self.root))
        edges = [Edge(f, self.resolve(t), l) for (f, t, l) in self.edges]
        root = self.root if isinstance(self.root, Vertex) else None
        return Graph(root, entry, [entry] + self.vertices, edges, self.exits)

    def reference(self, child, by):
        if child is self.root or id(child) in self.flow_parent:
            return
        self.flow_parent[id(child)] = by
        self.queue.append(child)

    def bind(self, child, port, target):
        ports = self.bindings.setdefault(id(child), {})
        if id(port) in ports:
            raise ForgeError("port " + str(port) + " of " + _show(child) + " is connected twice")
        ports[id(port)] = target

    def add_vertex(self, v):
        if id(v) not in self.vertex_set:
            self.vertex_set.add(id(v))
            self.vertices.append(v)

    def is_bound(self, node, port):
        if id(port) in self.bindings.get(id(node), {}):
            return True
        if not port.inherited:
            return False
        parent = self.flow_parent.get(id(node))
        return parent is not None and self.is_bound(parent, port)

    def resolve(self, target):
        key = (target[0],) + tuple(id(x) for x in target[1:])
        if key in self.resolved:
            return self.resolved[key]
        if key in self.resolving:
            raise ForgeError("flow cycle without a vertex at " + self.describe(target))
        self.resolving.add(key)
        try:
            if target[0] == "vertex":
                vertex = target[1]
            elif target[0] == "child":
                scope = self.scopes.get(id(target[1]))
                if scope is None:
                    raise ForgeError("no flow for " + _show(target[1]))
                if scope.entry_spec is None:
                    raise ForgeError("the flow of " + _show(target[1]) + " has no entry")
                vertex = self.resolve(scope.entry_spec)
            else:
                vertex = self.resolve_port(target[1], target[2])
            self.resolved[key] = vertex
            return vertex
        finally:
            self.resolving.discard(key)

    def resolve_port(self, node, port):
        target = self.bindings.get(id(node), {}).get(id(port))
        if target is not None:
            return self.resolve(target)
        if node is self.root:
            if port not in self.exits:
                v = _ExitVertex(port)
                self.exits[port] = v
                self.add_vertex(v)
            return self.exits[port]
        parent = self.flow_parent[id(node)]
        if port.inherited:
            return self.resolve_port(parent, port)
        raise ForgeError("port " + str(port) + " of " + _show(node) + " is not connected; connect it in the flow of " + _show(parent))

    def describe(self, target):
        if target[0] == "vertex":
            return _show(target[1])
        if target[0] == "child":
            return "entry of " + _show(target[1])
        return str(target[2]) + "(" + _show(target[1]) + ")"

class Span(_Record):
    """Where a parser read a node from: offset and length in characters (bytes for binary input)."""
    __slots__ = ("source", "line", "column", "offset", "length", "text")
    _fields = ("source", "line", "column", "offset", "length", "text")
    _name = "Span"
    def __init__(self, source, line, column, offset, length, text):
        self.source = source
        self.line = line
        self.column = column
        self.offset = offset
        self.length = length
        self.text = text
    def __str__(self):
        return ("" if self.source is None else self.source + ":") + str(self.line) + ":" + str(self.column)
    __repr__ = __str__

def spanOf(node):
    """Where node was parsed from, or None if no parser created it."""
    return node._span if isinstance(node, _Node) else None

# ==== parser runtime ===============================================================================

class ParseException(Exception):
    """A failed parse: where it failed and what would have been accepted there."""
    def __init__(self, message, offset, line, column, expected, source):
        Exception.__init__(self, message)
        self.message = message
        self.offset = offset
        self.line = line
        self.column = column
        self.expected = expected
        self.source = source

class _Mismatch(Exception):
    pass

_MISMATCH = _Mismatch()

class _Rule:
    def __init__(self, name, body=None):
        self.name = name
        self.body = body
    def run(self, scope, precedence):
        return self.body(scope)

class _AbstractRule(_Rule):
    """A rule made of alternatives and operators with precedence and associativity."""
    def __init__(self, name):
        _Rule.__init__(self, name)
        self.alternatives = []   # (precedence, operand precedence or None, parse)
        self.operators = []      # (precedence, operand precedence, extend)
    def on(self, parse):
        self.alternatives.append((2147483647, None, parse))
    def prefix(self, precedence, parse):
        self.alternatives.append((precedence, precedence, parse))
    def infixLeft(self, precedence, extend):
        self.operators.append((precedence, precedence + 1, extend))
    def infixRight(self, precedence, extend):
        self.operators.append((precedence, precedence, extend))
    def postfix(self, precedence, extend):
        self.operators.append((precedence, precedence, extend))
    def run(self, scope, precedence):
        return scope.climb(self, precedence)

class _Terminal:
    """A regular expression. Literals must not end inside a word (fun does not match funny)."""
    def __init__(self, regex, literal, display):
        self.pattern = _re.compile(regex, _re.ASCII)
        self.literal = literal
        self.display = display

class _Bits:
    def __init__(self, bits, display):
        self.bits = [b == "1" for b in bits]
        self.display = display

class _Label:
    """A label of a rule: assigned while parsing, read by the producer."""
    __slots__ = ("name", "value", "is_set")
    def __init__(self, name):
        self.name = name
        self.is_set = False
        self.value = None
    def set(self, value):
        self.value = value
        self.is_set = True
        return value
    def get(self):
        if not self.is_set:
            raise ForgeError("label '" + self.name + "' was not matched")
        return self.value

def _word_char(c):
    return c.isalnum() or c == "_"

class _Frame:
    __slots__ = ()

class _Memo:
    __slots__ = ("ok", "value", "end", "head")
    def __init__(self, ok, value, end, head):
        self.ok = ok
        self.value = value
        self.end = end
        self.head = head

class _ParseScope:
    def __init__(self, text, data, skip, keywords, source):
        self.text = text
        self.data = data
        self.skip = skip
        self.keywords = keywords
        self.source = source
        self.unit = 1 if data is None else 8
        self.size = len(text) * self.unit
        self.pos = 0
        self.memo = {}
        self.stack = []
        self.left_recursive = set()
        self.involved = set()
        self.furthest = -1
        self.expected = []
        self.operators = []

    # ---- text

    def terminal(self, t):
        self.skip_ignored()
        if self.pos % self.unit != 0:
            self.fail(t.display)
        start = self.pos // self.unit
        m = t.pattern.match(self.text, start)
        if m is None:
            self.fail(t.display)
        value = m.group(0)
        end = m.end()
        if t.literal is not None:
            if t.literal and _word_char(t.literal[-1]) and end < len(self.text) and _word_char(self.text[end]):
                self.fail(t.display)
        elif value in self.keywords:
            self.fail(t.display)
        self.pos = end * self.unit
        return value

    def skip_ignored(self):
        if self.skip is None or self.pos % self.unit != 0:
            return
        while True:
            m = self.skip.match(self.text, self.pos // self.unit)
            if m is None or m.end() == self.pos // self.unit:
                return
            self.pos = m.end() * self.unit

    # ---- binary

    def bits(self, pattern):
        self.require_binary()
        width = len(pattern.bits)
        if self.pos + width > self.size:
            self.fail(pattern.display)
        for i in range(width):
            if self.bit_at(self.pos + i) != pattern.bits[i]:
                self.fail(pattern.display)
        self.pos += width
        return pattern.display

    def uint(self, width):
        self.require_binary()
        if width < 0 or width > 32:
            raise ForgeError("width must be between 0 and 32")
        if self.pos + width > self.size:
            self.fail(str(width) + " more bits")
        value = 0
        for i in range(width):
            value = (value << 1) | (1 if self.bit_at(self.pos + i) else 0)
        self.pos += width
        return _i32(value)

    def sint(self, width):
        if width < 1 or width > 32:
            raise ForgeError("width must be between 1 and 32")
        value = self.uint(width) & 0xFFFFFFFF
        if width < 32 and (value >> (width - 1)) & 1 == 1:
            value -= 1 << width
        return _i32(value)

    def bit(self):
        return self.uint(1) == 1

    def u1(self):
        return self.uint(8)

    def u2(self):
        return self.uint(16)

    def u4(self):
        return self.uint(32)

    def bytes(self, count):
        self.require_binary()
        if self.pos + count * 8 > self.size:
            self.fail(str(count) + " more bytes")
        return [self.uint(8) for _ in range(count)]

    def bit_at(self, p):
        return (self.data[p >> 3] >> (7 - (p & 7))) & 1 == 1

    def require_binary(self):
        if self.data is None:
            raise ForgeError("bit and byte primitives need binary input")

    # ---- failure and combinators

    def fail(self, expected):
        if self.pos > self.furthest:
            self.furthest = self.pos
            self.expected = []
        if self.pos == self.furthest and expected not in self.expected:
            self.expected.append(expected)
        raise _MISMATCH

    def optional(self, body):
        start = self.pos
        try:
            return body()
        except _Mismatch:
            self.pos = start
            return None

    def many(self, body):
        result = []
        while True:
            start = self.pos
            try:
                result.append(body())
            except _Mismatch:
                self.pos = start
                return result
            if self.pos == start:
                return result

    def some(self, body):
        first = body()
        return [first] + self.many(body)

    def repeat(self, count, body):
        return [body() for _ in range(count)]

    def and_(self, body):
        start = self.pos
        try:
            body()
        finally:
            self.pos = start
        return None

    def not_(self, description, body):
        start = self.pos
        try:
            body()
            matched = True
        except _Mismatch:
            matched = False
        finally:
            self.pos = start
        if matched:
            self.fail("not " + description)
        return None

    def one_of(self, *alternatives):
        start = self.pos
        for alternative in alternatives:
            try:
                return alternative()
            except _Mismatch:
                self.pos = start
        raise _MISMATCH

    def seq(self, items, value_index):
        """A sequence: the parsers run in order; items are (parse, label or None)."""
        values = []
        for parse, label in items:
            v = parse()
            if label is not None:
                label.set(v)
            values.append(v)
        if value_index is None:
            return None
        if value_index == -1:
            return values
        return values[value_index]

    # ---- abstract rules

    def operand(self):
        context = self.operators[-1] if self.operators else None
        if context is None:
            raise ForgeError("an operand outside of an operator")
        return self.apply(context[0], context[1])

    def in_operator(self, rule, operand_precedence, block):
        self.operators.append(None if operand_precedence is None else (rule, operand_precedence))
        try:
            return block()
        finally:
            self.operators.pop()

    def climb(self, rule, min_precedence):
        start = self.pos
        left = _MISSING
        for _, operand_precedence, parse in rule.alternatives:
            try:
                left = self.in_operator(rule, operand_precedence, lambda: parse(self))
                self.record(left, start, self.pos)
                break
            except _Mismatch:
                self.pos = start
        if left is _MISSING:
            if not rule.alternatives:
                raise ForgeError("abstract rule '" + rule.name + "' has no alternatives")
            raise _MISMATCH
        while True:
            extended_any = False
            for precedence, operand_precedence, extend in rule.operators:
                if precedence < min_precedence:
                    continue
                before = self.pos
                try:
                    current = left
                    extended = self.in_operator(rule, operand_precedence, lambda: extend(self, current))
                    if self.pos == before:
                        continue
                    left = extended
                    self.record(extended, start, self.pos)
                    extended_any = True
                    break
                except _Mismatch:
                    self.pos = before
            if not extended_any:
                return left

    # ---- engine

    def apply(self, rule, precedence=-2147483648):
        """Applies rule with memoization and left recursion (seed growing)."""
        start = self.pos
        key = (id(rule), start, precedence)
        m = self.memo.get(key)
        if m is not None:
            if m.head is not None:
                self.left_recursive.add(m.head)
                index = len(self.stack) - 1 - self.stack[::-1].index(m.head)
                for f in self.stack[index + 1:]:
                    self.involved.add(f)
            if not m.ok:
                raise _MISMATCH
            self.pos = m.end
            return m.value
        frame = _Frame()
        m = _Memo(False, None, start, frame)
        self.memo[key] = m
        self.stack.append(frame)
        self.operators.append(None)
        try:
            self.attempt(rule, precedence, start, m)
            if frame in self.left_recursive:
                while m.ok:
                    value, end = m.value, m.end
                    if not self.attempt(rule, precedence, start, m) or m.end <= end:
                        m.ok, m.value, m.end = True, value, end
                        break
        finally:
            self.operators.pop()
            self.stack.pop()
            m.head = None
            if frame in self.involved:
                del self.memo[key]
            self.left_recursive.discard(frame)
            self.involved.discard(frame)
        if not m.ok:
            self.pos = start
            raise _MISMATCH
        self.pos = m.end
        self.record(m.value, start, m.end)
        return m.value

    def attempt(self, rule, precedence, start, into):
        self.pos = start
        try:
            into.value = rule.run(self, precedence)
            into.end = self.pos
            into.ok = True
        except _Mismatch:
            into.ok = False
        return into.ok

    def record(self, value, start, end):
        if not isinstance(value, _Node) or value._span is not None:
            return
        frm = self.skipped_from(start) // self.unit
        to = (end + self.unit - 1) // self.unit
        if self.data is None:
            value._span = Span(self.source, self.line_of(frm), self.column_of(frm), frm, to - frm, self.text[frm:max(frm, to)])
        else:
            value._span = Span(self.source, 0, 0, frm, to - frm, "")

    def skipped_from(self, frm):
        if self.skip is None or frm % self.unit != 0:
            return frm
        p = frm
        while True:
            m = self.skip.match(self.text, p // self.unit)
            if m is None or m.end() == p // self.unit:
                return p
            p = m.end() * self.unit

    def line_of(self, offset):
        return self.text.count("\n", 0, min(offset, len(self.text))) + 1

    def column_of(self, offset):
        return offset - (self.text.rfind("\n", 0, offset) + 1) + 1

    def parse_all(self, rule):
        try:
            result = self.apply(rule)
            self.skip_ignored()
            if self.pos == self.size:
                return result
            if self.pos > self.furthest:
                self.furthest = self.pos
                self.expected = []
            if self.pos == self.furthest and "end of input" not in self.expected:
                self.expected.append("end of input")
        except _Mismatch:
            pass
        raise self.error()

    def error(self):
        at = max(self.furthest, 0)
        described = " or ".join(self.expected)
        prefix = "" if self.source is None else self.source + ":"
        if self.data is None:
            found = "'" + self.text[at] + "'" if at < len(self.text) else "end of input"
            line = self.line_of(at)
            column = self.column_of(at)
            return ParseException(prefix + str(line) + ":" + str(column) + ": expected " + described + ", found " + found,
                                  at, line, column, list(self.expected), self.source)
        byte, bit = at // 8, at % 8
        where = "byte " + str(byte) if bit == 0 else "byte " + str(byte) + ", bit " + str(bit)
        found = "0x%02X" % self.data[byte] if byte < len(self.data) else "end of input"
        return ParseException(prefix + where + ": expected " + described + ", found " + found, at, 0, 0, list(self.expected), self.source)

def _parse_text(rule, text, skip, keywords, source):
    return _ParseScope(text, None, skip, keywords, source).parse_all(rule)

def _parse_binary(rule, data, skip, keywords, source):
    data = bytes(b & 0xFF for b in data)
    return _ParseScope(data.decode("latin-1"), data, skip, keywords, source).parse_all(rule)

# ==== end of Forge runtime =========================================================================
