    // ==== Forge runtime =========================================================================
    // Attribute caches, fixpoints of circular attributes, graphs, flows and the parser engine.

    /** An error while evaluating: a missing value, no matching case, an attribute that depends on itself. */
    public static final class ForgeError extends RuntimeException {
        public ForgeError(String message) { super(message); }
    }

    public interface __Fn0 { Object get(); }
    public interface __Fn1 { Object apply(Object a); }
    public interface __Fn2 { Object apply(Object a, Object b); }

    static Object __panic(String message) { throw new ForgeError(message); }

    static final Object __NOMATCH = new Object() { @Override public String toString() { return "<no match>"; } };
    static final Object __MISSING = new Object();

    static Object __match(Object subject, __Fn0[] cases, __Fn0 alternative) {
        for (__Fn0 c : cases) {
            Object r = c.get();
            if (r != __NOMATCH) return r;
        }
        if (alternative != null) return alternative.get();
        throw new ForgeError("no match for " + subject);
    }

    static Object __let(Object value, __Fn1 body) { return body.apply(value); }
    static Object __then(Object first, __Fn0 next) { return next.get(); }
    static boolean __bool(Object v) { return (Boolean) v; }
    static int __int(Object v) { return (Integer) v; }
    static float __float(Object v) { return (Float) v; }

    static Object __nn(Object v) {
        if (v == null) throw new NullPointerException("null value where a value is required (!!)");
        return v;
    }
    static Object __elvis(Object v, __Fn0 alternative) { return v == null ? alternative.get() : v; }

    @SuppressWarnings("unchecked")
    static java.util.List<Object> __l(Object v) { return (java.util.List<Object>) v; }
    @SuppressWarnings("unchecked")
    static java.util.Map<Object, Object> __m(Object v) { return (java.util.Map<Object, Object>) v; }

    @SuppressWarnings("unchecked")
    static java.util.Set<Object> __s(Object v) { return (java.util.Set<Object>) v; }
    @SuppressWarnings("unchecked")
    static Iterable<Object> __iter(Object v) { return (Iterable<Object>) v; }

    static java.util.List<Object> __list(Object... items) { return new java.util.ArrayList<>(java.util.Arrays.asList(items)); }

    /** A position for [size] elements: below 0 counts from the end. */
    static int __position(int i, int size) {
        int k = i < 0 ? i + size : i;
        if (k < 0 || k >= size) throw new IndexOutOfBoundsException("index " + i + " out of bounds for length " + size);
        return k;
    }
    /** A bound of a slice: below 0 counts from the end, clamped to 0..size; null is the default. */
    static int __bound(Object b, int size, int dflt) {
        int k = b == null ? dflt : (Integer) b < 0 ? (Integer) b + size : (Integer) b;
        return Math.max(0, Math.min(size, k));
    }
    static Object __get(Object list, Object index) { java.util.List<Object> l = __l(list); return l.get(__position((Integer) index, l.size())); }
    static Object __slice(Object v, Object lo, Object hi) {
        if (v instanceof String) {
            String s = (String) v;
            int a = __bound(lo, s.length(), 0), b = __bound(hi, s.length(), s.length());
            return a >= b ? "" : s.substring(a, b);
        }
        java.util.List<Object> l = __l(v);
        int a = __bound(lo, l.size(), 0), b = __bound(hi, l.size(), l.size());
        return a >= b ? new java.util.ArrayList<>() : new java.util.ArrayList<>(l.subList(a, b));
    }
    static String __charAt(Object s, Object i) { String t = (String) s; int k = __position((Integer) i, t.length()); return t.substring(k, k + 1); }
    static java.util.List<Object> __range(Object a, Object b) {
        java.util.ArrayList<Object> r = new java.util.ArrayList<>();
        for (int i = (Integer) a; i < (Integer) b; i++) r.add(i);
        return r;
    }
    static java.util.Set<Object> __set(Object... items) { return new java.util.LinkedHashSet<>(java.util.Arrays.asList(items)); }
    static java.util.Set<Object> __union(Object a, Object b) { java.util.LinkedHashSet<Object> r = new java.util.LinkedHashSet<>(__s(a)); r.addAll(__s(b)); return r; }
    static java.util.Set<Object> __setRemove(Object a, Object x) { java.util.LinkedHashSet<Object> r = new java.util.LinkedHashSet<>(__s(a)); r.remove(x); return r; }
    static java.util.Map<Object, Object> __mapRemove(Object m, Object k) { java.util.LinkedHashMap<Object, Object> r = new java.util.LinkedHashMap<>(__m(m)); r.remove(k); return r; }
    static java.util.List<Object> __reversed(Object l) { java.util.ArrayList<Object> r = new java.util.ArrayList<>(__l(l)); java.util.Collections.reverse(r); return r; }
    static Integer __toInt(Object s) {
        String t = (String) s;
        if (!t.matches("[+-]?[0-9]+")) throw new ForgeError("not an int: \"" + t + "\"");
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { throw new ForgeError("not an int: \"" + t + "\""); }
    }
    static Float __toFloat(Object s) {
        String t = (String) s;
        if (!t.matches("[+-]?([0-9]+[.]?[0-9]*|[.][0-9]+)([eE][+-]?[0-9]+)?")) throw new ForgeError("not a float: \"" + t + "\"");
        return Float.parseFloat(t);
    }
    static java.util.List<Object> __split(Object s, Object sep) {
        String t = (String) s, p = (String) sep;
        if (p.isEmpty()) throw new ForgeError("the separator of split is empty");
        java.util.ArrayList<Object> r = new java.util.ArrayList<>();
        int from = 0;
        while (true) {
            int i = t.indexOf(p, from);
            if (i < 0) { r.add(t.substring(from)); return r; }
            r.add(t.substring(from, i));
            from = i + p.length();
        }
    }
    static String __replace(Object s, Object old, Object replacement) {
        if (((String) old).isEmpty()) throw new ForgeError("the text to replace is empty");
        return ((String) s).replace((String) old, (String) replacement);
    }
    static Integer __code(Object s, Object i) { String t = (String) s; return (int) t.charAt(__position((Integer) i, t.length())); }
    static String __char(Object code) { return new String(Character.toChars((Integer) code)); }
    static java.util.List<Object> __utf8(Object s) {
        java.util.ArrayList<Object> r = new java.util.ArrayList<>();
        for (byte b : ((String) s).getBytes(java.nio.charset.StandardCharsets.UTF_8)) r.add(b & 0xFF);
        return r;
    }
    static String __unescape(Object s) {
        String t = (String) s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length()) {
                char e = t.charAt(++i);
                sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == '0' ? '\0' : e);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    static String __join(Object l, Object sep) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object x : __l(l)) { if (!first) sb.append((String) sep); first = false; sb.append((String) x); }
        return sb.toString();
    }
    static java.util.List<Object> __concat(Object a, Object b) {
        java.util.ArrayList<Object> r = new java.util.ArrayList<>(__l(a));
        r.addAll(__l(b));
        return r;
    }
    static java.util.List<Object> __append(Object a, Object x) {
        java.util.ArrayList<Object> r = new java.util.ArrayList<>(__l(a));
        r.add(x);
        return r;
    }
    static java.util.List<Object> __tail(Object list) {
        java.util.List<Object> l = __l(list);
        return new java.util.ArrayList<>(l.subList(1, l.size()));
    }
    static java.util.List<Object> __mapList(Object list, __Fn1 f) {
        java.util.ArrayList<Object> r = new java.util.ArrayList<>();
        for (Object x : __iter(list)) r.add(f.apply(x));
        return r;
    }
    static Object __fold(Object list, Object acc, __Fn2 f) {
        for (Object x : __iter(list)) acc = f.apply(acc, x);
        return acc;
    }

    static java.util.Map<Object, Object> __map(Object... kv) {
        java.util.LinkedHashMap<Object, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
    static Object __at(Object map, Object key) {
        java.util.Map<Object, Object> m = __m(map);
        if (!m.containsKey(key)) throw new java.util.NoSuchElementException("Key " + key + " is missing in the map.");
        return m.get(key);
    }
    static java.util.Map<Object, Object> __merge(Object a, Object b) {
        java.util.LinkedHashMap<Object, Object> m = new java.util.LinkedHashMap<>(__m(a));
        m.putAll(__m(b));
        return m;
    }
    static java.util.Map<Object, Object> __put(Object a, Object k, Object v) {
        java.util.LinkedHashMap<Object, Object> m = new java.util.LinkedHashMap<>(__m(a));
        m.put(k, v);
        return m;
    }

    static int __f2i(Object v) {
        float f = (Float) v;
        if (Float.isNaN(f)) return 0;
        return (int) f;
    }

    static <T> T __cast(Object v, Class<T> type, String name) {
        if (v != null && !type.isInstance(v)) throw new ClassCastException("can not cast " + v + " to " + name);
        return type.cast(v);
    }

    // ---- nodes --------------------------------------------------------------------------------

    /** A vertex of a graph: a node, or a synthetic or exit vertex of a flow. */
    public interface Vertex {}

    /** Everything attributes can be computed for: nodes and the vertices of flows. */
    public static abstract class __Obj implements Vertex {
        final int __kind;
        final java.util.HashMap<Object, Object> __cache = new java.util.HashMap<>();
        __Obj(int kind) { this.__kind = kind; }
    }

    public static abstract class __Node extends __Obj {
        public __Node __parent;
        /** Set by parsers: where the node was read from. */
        public Span __span;
        __Node(int kind) { super(kind); }

        abstract java.util.List<__Node> __children();

        void __adopt() { for (__Node c : __children()) c.__parent = this; }

        public __Node __root() {
            __Node n = this;
            while (n.__parent != null) n = n.__parent;
            return n;
        }

        static void __addChildren(java.util.List<__Node> into, Object value) {
            if (value instanceof __Node) into.add((__Node) value);
            else if (value instanceof java.util.List) for (Object x : (java.util.List<?>) value) if (x instanceof __Node) into.add((__Node) x);
        }
    }

    /** The nodes of the tree below node (including it), parents before children. */
    static java.util.List<__Node> __preorder(__Node node) {
        java.util.ArrayList<__Node> result = new java.util.ArrayList<>();
        java.util.ArrayDeque<__Node> work = new java.util.ArrayDeque<>();
        work.addLast(node);
        while (!work.isEmpty()) {
            __Node n = work.removeLast();
            result.add(n);
            java.util.List<__Node> children = n.__children();
            for (int i = children.size() - 1; i >= 0; i--) work.addLast(children.get(i));
        }
        return result;
    }

    // ---- caches and fixpoints -----------------------------------------------------------------

    static final class __Cycle {
        int iteration = 0;
        boolean changed = false;
        final java.util.ArrayList<__Circular> attributes = new java.util.ArrayList<>();
        void involve(__Circular a) {
            for (__Circular x : attributes) if (x == a) return;
            attributes.add(a);
        }
    }

    static __Cycle __cycle = null;

    /** Values of one syn, inh or coll attribute, per node. Nothing is cached while a fixpoint is computed. */
    static final class __Cache {
        final String attribute;
        final Object busy = new Object();
        __Cache(String attribute) { this.attribute = attribute; }

        Object get(Object node, __Fn0 compute) {
            java.util.HashMap<Object, Object> cache = ((__Obj) node).__cache;
            if (cache.containsKey(this)) return cache.get(this);
            if (cache.containsKey(busy)) {
                throw new ForgeError("attribute '" + attribute + "' of " + node + " depends on itself; declare it as circular");
            }
            cache.put(busy, Boolean.TRUE);
            try {
                Object value = compute.get();
                if (__cycle == null) cache.put(this, value);
                return value;
            } finally {
                cache.remove(busy);
            }
        }
    }

    /** A circular attribute, computed as a fixpoint starting at its initial value. */
    static final class __Circular {
        java.util.IdentityHashMap<Object, Object> approx = new java.util.IdentityHashMap<>();
        java.util.IdentityHashMap<Object, Integer> visited = new java.util.IdentityHashMap<>();

        Object get(Object node, Object initial, __Fn0 body) {
            java.util.HashMap<Object, Object> cache = ((__Obj) node).__cache;
            if (cache.containsKey(this)) return cache.get(this);
            __Cycle cycle = __cycle;
            if (cycle != null) {
                cycle.involve(this);
                Integer seen = visited.get(node);
                if (seen != null && seen == cycle.iteration) return approxOf(node, initial);
                return step(node, initial, body, cycle);
            }
            __Cycle c = new __Cycle();
            __cycle = c;
            try {
                c.involve(this);
                do {
                    c.changed = false;
                    c.iteration++;
                    step(node, initial, body, c);
                } while (c.changed);
                for (__Circular a : c.attributes) a.finish(c.iteration);
                return cache.get(this);
            } finally {
                __cycle = null;
                for (__Circular a : c.attributes) {
                    a.approx = new java.util.IdentityHashMap<>();
                    a.visited = new java.util.IdentityHashMap<>();
                }
            }
        }

        Object approxOf(Object node, Object initial) { return approx.containsKey(node) ? approx.get(node) : initial; }

        Object step(Object node, Object initial, __Fn0 body, __Cycle cycle) {
            visited.put(node, cycle.iteration);
            Object old = approxOf(node, initial);
            Object value = body.get();
            if (!java.util.Objects.equals(value, old) || !approx.containsKey(node)) {
                if (!java.util.Objects.equals(value, old)) cycle.changed = true;
                approx.put(node, value);
            }
            return value;
        }

        void finish(int last) {
            for (java.util.Map.Entry<Object, Integer> e : visited.entrySet()) {
                if (e.getValue() == last) ((__Obj) e.getKey()).__cache.put(this, approx.get(e.getKey()));
            }
        }
    }

    // ---- graphs -------------------------------------------------------------------------------

    /** A directed edge. */
    public static final class Edge {
        public final Vertex from;
        public final Vertex to;
        public final String label;
        public Edge(Vertex from, Vertex to, String label) { this.from = from; this.to = to; this.label = label; }
        @Override public String toString() { return label == null ? from + " -> " + to : from + " -[" + label + "]-> " + to; }
    }

    /** A graph from edges, e.g. a call graph. The vertices are entry and the endpoints of edges. */
    public static Graph graphOf(Vertex entry, java.util.List<Object> edges) {
        java.util.ArrayList<Object> vertices = new java.util.ArrayList<>();
        vertices.add(entry);
        for (Object e : edges) { vertices.add(((Edge) e).from); vertices.add(((Edge) e).to); }
        return new Graph(null, entry, vertices, edges, new java.util.LinkedHashMap<>());
    }

    /** A directed graph. Vertices are compared by identity. */
    public static final class Graph {
        public final Vertex root;
        public final Vertex entry;
        public final java.util.List<Object> vertices;
        public final java.util.List<Object> edges;
        private final java.util.Map<__Port, Vertex> exits;
        private final java.util.IdentityHashMap<Object, Integer> index = new java.util.IdentityHashMap<>();
        private final java.util.ArrayList<java.util.ArrayList<Object>> outgoing = new java.util.ArrayList<>();
        private final java.util.ArrayList<java.util.ArrayList<Object>> incoming = new java.util.ArrayList<>();

        Graph(Vertex root, Vertex entry, java.util.List<Object> vertices, java.util.List<Object> edges, java.util.Map<__Port, Vertex> exits) {
            this.root = root;
            this.entry = entry;
            this.edges = new java.util.ArrayList<>(edges);
            this.exits = exits;
            java.util.ArrayList<Object> distinct = new java.util.ArrayList<>();
            java.util.ArrayList<Object> all = new java.util.ArrayList<>(vertices);
            for (Object e : edges) { all.add(((Edge) e).from); all.add(((Edge) e).to); }
            for (Object v : all) if (!index.containsKey(v)) { index.put(v, distinct.size()); distinct.add(v); }
            this.vertices = distinct;
            for (int i = 0; i < distinct.size(); i++) { outgoing.add(new java.util.ArrayList<>()); incoming.add(new java.util.ArrayList<>()); }
            for (Object e : edges) {
                outgoing.get(index.get(((Edge) e).from)).add(e);
                incoming.get(index.get(((Edge) e).to)).add(e);
            }
        }

        public boolean contains(Object v) { return index.containsKey(v); }
        public int indexOf(Object v) { Integer i = index.get(v); return i == null ? -1 : i; }
        public java.util.List<Object> outEdges(Object v) { Integer i = index.get(v); return i == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(outgoing.get(i)); }
        public java.util.List<Object> inEdges(Object v) { Integer i = index.get(v); return i == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(incoming.get(i)); }
        public java.util.List<Object> succ(Object v) { java.util.ArrayList<Object> r = new java.util.ArrayList<>(); for (Object e : outEdges(v)) r.add(((Edge) e).to); return r; }
        public java.util.List<Object> pred(Object v) { java.util.ArrayList<Object> r = new java.util.ArrayList<>(); for (Object e : inEdges(v)) r.add(((Edge) e).from); return r; }

        public Vertex exit(String port) {
            for (java.util.Map.Entry<__Port, Vertex> e : exits.entrySet()) if (e.getKey().name.equals(port)) return e.getValue();
            return null;
        }

        public java.util.List<Object> reachable() {
            boolean[] seen = new boolean[vertices.size()];
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            java.util.ArrayDeque<Object> work = new java.util.ArrayDeque<>();
            work.addLast(entry);
            while (!work.isEmpty()) {
                Object v = work.removeLast();
                int i = indexOf(v);
                if (i < 0 || seen[i]) continue;
                seen[i] = true;
                result.add(v);
                java.util.List<Object> s = succ(v);
                for (int k = s.size() - 1; k >= 0; k--) work.addLast(s.get(k));
            }
            return result;
        }

        public java.util.List<Object> postorder() {
            boolean[] seen = new boolean[vertices.size()];
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
            int i0 = indexOf(entry);
            if (i0 >= 0) { seen[i0] = true; stack.addLast(new Object[]{entry, succ(entry).iterator()}); }
            while (!stack.isEmpty()) {
                Object[] top = stack.peekLast();
                @SuppressWarnings("unchecked") java.util.Iterator<Object> children = (java.util.Iterator<Object>) top[1];
                if (children.hasNext()) {
                    Object c = children.next();
                    int i = indexOf(c);
                    if (i >= 0 && !seen[i]) { seen[i] = true; stack.addLast(new Object[]{c, succ(c).iterator()}); }
                } else {
                    stack.removeLast();
                    result.add(top[0]);
                }
            }
            return result;
        }

        public java.util.List<Object> reversePostorder() {
            java.util.List<Object> r = postorder();
            java.util.Collections.reverse(r);
            return r;
        }

        public String toDot() {
            StringBuilder sb = new StringBuilder("digraph G {\n");
            for (int i = 0; i < vertices.size(); i++) sb.append("  n").append(i).append(" [label=\"").append(esc(String.valueOf(vertices.get(i)))).append("\"];\n");
            for (Object o : edges) {
                Edge e = (Edge) o;
                sb.append("  n").append(indexOf(e.from)).append(" -> n").append(indexOf(e.to));
                if (e.label != null) sb.append(" [label=\"").append(esc(e.label)).append("\"]");
                sb.append(";\n");
            }
            return sb.append("}").toString();
        }

        private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

        @Override public String toString() { return "Graph(" + vertices.size() + " vertices, " + edges.size() + " edges)"; }
    }

    /** A named exit of a flow. Inherited ports are looked up through the enclosing flows. */
    public static final class __Port {
        final String name;
        final boolean inherited;
        __Port(String name, boolean inherited) { this.name = name; this.inherited = inherited; }
        @Override public String toString() { return name; }
    }

    static final __Port __PORT_NEXT = new __Port("next", false);

    /** The port of a child, e.g. onTrue(cond). */
    static final class __PortRef {
        final Object node;
        final __Port port;
        __PortRef(Object node, __Port port) { this.node = node; this.port = port; }
        @Override public String toString() { return port + "(" + node + ")"; }
    }

    /** The vertex an unbound port of a root leads to, e.g. the end of a function. */
    public static final class __ExitVertex extends __Obj {
        final __Port port;
        __ExitVertex(__Port port) { super(__KIND_EXIT); this.port = port; }
        @Override public String toString() { return "exit(" + port + ")"; }
    }

    /** A vertex declared in a flow (vertex name). */
    public static final class __SyntheticVertex extends __Obj {
        final String name;
        final Object owner;
        __SyntheticVertex(String name, Object owner) { super(__KIND_SYNTHETIC); this.name = name; this.owner = owner; }
        @Override public String toString() { return name; }
    }

    interface __FlowRule { boolean run(__FlowScope s, Object node); }
    interface __IsRoot { boolean test(Object node); }

    /** A graph definition: one graph per root node, built from the flows of the nodes below it. */
    public static final class __FlowGraph {
        private final __IsRoot isRoot;
        private final __FlowRule rule;
        private final java.util.IdentityHashMap<Object, Graph> graphs = new java.util.IdentityHashMap<>();
        private final java.util.IdentityHashMap<Object, Graph> owners = new java.util.IdentityHashMap<>();
        __FlowGraph(__IsRoot isRoot, __FlowRule rule) { this.isRoot = isRoot; this.rule = rule; }

        public Graph get(Object root) {
            Graph g = graphs.get(root);
            if (g != null) return g;
            Graph graph = new __FlowBuild(root, rule).build();
            graphs.put(root, graph);
            for (Object v : graph.vertices) owners.put(v, graph);
            return graph;
        }

        public Graph of(Object vertex) {
            Graph g = owners.get(vertex);
            if (g != null) return g;
            Object current = vertex;
            while (current != null) {
                if (isRoot.test(current)) return get(current);
                current = current instanceof __Node ? ((__Node) current).__parent : null;
            }
            return null;
        }

        public java.util.List<Object> succ(Object v) { Graph g = of(v); return g == null ? new java.util.ArrayList<>() : g.succ(v); }
        public java.util.List<Object> pred(Object v) { Graph g = of(v); return g == null ? new java.util.ArrayList<>() : g.pred(v); }
    }

    /** Where a flow sends control: a vertex, a child (its entry) or a port of a node. */
    static final class __Target {
        final int kind; // 0 vertex, 1 child, 2 port
        final Object node;
        final __Port port;
        __Target(int kind, Object node, __Port port) { this.kind = kind; this.node = node; this.port = port; }
    }

    static final class __EdgeSpec {
        final Object from; final __Target to; final String label;
        __EdgeSpec(Object from, __Target to, String label) { this.from = from; this.to = to; this.label = label; }
    }

    /** Where a flow sends control. The node itself and its declared vertices are vertices of the graph. */
    public static final class __FlowScope {
        final Object node;
        final __FlowBuild build;
        __Target entrySpec;
        final java.util.IdentityHashMap<Object, Boolean> own = new java.util.IdentityHashMap<>();
        __FlowScope(Object node, __FlowBuild build) { this.node = node; this.build = build; }

        void entry(Object target) {
            if (entrySpec != null) throw new ForgeError("the flow of " + node + " already has an entry");
            entrySpec = toTarget(target);
        }

        void connect(Object source, Object target, Object label) {
            __Target to = toTarget(target);
            if (source instanceof __Port) throw new ForgeError("a port of the node itself can not be connected; only ports of children: " + source + "(" + node + ")");
            if (source instanceof __PortRef) {
                if (label != null) throw new ForgeError("only edges from vertices can be labeled: " + source);
                __PortRef ref = (__PortRef) source;
                build.reference(ref.node, node);
                build.bind(ref.node, ref.port, to);
            } else if (isVertex(source)) {
                build.addVertex(source);
                build.edges.add(new __EdgeSpec(source, to, (String) label));
            } else {
                if (label != null) throw new ForgeError("only edges from vertices can be labeled: " + source);
                build.reference(source, node);
                build.bind(source, __PORT_NEXT, to);
            }
        }

        Object vertex(Object v) { own.put(v, Boolean.TRUE); build.addVertex(v); return v; }
        boolean bound(Object port) { return build.isBound(node, (__Port) port); }

        void sequence(Object list) {
            java.util.List<Object> items = __l(list);
            if (items.isEmpty()) { entry(__PORT_NEXT); return; }
            entry(items.get(0));
            for (int i = 0; i < items.size() - 1; i++) connect(items.get(i), items.get(i + 1), null);
            connect(items.get(items.size() - 1), __PORT_NEXT, null);
        }

        boolean isVertex(Object x) { return x == node || own.containsKey(x); }

        __Target toTarget(Object x) {
            if (x instanceof __Port) return new __Target(2, node, (__Port) x);
            if (x instanceof __PortRef) throw new ForgeError("a port of a child can not be a target: " + x + ". Connect it instead: " + x + " -> ...");
            if (isVertex(x)) { build.addVertex(x); return new __Target(0, x, null); }
            build.reference(x, node);
            return new __Target(1, x, null);
        }
    }

    /** Runs the flows of all nodes reachable from root and resolves them into a Graph. */
    static final class __FlowBuild {
        final Object root;
        final __FlowRule rule;
        final java.util.IdentityHashMap<Object, __FlowScope> scopes = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<Object, Object> flowParent = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<Object, java.util.LinkedHashMap<__Port, __Target>> bindings = new java.util.IdentityHashMap<>();
        final java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        final java.util.ArrayList<Object> vertices = new java.util.ArrayList<>();
        final java.util.IdentityHashMap<Object, Boolean> vertexSet = new java.util.IdentityHashMap<>();
        final java.util.LinkedHashMap<__Port, Vertex> exits = new java.util.LinkedHashMap<>();
        final java.util.ArrayList<__EdgeSpec> edges = new java.util.ArrayList<>();
        final java.util.IdentityHashMap<__Target, Vertex> resolved = new java.util.IdentityHashMap<>();
        final java.util.IdentityHashMap<__Target, Boolean> resolving = new java.util.IdentityHashMap<>();

        __FlowBuild(Object root, __FlowRule rule) { this.root = root; this.rule = rule; }

        Graph build() {
            queue.addLast(root);
            while (!queue.isEmpty()) {
                Object node = queue.removeFirst();
                if (scopes.containsKey(node)) continue;
                __FlowScope scope = new __FlowScope(node, this);
                scopes.put(node, scope);
                if (!rule.run(scope, node)) {
                    Object parent = flowParent.get(node);
                    throw new ForgeError("no flow defined for " + node.getClass().getSimpleName() + (parent == null ? "" : ", used in the flow of " + parent.getClass().getSimpleName()));
                }
            }
            Vertex entry = resolve(new __Target(1, root, null));
            java.util.ArrayList<Object> resolvedEdges = new java.util.ArrayList<>();
            for (__EdgeSpec e : edges) resolvedEdges.add(new Edge((Vertex) e.from, resolve(e.to), e.label));
            java.util.ArrayList<Object> all = new java.util.ArrayList<>();
            all.add(entry);
            all.addAll(vertices);
            return new Graph(root instanceof Vertex ? (Vertex) root : null, entry, all, resolvedEdges, exits);
        }

        void reference(Object child, Object by) {
            if (child == root || flowParent.containsKey(child)) return;
            flowParent.put(child, by);
            queue.addLast(child);
        }

        void bind(Object child, __Port port, __Target target) {
            java.util.LinkedHashMap<__Port, __Target> ports = bindings.computeIfAbsent(child, k -> new java.util.LinkedHashMap<>());
            if (ports.containsKey(port)) throw new ForgeError("port " + port + " of " + child + " is connected twice");
            ports.put(port, target);
        }

        void addVertex(Object v) {
            if (!vertexSet.containsKey(v)) { vertexSet.put(v, Boolean.TRUE); vertices.add(v); }
        }

        boolean isBound(Object node, __Port port) {
            java.util.LinkedHashMap<__Port, __Target> ports = bindings.get(node);
            if (ports != null && ports.containsKey(port)) return true;
            if (!port.inherited) return false;
            Object parent = flowParent.get(node);
            return parent != null && isBound(parent, port);
        }

        Vertex resolve(__Target target) {
            Vertex done = resolved.get(target);
            if (done != null) return done;
            if (resolving.containsKey(target)) throw new ForgeError("flow cycle without a vertex at " + describe(target));
            resolving.put(target, Boolean.TRUE);
            try {
                Vertex vertex;
                if (target.kind == 0) vertex = (Vertex) target.node;
                else if (target.kind == 1) {
                    __FlowScope scope = scopes.get(target.node);
                    if (scope == null) throw new ForgeError("no flow for " + target.node);
                    if (scope.entrySpec == null) throw new ForgeError("the flow of " + target.node + " has no entry");
                    vertex = resolve(scope.entrySpec);
                } else vertex = resolvePort(target.node, target.port);
                resolved.put(target, vertex);
                return vertex;
            } finally {
                resolving.remove(target);
            }
        }

        Vertex resolvePort(Object node, __Port port) {
            java.util.LinkedHashMap<__Port, __Target> ports = bindings.get(node);
            if (ports != null && ports.containsKey(port)) return resolve(ports.get(port));
            if (node == root) {
                Vertex v = exits.get(port);
                if (v == null) { v = new __ExitVertex(port); exits.put(port, v); addVertex(v); }
                return v;
            }
            Object parent = flowParent.get(node);
            if (port.inherited) return resolvePort(parent, port);
            throw new ForgeError("port " + port + " of " + node + " is not connected; connect it in the flow of " + parent);
        }

        String describe(__Target t) {
            if (t.kind == 0) return String.valueOf(t.node);
            if (t.kind == 1) return "entry of " + t.node;
            return t.port + "(" + t.node + ")";
        }
    }

    /** Where a parser read a node from: offset and length in characters (bytes for binary input). */
    public static final class Span {
        public final String source;
        public final Integer line, column, offset, length;
        public final String text;
        public Span(String source, Integer line, Integer column, Integer offset, Integer length, String text) {
            this.source = source; this.line = line; this.column = column; this.offset = offset; this.length = length; this.text = text;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Span)) return false;
            Span s = (Span) o;
            return java.util.Objects.equals(source, s.source) && line.equals(s.line) && column.equals(s.column)
                && offset.equals(s.offset) && length.equals(s.length) && text.equals(s.text);
        }
        @Override public int hashCode() { return java.util.Objects.hash(source, line, column, offset, length, text); }
        @Override public String toString() { return (source != null ? source + ":" : "") + line + ":" + column; }
    }

    /** Where node was parsed from, or null if no parser created it. */
    public static Span spanOf(Object node) { return node instanceof __Node ? ((__Node) node).__span : null; }

    // ==== parser runtime ========================================================================

    /** A failed parse: where it failed and what would have been accepted there. */
    public static final class ParseException extends RuntimeException {
        /** Character offset for text input, bit offset for binary input. */
        public final int offset;
        /** 1-based line and column (0 for binary input). */
        public final int line, column;
        public final java.util.List<String> expected;
        public final String source;
        ParseException(String message, int offset, int line, int column, java.util.List<String> expected, String source) {
            super(message);
            this.offset = offset; this.line = line; this.column = column; this.expected = expected; this.source = source;
        }
    }

    interface __RuleBody { Object run(__ParseScope s); }
    interface __Extend { Object run(__ParseScope s, Object left); }
    interface __Parse { Object run(); }

    static class __Rule {
        final String name;
        final __RuleBody body;
        __Rule(String name, __RuleBody body) { this.name = name; this.body = body; }
        Object run(__ParseScope scope, int precedence) { return body.run(scope); }
    }

    static final class __Alternative {
        final int precedence;
        final Integer operandPrecedence;
        final __RuleBody parse;
        final __Extend extend;
        __Alternative(int precedence, Integer operandPrecedence, __RuleBody parse, __Extend extend) {
            this.precedence = precedence; this.operandPrecedence = operandPrecedence; this.parse = parse; this.extend = extend;
        }
    }

    /** A rule made of alternatives and operators with precedence and associativity. */
    static final class __AbstractRule extends __Rule {
        final java.util.ArrayList<__Alternative> alternatives = new java.util.ArrayList<>();
        final java.util.ArrayList<__Alternative> operators = new java.util.ArrayList<>();
        __AbstractRule(String name) { super(name, null); }
        void on(__RuleBody b) { alternatives.add(new __Alternative(Integer.MAX_VALUE, null, b, null)); }
        void prefix(int p, __RuleBody b) { alternatives.add(new __Alternative(p, p, b, null)); }
        void infixLeft(int p, __Extend e) { operators.add(new __Alternative(p, p + 1, null, e)); }
        void infixRight(int p, __Extend e) { operators.add(new __Alternative(p, p, null, e)); }
        void postfix(int p, __Extend e) { operators.add(new __Alternative(p, p, null, e)); }
        @Override Object run(__ParseScope scope, int precedence) { return scope.climb(this, precedence); }
    }

    /** A regular expression. Literals must not end inside a word (fun does not match funny). */
    static final class __Terminal {
        final java.util.regex.Pattern pattern;
        final String literal;
        final String display;
        __Terminal(String regex, String literal, String display) { pattern = java.util.regex.Pattern.compile(regex); this.literal = literal; this.display = display; }
    }

    static final class __Bits {
        final boolean[] bits;
        final String display;
        __Bits(String bits, String display) {
            this.bits = new boolean[bits.length()];
            for (int i = 0; i < bits.length(); i++) this.bits[i] = bits.charAt(i) == '1';
            this.display = display;
        }
    }

    /** A label of a rule: assigned while parsing, read by the producer. */
    public static final class __Label {
        final String name;
        boolean set;
        Object value;
        __Label(String name) { this.name = name; }
        Object set(Object v) { value = v; set = true; return v; }
        Object get() {
            if (!set) throw new ForgeError("label '" + name + "' was not matched");
            return value;
        }
    }

    interface __WithLabel { Object apply(__Label label); }
    static Object __label(String name, __WithLabel body) { return body.apply(new __Label(name)); }

    static final class __Mismatch extends RuntimeException {
        __Mismatch() { super("mismatch", null, false, false); }
    }
    static final __Mismatch __MISMATCH = new __Mismatch();

    static final class __Frame {}

    static final class __Memo {
        boolean ok; Object value; int end; __Frame head;
        __Memo(boolean ok, Object value, int end, __Frame head) { this.ok = ok; this.value = value; this.end = end; this.head = head; }
    }

    static final class __Key {
        final __Rule rule; final int pos; final int precedence;
        __Key(__Rule rule, int pos, int precedence) { this.rule = rule; this.pos = pos; this.precedence = precedence; }
        @Override public int hashCode() { return (System.identityHashCode(rule) * 31 + pos) * 31 + precedence; }
        @Override public boolean equals(Object o) { return o instanceof __Key && ((__Key) o).rule == rule && ((__Key) o).pos == pos && ((__Key) o).precedence == precedence; }
    }

    public static final class __ParseScope {
        final String text;
        final byte[] bytes;
        final java.util.regex.Pattern skip;
        final java.util.Set<String> keywords;
        final String source;
        final int unit;
        final int size;
        int pos = 0;
        final java.util.HashMap<__Key, __Memo> memo = new java.util.HashMap<>();
        final java.util.ArrayList<__Frame> stack = new java.util.ArrayList<>();
        final java.util.HashSet<__Frame> leftRecursive = new java.util.HashSet<>();
        final java.util.HashSet<__Frame> involved = new java.util.HashSet<>();
        int furthest = -1;
        final java.util.LinkedHashSet<String> expected = new java.util.LinkedHashSet<>();
        /** The operator whose body is running (rule and operand precedence), or null inside a rule. */
        final java.util.ArrayList<Object[]> operators = new java.util.ArrayList<>();

        __ParseScope(String text, byte[] bytes, java.util.regex.Pattern skip, java.util.Set<String> keywords, String source) {
            this.text = text; this.bytes = bytes; this.skip = skip; this.keywords = keywords; this.source = source;
            this.unit = bytes == null ? 1 : 8;
            this.size = text.length() * unit;
        }

        Object terminal(__Terminal t) {
            skipIgnored();
            if (pos % unit != 0) fail(t.display);
            int start = pos / unit;
            java.util.regex.Matcher m = t.pattern.matcher(text);
            m.region(start, text.length());
            m.useTransparentBounds(true);
            m.useAnchoringBounds(false);
            if (!m.lookingAt()) fail(t.display);
            String value = m.group();
            int end = m.end();
            if (t.literal != null) {
                if (!t.literal.isEmpty() && wordChar(t.literal.charAt(t.literal.length() - 1)) && end < text.length() && wordChar(text.charAt(end))) fail(t.display);
            } else if (keywords.contains(value)) fail(t.display);
            pos = end * unit;
            return value;
        }

        void skipIgnored() {
            if (skip == null || pos % unit != 0) return;
            while (true) {
                java.util.regex.Matcher m = skip.matcher(text);
                m.region(pos / unit, text.length());
                if (!m.lookingAt() || m.end() == pos / unit) return;
                pos = m.end() * unit;
            }
        }

        Object bits(__Bits p) {
            requireBinary();
            int width = p.bits.length;
            if (pos + width > size) fail(p.display);
            for (int i = 0; i < width; i++) if (bitAt(pos + i) != p.bits[i]) fail(p.display);
            pos += width;
            return p.display;
        }

        Object uint(Object w) {
            int width = (Integer) w;
            requireBinary();
            if (width < 0 || width > 32) throw new IllegalArgumentException("width must be between 0 and 32");
            if (pos + width > size) fail(width + " more bits");
            long value = 0;
            for (int i = 0; i < width; i++) value = (value << 1) | (bitAt(pos + i) ? 1L : 0L);
            pos += width;
            return (int) value;
        }

        Object sint(Object w) {
            int width = (Integer) w;
            if (width < 1 || width > 32) throw new IllegalArgumentException("width must be between 1 and 32");
            long value = ((Integer) uint(width)) & 0xFFFFFFFFL;
            return (int) (width < 32 && ((value >>> (width - 1)) & 1L) == 1L ? value - (1L << width) : value);
        }

        Object bit() { return ((Integer) uint(1)) == 1; }
        Object u1() { return uint(8); }
        Object u2() { return uint(16); }
        Object u4() { return uint(32); }

        Object bytes(Object c) {
            int count = (Integer) c;
            requireBinary();
            if (pos + count * 8 > size) fail(count + " more bytes");
            java.util.ArrayList<Object> r = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) r.add(uint(8));
            return r;
        }

        boolean bitAt(int p) { return ((bytes[p >>> 3] >> (7 - (p & 7))) & 1) == 1; }
        void requireBinary() { if (bytes == null) throw new IllegalStateException("bit and byte primitives need binary input"); }

        void fail(String what) {
            if (pos > furthest) { furthest = pos; expected.clear(); }
            if (pos == furthest) expected.add(what);
            throw __MISMATCH;
        }

        Object optional(__Parse body) {
            int start = pos;
            try { return body.run(); } catch (__Mismatch e) { pos = start; return null; }
        }

        Object many(__Parse body) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            while (true) {
                int start = pos;
                try { result.add(body.run()); } catch (__Mismatch e) { pos = start; return result; }
                if (pos == start) return result;
            }
        }

        Object some(__Parse body) {
            Object first = body.run();
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            result.add(first);
            result.addAll(__l(many(body)));
            return result;
        }

        Object repeat(Object count, __Parse body) {
            java.util.ArrayList<Object> r = new java.util.ArrayList<>();
            for (int i = 0; i < (Integer) count; i++) r.add(body.run());
            return r;
        }

        Object and(__Parse body) {
            int start = pos;
            try { body.run(); } finally { pos = start; }
            return null;
        }

        Object not(String description, __Parse body) {
            int start = pos;
            boolean matched;
            try { body.run(); matched = true; } catch (__Mismatch e) { matched = false; } finally { pos = start; }
            if (matched) fail("not " + description);
            return null;
        }

        Object oneOf(__Parse... alternatives) {
            int start = pos;
            for (__Parse a : alternatives) {
                try { return a.run(); } catch (__Mismatch e) { pos = start; }
            }
            throw __MISMATCH;
        }

        Object seq(__Parse[] items, __Label[] labels, int valueIndex) {
            java.util.ArrayList<Object> values = new java.util.ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                Object v = items[i].run();
                if (labels[i] != null) labels[i].set(v);
                values.add(v);
            }
            if (valueIndex == -2) return null;
            if (valueIndex == -1) return values;
            return values.get(valueIndex);
        }

        Object operand() {
            Object[] context = operators.isEmpty() ? null : operators.get(operators.size() - 1);
            if (context == null) throw new IllegalStateException("an operand outside of an operator");
            return apply((__Rule) context[0], (Integer) context[1]);
        }

        Object inOperator(__Rule rule, Integer operandPrecedence, __Parse block) {
            operators.add(operandPrecedence == null ? null : new Object[]{rule, operandPrecedence});
            try { return block.run(); } finally { operators.remove(operators.size() - 1); }
        }

        Object climb(__AbstractRule rule, int minPrecedence) {
            int start = pos;
            Object left = __MISSING;
            for (__Alternative a : rule.alternatives) {
                try {
                    left = inOperator(rule, a.operandPrecedence, () -> a.parse.run(this));
                    record(left, start, pos);
                    break;
                } catch (__Mismatch e) {
                    pos = start;
                }
            }
            if (left == __MISSING) {
                if (rule.alternatives.isEmpty()) throw new IllegalStateException("abstract rule '" + rule.name + "' has no alternatives");
                throw __MISMATCH;
            }
            extend:
            while (true) {
                for (__Alternative op : rule.operators) {
                    if (op.precedence < minPrecedence) continue;
                    int before = pos;
                    try {
                        final Object current = left;
                        Object extended = inOperator(rule, op.operandPrecedence, () -> op.extend.run(this, current));
                        if (pos == before) continue;
                        left = extended;
                        record(extended, start, pos);
                        continue extend;
                    } catch (__Mismatch e) {
                        pos = before;
                    }
                }
                return left;
            }
        }

        Object apply(__Rule rule) { return apply(rule, Integer.MIN_VALUE); }

        Object apply(__Rule rule, int precedence) {
            int start = pos;
            __Key key = new __Key(rule, start, precedence);
            __Memo m = memo.get(key);
            if (m != null) {
                if (m.head != null) {
                    leftRecursive.add(m.head);
                    for (int i = stack.lastIndexOf(m.head) + 1; i < stack.size(); i++) involved.add(stack.get(i));
                }
                if (!m.ok) throw __MISMATCH;
                pos = m.end;
                return m.value;
            }
            __Frame frame = new __Frame();
            __Memo memoEntry = new __Memo(false, null, start, frame);
            memo.put(key, memoEntry);
            stack.add(frame);
            operators.add(null);
            try {
                attempt(rule, precedence, start, memoEntry);
                if (leftRecursive.contains(frame)) {
                    while (memoEntry.ok) {
                        Object value = memoEntry.value;
                        int end = memoEntry.end;
                        if (!attempt(rule, precedence, start, memoEntry) || memoEntry.end <= end) {
                            memoEntry.ok = true; memoEntry.value = value; memoEntry.end = end;
                            break;
                        }
                    }
                }
            } finally {
                operators.remove(operators.size() - 1);
                stack.remove(stack.size() - 1);
                memoEntry.head = null;
                if (involved.contains(frame)) memo.remove(key);
                leftRecursive.remove(frame);
                involved.remove(frame);
            }
            if (!memoEntry.ok) { pos = start; throw __MISMATCH; }
            pos = memoEntry.end;
            record(memoEntry.value, start, memoEntry.end);
            return memoEntry.value;
        }

        boolean attempt(__Rule rule, int precedence, int start, __Memo into) {
            pos = start;
            try {
                into.value = rule.run(this, precedence);
                into.end = pos;
                into.ok = true;
            } catch (__Mismatch e) {
                into.ok = false;
            }
            return into.ok;
        }

        void record(Object value, int start, int end) {
            if (!(value instanceof __Node) || ((__Node) value).__span != null) return;
            int from = skippedFrom(start) / unit;
            int to = (end + unit - 1) / unit;
            ((__Node) value).__span = bytes == null
                ? new Span(source, lineOf(from), columnOf(from), from, to - from, text.substring(from, Math.max(from, to)))
                : new Span(source, 0, 0, from, to - from, "");
        }

        int skippedFrom(int from) {
            if (skip == null || from % unit != 0) return from;
            int p = from;
            while (true) {
                java.util.regex.Matcher m = skip.matcher(text);
                m.region(p / unit, text.length());
                if (!m.lookingAt() || m.end() == p / unit) return p;
                p = m.end() * unit;
            }
        }

        int lineOf(int offset) {
            int line = 1;
            for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
            return line;
        }

        int columnOf(int offset) { return offset - (text.lastIndexOf('\n', offset - 1) + 1) + 1; }

        Object parseAll(__Rule rule) {
            try {
                Object result = apply(rule);
                skipIgnored();
                if (pos == size) return result;
                if (pos > furthest) { furthest = pos; expected.clear(); }
                if (pos == furthest) expected.add("end of input");
            } catch (__Mismatch e) {
                // reported below
            }
            throw error();
        }

        ParseException error() {
            int at = Math.max(furthest, 0);
            java.util.List<String> exp = new java.util.ArrayList<>(expected);
            String described = String.join(" or ", exp);
            String prefix = source != null ? source + ":" : "";
            if (bytes == null) {
                String found = at < text.length() ? "'" + text.charAt(at) + "'" : "end of input";
                int line = lineOf(at), column = columnOf(at);
                return new ParseException(prefix + line + ":" + column + ": expected " + described + ", found " + found, at, line, column, exp, source);
            }
            int b = at / 8, bit = at % 8;
            String where = bit == 0 ? "byte " + b : "byte " + b + ", bit " + bit;
            String found = b < bytes.length ? String.format("0x%02X", bytes[b] & 0xFF) : "end of input";
            return new ParseException(prefix + where + ": expected " + described + ", found " + found, at, 0, 0, exp, source);
        }

        static boolean wordChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    }

    static Object __parseText(__Rule rule, String text, java.util.regex.Pattern skip, java.util.Set<String> keywords, String source) {
        return new __ParseScope(text, null, skip, keywords, source).parseAll(rule);
    }

    static Object __parseBinary(__Rule rule, byte[] bytes, java.util.regex.Pattern skip, java.util.Set<String> keywords, String source) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) chars[i] = (char) (bytes[i] & 0xFF);
        return new __ParseScope(new String(chars), bytes, skip, keywords, source).parseAll(rule);
    }

    static byte[] __bytes(java.util.List<Object> list) {
        byte[] b = new byte[list.size()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (int) (Integer) list.get(i);
        return b;
    }

    // ==== end of Forge runtime ==================================================================
