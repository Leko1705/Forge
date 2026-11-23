// ==== Forge runtime ============================================================================
// Values, attribute caches, fixpoints of circular attributes, graphs, flows, regular expressions and the
// parser engine. Standard library only.
#[allow(dead_code, unused_variables, unused_mut, non_snake_case, clippy::all)]
pub mod rt {
    use std::cell::{Cell, RefCell};
    use std::collections::{HashMap, HashSet, VecDeque};
    use std::rc::Rc;

    /** A value of Forge. Lists, maps and records are immutable; nodes are compared by identity. */
    #[derive(Clone)]
    pub enum V {
        Null,
        Int(i32),
        Float(f32),
        Bool(bool),
        Str(Rc<str>),
        List(Rc<Vec<V>>),
        /** Without duplicates, in the order the elements were added. */
        Set(Rc<Vec<V>>),
        Map(Rc<Vec<(V, V)>>),
        Rec(Rc<Rec>),
        Node(Rc<Node>),
        Graph(Rc<Graph>),
        Edge(Rc<Edge>),
        Port(Rc<Port>),
        PortRef(Rc<(V, V)>),
        FlowGraph(usize),
        NoMatch,
    }

    /** Records and enum items: [rid] is the type, [item] whether it is an enum item. */
    pub struct Rec { pub rid: i32, pub name: &'static str, pub names: &'static [&'static str], pub fields: Vec<V>, pub item: bool }
    /** Nodes, and the exit and synthetic vertices of flows ([kind] < 0). */
    pub struct Node {
        pub kind: i32,
        pub name: &'static str,
        pub id: u64,
        pub fields: Vec<V>,
        pub parent: RefCell<V>,
        pub span: RefCell<V>,
        cache: RefCell<HashMap<i64, V>>,
    }
    pub struct Edge { pub from: V, pub to: V, pub label: V }
    pub struct Port { pub name: &'static str, pub inherited: bool, pub id: u64 }

    pub const EXIT_KIND: i32 = -1;
    pub const SYNTHETIC_KIND: i32 = -2;
    pub const SPAN_RID: i32 = -1;

    thread_local! {
        static COUNTER: Cell<u64> = Cell::new(0);
    }
    pub fn next_id() -> u64 { COUNTER.with(|c| { c.set(c.get() + 1); c.get() }) }

    /** An error while evaluating: a missing value, no matching case, an attribute that depends on itself. */
    pub fn fail(message: impl Into<String>) -> ! { panic!("{}", message.into()) }

    pub fn s(text: &str) -> V { V::Str(Rc::from(text)) }
    pub fn list(items: Vec<V>) -> V { V::List(Rc::new(items)) }

    pub fn truth(v: &V) -> bool { match v { V::Bool(b) => *b, _ => fail("a bool was expected") } }
    pub fn int_of(v: &V) -> i32 { match v { V::Int(i) => *i, _ => fail("an int was expected") } }
    pub fn float_of(v: &V) -> f32 { match v { V::Float(f) => *f, _ => fail("a float was expected") } }
    pub fn str_of(v: &V) -> &str { match v { V::Str(s) => s, _ => fail("a str was expected") } }
    pub fn items(v: &V) -> &Vec<V> { match v { V::List(l) | V::Set(l) => l, _ => fail("a list was expected") } }
    pub fn entries(v: &V) -> &Vec<(V, V)> { match v { V::Map(m) => m, _ => fail("a map was expected") } }
    pub fn node_of(v: &V) -> &Node { match v { V::Node(n) => n, _ => fail(format!("a node was expected, got {}", show(v))) } }
    pub fn is_null(v: &V) -> bool { matches!(v, V::Null) }

    // ---- arithmetic -----------------------------------------------------------------------

    pub fn add(a: &V, b: &V) -> V { V::Int(int_of(a).wrapping_add(int_of(b))) }
    pub fn sub(a: &V, b: &V) -> V { V::Int(int_of(a).wrapping_sub(int_of(b))) }
    pub fn mul(a: &V, b: &V) -> V { V::Int(int_of(a).wrapping_mul(int_of(b))) }
    pub fn div(a: &V, b: &V) -> V { let d = int_of(b); if d == 0 { fail("/ by zero") } V::Int(int_of(a).wrapping_div(d)) }
    pub fn rem(a: &V, b: &V) -> V { let d = int_of(b); if d == 0 { fail("/ by zero") } V::Int(int_of(a).wrapping_rem(d)) }
    pub fn neg(a: &V) -> V { V::Int(int_of(a).wrapping_neg()) }
    pub fn band(a: &V, b: &V) -> V { V::Int(int_of(a) & int_of(b)) }
    pub fn bor(a: &V, b: &V) -> V { V::Int(int_of(a) | int_of(b)) }
    pub fn bxor(a: &V, b: &V) -> V { V::Int(int_of(a) ^ int_of(b)) }
    pub fn bnot(a: &V) -> V { V::Int(!int_of(a)) }
    pub fn fadd(a: &V, b: &V) -> V { V::Float(float_of(a) + float_of(b)) }
    pub fn fsub(a: &V, b: &V) -> V { V::Float(float_of(a) - float_of(b)) }
    pub fn fmul(a: &V, b: &V) -> V { V::Float(float_of(a) * float_of(b)) }
    pub fn fdiv(a: &V, b: &V) -> V { V::Float(float_of(a) / float_of(b)) }
    pub fn fneg(a: &V) -> V { V::Float(-float_of(a)) }
    pub fn i2f(a: &V) -> V { V::Float(int_of(a) as f32) }
    pub fn f2i(a: &V) -> V { V::Int(float_of(a) as i32) }
    pub fn concat(a: &V, b: &V) -> V { let mut r = String::from(str_of(a)); r.push_str(str_of(b)); s(&r) }

    // ---- equality and text ----------------------------------------------------------------

    pub fn eq(a: &V, b: &V) -> bool {
        match (a, b) {
            (V::Null, V::Null) | (V::NoMatch, V::NoMatch) => true,
            (V::Int(x), V::Int(y)) => x == y,
            (V::Float(x), V::Float(y)) => x == y || (x.is_nan() && y.is_nan()),
            (V::Bool(x), V::Bool(y)) => x == y,
            (V::Str(x), V::Str(y)) => x == y,
            (V::List(x), V::List(y)) => x.len() == y.len() && x.iter().zip(y.iter()).all(|(p, q)| eq(p, q)),
            (V::Set(x), V::Set(y)) => x.len() == y.len() && x.iter().all(|a| y.iter().any(|b| eq(a, b))),
            (V::Map(x), V::Map(y)) => x.len() == y.len()
                && x.iter().all(|(k, v)| y.iter().any(|(k2, v2)| eq(k, k2) && eq(v, v2))),
            (V::Rec(x), V::Rec(y)) => x.rid == y.rid && x.name == y.name && x.fields.len() == y.fields.len()
                && x.fields.iter().zip(y.fields.iter()).all(|(p, q)| eq(p, q)),
            (V::Node(x), V::Node(y)) => Rc::ptr_eq(x, y),
            (V::Graph(x), V::Graph(y)) => Rc::ptr_eq(x, y),
            (V::Edge(x), V::Edge(y)) => Rc::ptr_eq(x, y),
            (V::Port(x), V::Port(y)) => Rc::ptr_eq(x, y),
            (V::PortRef(x), V::PortRef(y)) => eq(&x.0, &y.0) && eq(&x.1, &y.1),
            (V::FlowGraph(x), V::FlowGraph(y)) => x == y,
            _ => false,
        }
    }

    pub fn same(a: &V, b: &V) -> bool { match (a, b) { (V::Node(x), V::Node(y)) => Rc::ptr_eq(x, y), _ => false } }

    pub fn show_float(f: f32) -> String {
        if f.is_nan() { return "NaN".into(); }
        if f.is_infinite() { return if f > 0.0 { "Infinity".into() } else { "-Infinity".into() }; }
        if f == 0.0 { return if f.is_sign_negative() { "-0.0".into() } else { "0.0".into() }; }
        // the shortest decimal that reads back as the same float, formatted like Kotlin
        let text = format!("{:e}", f);
        let (mantissa, exponent) = text.split_once('e').unwrap();
        let exponent: i32 = exponent.parse().unwrap();
        let negative = mantissa.starts_with('-');
        let digits: String = mantissa.chars().filter(|c| c.is_ascii_digit()).collect();
        let n = digits.len() as i32;
        let body = if (-3..7).contains(&exponent) {
            if exponent >= 0 {
                let whole = if n > exponent + 1 { digits[..(exponent + 1) as usize].to_string() } else { format!("{}{}", digits, "0".repeat((exponent + 1 - n) as usize)) };
                let fraction = if n > exponent + 1 { digits[(exponent + 1) as usize..].to_string() } else { "0".to_string() };
                format!("{}.{}", whole, fraction)
            } else {
                format!("0.{}{}", "0".repeat((-exponent - 1) as usize), digits)
            }
        } else {
            format!("{}.{}E{}", &digits[..1], if n > 1 { &digits[1..] } else { "0" }, exponent)
        };
        if negative { format!("-{}", body) } else { body }
    }

    pub fn join(xs: &[V]) -> String { xs.iter().map(show).collect::<Vec<_>>().join(", ") }

    pub fn show(v: &V) -> String {
        match v {
            V::Null => "null".into(),
            V::Int(i) => i.to_string(),
            V::Float(f) => show_float(*f),
            V::Bool(b) => b.to_string(),
            V::Str(s) => s.to_string(),
            V::List(l) | V::Set(l) => format!("[{}]", join(l)),
            V::Map(m) => format!("{{{}}}", m.iter().map(|(k, x)| format!("{}={}", show(k), show(x))).collect::<Vec<_>>().join(", ")),
            V::Rec(r) if r.rid == SPAN_RID => format!("{}{}:{}", if is_null(&r.fields[0]) { String::new() } else { format!("{}:", show(&r.fields[0])) }, show(&r.fields[1]), show(&r.fields[2])),
            V::Rec(r) if r.item && r.fields.is_empty() => r.name.to_string(),
            V::Rec(r) => format!("{}({})", r.name, r.fields.iter().enumerate().map(|(i, x)| format!("{}={}", r.names[i], show(x))).collect::<Vec<_>>().join(", ")),
            V::Node(n) if n.kind == EXIT_KIND => format!("exit({})", show(&n.fields[0])),
            V::Node(n) if n.kind == SYNTHETIC_KIND => show(&n.fields[0]),
            V::Node(n) => format!("{}({})", n.name, join(&n.fields)),
            V::Graph(g) => format!("Graph({} vertices, {} edges)", g.vertices.len(), g.edges.len()),
            V::Edge(e) => if is_null(&e.label) { format!("{} -> {}", show(&e.from), show(&e.to)) } else { format!("{} -[{}]-> {}", show(&e.from), show(&e.label), show(&e.to)) },
            V::Port(p) => p.name.to_string(),
            V::PortRef(r) => format!("{}({})", show(&r.1), show(&r.0)),
            V::FlowGraph(_) => "FlowGraph".into(),
            V::NoMatch => "<no match>".into(),
        }
    }

    // ---- lists, maps, records -------------------------------------------------------------

    pub fn nn(v: V) -> V { if is_null(&v) { fail("null value where a value is required (!!)") } v }
    pub fn elvis(v: V, alt: impl FnOnce() -> V) -> V { if is_null(&v) { alt() } else { v } }
    /** A position for [n] elements: below 0 counts from the end. */
    pub fn position(i: i32, n: usize) -> usize {
        let k = if i < 0 { i as i64 + n as i64 } else { i as i64 };
        if k < 0 || k >= n as i64 { fail(format!("index {} out of bounds for length {}", i, n)) }
        k as usize
    }
    /** A bound of a slice: below 0 counts from the end, clamped to 0..n; null is the default. */
    fn bound(b: &V, n: usize, default: usize) -> usize {
        let k = match b { V::Null => default as i64, _ => { let i = int_of(b) as i64; if i < 0 { i + n as i64 } else { i } } };
        k.clamp(0, n as i64) as usize
    }
    pub fn get(l: &V, i: &V) -> V { let a = items(l); a[position(int_of(i), a.len())].clone() }
    pub fn size(v: &V) -> V {
        match v {
            V::List(l) | V::Set(l) => V::Int(l.len() as i32),
            V::Map(m) => V::Int(m.len() as i32),
            V::Str(s) => V::Int(s.len() as i32),
            _ => fail("size of a value that is no list, set, map or str"),
        }
    }
    pub fn slice(v: &V, lo: &V, hi: &V) -> V {
        match v {
            V::Str(s) => { let n = s.len(); let (a, b) = (bound(lo, n, 0), bound(hi, n, n)); if a >= b { self::s("") } else { self::s(&String::from_utf8_lossy(&s.as_bytes()[a..b])) } }
            _ => { let l = items(v); let n = l.len(); let (a, b) = (bound(lo, n, 0), bound(hi, n, n)); list(if a >= b { vec![] } else { l[a..b].to_vec() }) }
        }
    }
    pub fn char_at(v: &V, i: &V) -> V { let t = str_of(v).as_bytes(); let k = position(int_of(i), t.len()); s(&String::from_utf8_lossy(&t[k..k + 1])) }
    pub fn range(a: &V, b: &V) -> V { list((int_of(a)..int_of(b)).map(V::Int).collect()) }
    pub fn shl(a: &V, b: &V) -> V { V::Int(int_of(a).wrapping_shl(int_of(b) as u32 & 31)) }
    pub fn shr(a: &V, b: &V) -> V { V::Int(int_of(a) >> (int_of(b) & 31)) }
    pub fn ushr(a: &V, b: &V) -> V { V::Int(((int_of(a) as u32) >> (int_of(b) as u32 & 31)) as i32) }

    // ---- sets
    pub fn make_set(xs: Vec<V>) -> V {
        let mut r: Vec<V> = Vec::new();
        for x in xs { if !r.iter().any(|y| eq(&x, y)) { r.push(x); } }
        V::Set(Rc::new(r))
    }
    pub fn set_contains(st: &V, x: &V) -> V { V::Bool(items(st).iter().any(|y| eq(x, y))) }
    pub fn set_union(a: &V, b: &V) -> V { let mut r = items(a).clone(); r.extend(items(b).iter().cloned()); make_set(r) }
    pub fn set_remove(st: &V, x: &V) -> V { make_set(items(st).iter().filter(|y| !eq(x, y)).cloned().collect()) }
    pub fn to_set(l: &V) -> V { make_set(items(l).clone()) }
    pub fn to_list(st: &V) -> V { list(items(st).clone()) }

    // ---- builtins of str, List and Map
    pub fn str_index_of(a: &V, b: &V) -> V { V::Int(str_of(a).find(str_of(b)).map(|i| i as i32).unwrap_or(-1)) }
    pub fn str_contains(a: &V, b: &V) -> V { V::Bool(str_of(a).contains(str_of(b))) }
    pub fn starts_with(a: &V, b: &V) -> V { V::Bool(str_of(a).starts_with(str_of(b))) }
    pub fn ends_with(a: &V, b: &V) -> V { V::Bool(str_of(a).ends_with(str_of(b))) }
    pub fn split(a: &V, sep: &V) -> V {
        if str_of(sep).is_empty() { fail("the separator of split is empty") }
        list(str_of(a).split(str_of(sep)).map(s).collect())
    }
    pub fn replace(a: &V, old: &V, new: &V) -> V {
        if str_of(old).is_empty() { fail("the text to replace is empty") }
        s(&str_of(a).replace(str_of(old), str_of(new)))
    }
    pub fn trim(a: &V) -> V { s(str_of(a).trim_matches(|c: char| matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}'))) }
    pub fn upper(a: &V) -> V { s(&str_of(a).to_ascii_uppercase()) }
    pub fn lower(a: &V) -> V { s(&str_of(a).to_ascii_lowercase()) }
    pub fn code(a: &V, i: &V) -> V { let t = str_of(a).as_bytes(); V::Int(t[position(int_of(i), t.len())] as i32) }
    pub fn utf8(a: &V) -> V { list(str_of(a).bytes().map(|b| V::Int(b as i32)).collect()) }
    pub fn char_of(c: &V) -> V { s(&char::from_u32(int_of(c) as u32).map(|c| c.to_string()).unwrap_or_else(|| fail("not a character code"))) }
    pub fn unescape(a: &V) -> V {
        let mut out = String::new();
        let mut chars = str_of(a).chars().peekable();
        while let Some(c) = chars.next() {
            if c == '\\' && chars.peek().is_some() {
                let e = chars.next().unwrap();
                out.push(match e { 'n' => '\n', 't' => '\t', 'r' => '\r', '0' => '\0', x => x });
            } else {
                out.push(c);
            }
        }
        s(&out)
    }
    pub fn to_int(a: &V) -> V {
        let t = str_of(a);
        let digits = t.strip_prefix(|c| c == '+' || c == '-').unwrap_or(t);
        if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) { fail(format!("not an int: \"{}\"", t)) }
        match t.trim_start_matches('+').parse::<i32>() { Ok(v) => V::Int(v), Err(_) => fail(format!("not an int: \"{}\"", t)) }
    }
    pub fn to_float(a: &V) -> V {
        let t = str_of(a).as_bytes();
        let n = t.len();
        let mut i = if n > 0 && (t[0] == b'+' || t[0] == b'-') { 1 } else { 0 };
        let mut digits = |i: &mut usize| { let start = *i; while *i < n && t[*i].is_ascii_digit() { *i += 1; } *i - start };
        let whole = digits(&mut i);
        let mut fraction = 0;
        if i < n && t[i] == b'.' { i += 1; fraction = digits(&mut i); }
        let mut ok = whole + fraction > 0;
        if i < n && (t[i] == b'e' || t[i] == b'E') {
            i += 1;
            if i < n && (t[i] == b'+' || t[i] == b'-') { i += 1; }
            if digits(&mut i) == 0 { ok = false; }
        }
        if !ok || i != n { fail(format!("not a float: \"{}\"", str_of(a))) }
        V::Float(str_of(a).parse::<f32>().unwrap())
    }
    pub fn join_str(l: &V, sep: &V) -> V { s(&items(l).iter().map(|x| str_of(x).to_string()).collect::<Vec<_>>().join(str_of(sep))) }
    pub fn list_index_of(l: &V, x: &V) -> V { V::Int(items(l).iter().position(|y| eq(x, y)).map(|i| i as i32).unwrap_or(-1)) }
    pub fn reversed(l: &V) -> V { let mut r = items(l).clone(); r.reverse(); list(r) }
    pub fn map_keys(m: &V) -> V { list(entries(m).iter().map(|(k, _)| k.clone()).collect()) }
    pub fn map_values(m: &V) -> V { list(entries(m).iter().map(|(_, x)| x.clone()).collect()) }
    pub fn map_remove(m: &V, k: &V) -> V { V::Map(Rc::new(entries(m).iter().filter(|(k2, _)| !eq(k, k2)).cloned().collect())) }
    pub fn list_concat(a: &V, b: &V) -> V { let mut r = items(a).clone(); r.extend(items(b).iter().cloned()); list(r) }
    pub fn list_append(a: &V, x: &V) -> V { let mut r = items(a).clone(); r.push(x.clone()); list(r) }
    pub fn tail(l: &V) -> V { list(items(l)[1..].to_vec()) }
    pub fn contains(l: &V, x: &V) -> bool { items(l).iter().any(|y| eq(x, y)) }
    pub fn map_list(l: &V, f: impl Fn(&V) -> V) -> V { list(items(l).iter().map(f).collect()) }

    fn put_raw(m: &mut Vec<(V, V)>, k: V, x: V) {
        for e in m.iter_mut() { if eq(&e.0, &k) { e.1 = x; return; } }
        m.push((k, x));
    }
    pub fn map_of(pairs: Vec<(V, V)>) -> V { let mut m = Vec::new(); for (k, x) in pairs { put_raw(&mut m, k, x); } V::Map(Rc::new(m)) }
    pub fn map_put(m: &V, k: &V, x: &V) -> V { let mut r = entries(m).clone(); put_raw(&mut r, k.clone(), x.clone()); V::Map(Rc::new(r)) }
    pub fn map_merge(a: &V, b: &V) -> V { let mut r = entries(a).clone(); for (k, x) in entries(b) { put_raw(&mut r, k.clone(), x.clone()); } V::Map(Rc::new(r)) }
    pub fn map_has(m: &V, k: &V) -> bool { entries(m).iter().any(|(k2, _)| eq(k, k2)) }
    pub fn map_at(m: &V, k: &V) -> V {
        for (k2, x) in entries(m) { if eq(k, k2) { return x.clone(); } }
        fail(format!("key {} is missing in the map", show(k)))
    }

    pub fn new_rec(rid: i32, name: &'static str, names: &'static [&'static str], item: bool, fields: Vec<V>) -> V {
        V::Rec(Rc::new(Rec { rid, name, names, fields, item }))
    }
    pub fn field(v: &V, k: usize) -> V {
        match v { V::Rec(r) => r.fields[k].clone(), V::Node(n) => n.fields[k].clone(), _ => fail(format!("a record was expected, got {}", show(v))) }
    }
    pub fn rec_is(v: &V, rid: i32) -> bool { match v { V::Rec(r) => r.rid == rid, _ => false } }

    // ---- nodes ----------------------------------------------------------------------------

    pub fn kind(v: &V) -> i32 { match v { V::Node(n) => n.kind, _ => fail(format!("not a vertex: {}", show(v))) } }
    pub fn kind_in(v: &V, kinds: &[i32]) -> bool { match v { V::Node(n) => kinds.contains(&n.kind), _ => false } }
    pub fn is_vertex(v: &V) -> bool { matches!(v, V::Node(_)) }

    pub fn children(n: &Node) -> Vec<V> {
        let mut r = Vec::new();
        for f in &n.fields {
            match f {
                V::Node(_) => r.push(f.clone()),
                V::List(l) => for x in l.iter() { if let V::Node(_) = x { r.push(x.clone()); } },
                _ => {}
            }
        }
        r
    }

    /** A node; it becomes the parent of its children (which keeps the tree alive as long as any node of it is). */
    pub fn new_node(kind: i32, name: &'static str, fields: Vec<V>) -> V {
        let n = Rc::new(Node { kind, name, id: next_id(), fields, parent: RefCell::new(V::Null), span: RefCell::new(V::Null), cache: RefCell::new(HashMap::new()) });
        let v = V::Node(n.clone());
        for c in children(&n) { *node_of(&c).parent.borrow_mut() = v.clone(); }
        v
    }
    pub fn parent(v: &V) -> V { node_of(v).parent.borrow().clone() }
    pub fn root(v: &V) -> V { let mut n = v.clone(); loop { let p = parent(&n); if is_null(&p) { return n; } n = p; } }

    /** The nodes of the tree below a node (including it), parents before children. */
    pub fn preorder(n: &V) -> Vec<V> {
        let mut result = Vec::new();
        let mut work = vec![n.clone()];
        while let Some(x) = work.pop() {
            let c = children(node_of(&x));
            result.push(x);
            for y in c.into_iter().rev() { work.push(y); }
        }
        result
    }

    pub fn index_of_child(l: &V, child: &V) -> i32 {
        items(l).iter().position(|x| same(x, child)).map(|i| i as i32).unwrap_or(-1)
    }

    // ---- caches and fixpoints -------------------------------------------------------------

    struct Cycle { iteration: i32, changed: bool, attributes: Vec<i64> }
    #[derive(Default)]
    struct CircularState {
        cycle: Option<Cycle>,
        approx: HashMap<(i64, u64), V>,
        visited: HashMap<(i64, u64), (V, i32)>,
    }
    thread_local! {
        static CIRCULAR: RefCell<CircularState> = RefCell::new(CircularState::default());
    }
    fn in_cycle() -> bool { CIRCULAR.with(|c| c.borrow().cycle.is_some()) }

    struct Busy<'a> { node: &'a Node, id: i64 }
    impl Drop for Busy<'_> { fn drop(&mut self) { self.node.cache.borrow_mut().remove(&-self.id); } }

    /** The value of an attribute, computed once per node. Nothing is cached while a fixpoint is computed. */
    pub fn cached(id: i64, name: &str, v: &V, compute: impl FnOnce() -> V) -> V {
        let n = node_of(v);
        if let Some(x) = n.cache.borrow().get(&id) { return x.clone(); }
        if n.cache.borrow().contains_key(&-id) { fail(format!("attribute '{}' of {} depends on itself; declare it as circular", name, show(v))) }
        n.cache.borrow_mut().insert(-id, V::Null);
        let busy = Busy { node: n, id };
        let value = compute();
        drop(busy);
        if !in_cycle() { n.cache.borrow_mut().insert(id, value.clone()); }
        value
    }

    fn approx_of(id: i64, n: &Node, initial: &V) -> V {
        CIRCULAR.with(|c| c.borrow().approx.get(&(id, n.id)).cloned()).unwrap_or_else(|| initial.clone())
    }

    fn step(id: i64, v: &V, initial: &V, body: &dyn Fn() -> V) -> V {
        let n = node_of(v);
        CIRCULAR.with(|c| { let mut c = c.borrow_mut(); let it = c.cycle.as_ref().unwrap().iteration; c.visited.insert((id, n.id), (v.clone(), it)); });
        let old = approx_of(id, n, initial);
        let value = body();
        CIRCULAR.with(|c| {
            let mut c = c.borrow_mut();
            let known = c.approx.contains_key(&(id, n.id));
            if !eq(&value, &old) || !known {
                if !eq(&value, &old) { c.cycle.as_mut().unwrap().changed = true; }
                c.approx.insert((id, n.id), value.clone());
            }
        });
        value
    }

    /** A circular attribute: a fixpoint, starting at its initial value. */
    pub fn circular(id: i64, v: &V, initial: V, body: impl Fn() -> V) -> V {
        let n = node_of(v);
        if let Some(x) = n.cache.borrow().get(&id) { return x.clone(); }
        if in_cycle() {
            let (iteration, seen) = CIRCULAR.with(|c| {
                let mut c = c.borrow_mut();
                let cycle = c.cycle.as_mut().unwrap();
                if !cycle.attributes.contains(&id) { cycle.attributes.push(id); }
                let iteration = cycle.iteration;
                (iteration, c.visited.get(&(id, n.id)).map(|x| x.1))
            });
            if seen == Some(iteration) { return approx_of(id, n, &initial); }
            return step(id, v, &initial, &body);
        }
        CIRCULAR.with(|c| c.borrow_mut().cycle = Some(Cycle { iteration: 0, changed: false, attributes: vec![id] }));
        loop {
            CIRCULAR.with(|c| { let mut c = c.borrow_mut(); let cy = c.cycle.as_mut().unwrap(); cy.changed = false; cy.iteration += 1; });
            step(id, v, &initial, &body);
            if !CIRCULAR.with(|c| c.borrow().cycle.as_ref().unwrap().changed) { break; }
        }
        let state = CIRCULAR.with(|c| std::mem::take(&mut *c.borrow_mut()));
        let last = state.cycle.as_ref().unwrap().iteration;
        for ((attr, node), (vertex, iteration)) in state.visited.iter() {
            if *iteration == last {
                if let Some(x) = state.approx.get(&(*attr, *node)) { node_of(vertex).cache.borrow_mut().insert(*attr, x.clone()); }
            }
        }
        let result = n.cache.borrow().get(&id).cloned().unwrap_or(V::Null);
        result
    }

    // ---- graphs ---------------------------------------------------------------------------

    pub struct Graph {
        pub root: V,
        pub entry: V,
        pub vertices: Vec<V>,
        pub edges: Vec<V>,
        exits: Vec<(V, V)>,
        index: HashMap<u64, usize>,
        out: Vec<Vec<V>>,
        inn: Vec<Vec<V>>,
    }

    pub fn new_edge(f: V, t: V, l: V) -> V { V::Edge(Rc::new(Edge { from: f, to: t, label: l })) }
    fn vid(v: &V) -> u64 { node_of(v).id }
    fn edge_of(v: &V) -> &Edge { match v { V::Edge(e) => e, _ => fail("an edge was expected") } }
    pub fn graph_of(v: &V) -> &Graph { match v { V::Graph(g) => g, _ => fail("a graph was expected") } }

    fn make_graph(root: V, entry: V, vertices: &[V], edges: &[V], exits: Vec<(V, V)>) -> V {
        let mut index = HashMap::new();
        let mut vs: Vec<V> = Vec::new();
        let mut add = |v: &V, vs: &mut Vec<V>| { let i = vid(v); if !index.contains_key(&i) { index.insert(i, vs.len()); vs.push(v.clone()); } };
        for v in vertices { add(v, &mut vs); }
        for e in edges { add(&edge_of(e).from, &mut vs); add(&edge_of(e).to, &mut vs); }
        let mut out = vec![Vec::new(); vs.len()];
        let mut inn = vec![Vec::new(); vs.len()];
        for e in edges {
            out[index[&vid(&edge_of(e).from)]].push(e.clone());
            inn[index[&vid(&edge_of(e).to)]].push(e.clone());
        }
        V::Graph(Rc::new(Graph { root, entry, vertices: vs, edges: edges.to_vec(), exits, index, out, inn }))
    }

    /** A graph from edges, e.g. a call graph. The vertices are entry and the endpoints of edges. */
    pub fn graph_from(entry: &V, edges: &V) -> V {
        let mut vs = vec![entry.clone()];
        for e in items(edges) { vs.push(edge_of(e).from.clone()); vs.push(edge_of(e).to.clone()); }
        make_graph(V::Null, entry.clone(), &vs, items(edges), Vec::new())
    }
    pub fn edge_field(v: &V, k: usize) -> V { let e = edge_of(v); match k { 0 => e.from.clone(), 1 => e.to.clone(), _ => e.label.clone() } }
    pub fn graph_field(v: &V, k: usize) -> V {
        let g = graph_of(v);
        match k { 0 => g.entry.clone(), 1 => list(g.vertices.clone()), 2 => list(g.edges.clone()), _ => g.root.clone() }
    }
    fn index_in(g: &Graph, v: &V) -> Option<usize> { match v { V::Node(n) => g.index.get(&n.id).copied(), _ => None } }
    pub fn g_contains(g: &V, v: &V) -> V { V::Bool(index_in(graph_of(g), v).is_some()) }
    pub fn g_index(g: &V, v: &V) -> V { V::Int(index_in(graph_of(g), v).map(|i| i as i32).unwrap_or(-1)) }
    pub fn g_out(g: &V, v: &V) -> V { let gr = graph_of(g); list(index_in(gr, v).map(|i| gr.out[i].clone()).unwrap_or_default()) }
    pub fn g_in(g: &V, v: &V) -> V { let gr = graph_of(g); list(index_in(gr, v).map(|i| gr.inn[i].clone()).unwrap_or_default()) }
    pub fn g_succ(g: &V, v: &V) -> V { list(items(&g_out(g, v)).iter().map(|e| edge_of(e).to.clone()).collect()) }
    pub fn g_pred(g: &V, v: &V) -> V { list(items(&g_in(g, v)).iter().map(|e| edge_of(e).from.clone()).collect()) }
    pub fn g_exit(g: &V, port: &V) -> V {
        for (p, v) in &graph_of(g).exits { if let V::Port(p) = p { if p.name == str_of(port) { return v.clone(); } } }
        V::Null
    }
    pub fn g_reachable(gv: &V) -> V {
        let g = graph_of(gv);
        let mut seen = vec![false; g.vertices.len()];
        let mut result = Vec::new();
        let mut work = vec![g.entry.clone()];
        while let Some(v) = work.pop() {
            let Some(i) = index_in(g, &v) else { continue };
            if seen[i] { continue; }
            seen[i] = true;
            for x in items(&g_succ(gv, &v)).iter().rev() { work.push(x.clone()); }
            result.push(v);
        }
        list(result)
    }
    pub fn g_postorder(gv: &V) -> V {
        let g = graph_of(gv);
        let mut seen = vec![false; g.vertices.len()];
        let mut result = Vec::new();
        let mut stack: Vec<(V, V, usize)> = Vec::new();
        if let Some(i) = index_in(g, &g.entry) { seen[i] = true; stack.push((g.entry.clone(), g_succ(gv, &g.entry), 0)); }
        while let Some(top) = stack.last_mut() {
            if top.2 < items(&top.1).len() {
                let c = items(&top.1)[top.2].clone();
                top.2 += 1;
                if let Some(i) = index_in(g, &c) { if !seen[i] { seen[i] = true; let s = g_succ(gv, &c); stack.push((c, s, 0)); } }
            } else {
                let (v, _, _) = stack.pop().unwrap();
                result.push(v);
            }
        }
        list(result)
    }
    pub fn g_rpo(gv: &V) -> V { let mut r = items(&g_postorder(gv)).clone(); r.reverse(); list(r) }
    fn dot_escape(s: &str) -> String { s.replace('\\', "\\\\").replace('"', "\\\"") }
    pub fn g_dot(gv: &V) -> V {
        let g = graph_of(gv);
        let mut out = String::from("digraph G {\n");
        for (i, v) in g.vertices.iter().enumerate() { out.push_str(&format!("  n{} [label=\"{}\"];\n", i, dot_escape(&show(v)))); }
        for e in &g.edges {
            let x = edge_of(e);
            out.push_str(&format!("  n{} -> n{}", index_in(g, &x.from).unwrap(), index_in(g, &x.to).unwrap()));
            if !is_null(&x.label) { out.push_str(&format!(" [label=\"{}\"]", dot_escape(&show(&x.label)))); }
            out.push_str(";\n");
        }
        out.push('}');
        s(&out)
    }

    pub fn new_port(name: &'static str, inherited: bool) -> V { V::Port(Rc::new(Port { name, inherited, id: next_id() })) }
    thread_local! {
        static NEXT: V = new_port("next", false);
    }
    pub fn port_next() -> V { NEXT.with(|p| p.clone()) }
    fn port_of(v: &V) -> &Port { match v { V::Port(p) => p, _ => fail("a port was expected") } }
    pub fn port_ref(node: V, port: V) -> V { V::PortRef(Rc::new((node, port))) }
    pub fn synthetic_vertex(name: V, owner: V) -> V { new_node(SYNTHETIC_KIND, "", vec![name, owner]) }

    // ---- flows ----------------------------------------------------------------------------

    /** Where a flow sends control: a vertex, a child (its entry) or a port of a node. */
    #[derive(Clone)]
    pub enum Target { Vertex(V), Child(V), Port(V, V) }

    #[derive(Default)]
    struct ScopeData { entry: Option<Target>, own: HashSet<u64> }

    /** The flows of a graph definition: the rule of every node and whether a node is a root. */
    pub struct Flows { pub rule: fn(usize, &mut Build, &V) -> bool, pub is_root: fn(usize, &V) -> bool }

    /** Runs the flows of all nodes reachable from the root and resolves them into a Graph. */
    pub struct Build {
        root: V,
        current: V,
        scopes: HashMap<u64, ScopeData>,
        flow_parent: HashMap<u64, V>,
        bindings: HashMap<u64, Vec<(V, Target)>>,
        queue: VecDeque<V>,
        vertices: Vec<V>,
        vertex_set: HashSet<u64>,
        exits: Vec<(V, V)>,
        edges: Vec<(V, Target, V)>,
        resolved: HashMap<(u8, u64, u64), V>,
        resolving: HashSet<(u8, u64, u64)>,
    }

    impl Build {
        fn scope(&mut self) -> &mut ScopeData { let id = vid(&self.current); self.scopes.get_mut(&id).unwrap() }
        fn add_vertex(&mut self, v: &V) { if self.vertex_set.insert(vid(v)) { self.vertices.push(v.clone()); } }
        fn reference(&mut self, child: &V, by: &V) {
            if same(child, &self.root) || self.flow_parent.contains_key(&vid(child)) { return; }
            self.flow_parent.insert(vid(child), by.clone());
            self.queue.push_back(child.clone());
        }
        fn bind(&mut self, child: &V, port: &V, t: Target) {
            let ports = self.bindings.entry(vid(child)).or_default();
            if ports.iter().any(|(p, _)| eq(p, port)) { fail(format!("port {} of {} is connected twice", show(port), show(child))) }
            ports.push((port.clone(), t));
        }
        fn is_bound(&self, node: &V, port: &V) -> bool {
            if let Some(ports) = self.bindings.get(&vid(node)) { if ports.iter().any(|(p, _)| eq(p, port)) { return true; } }
            if !port_of(port).inherited { return false; }
            match self.flow_parent.get(&vid(node)) { Some(p) => self.is_bound(p, port), None => false }
        }
        fn is_own(&self, x: &V) -> bool {
            same(x, &self.current) || match x { V::Node(n) => self.scopes.get(&vid(&self.current)).map_or(false, |s| s.own.contains(&n.id)), _ => false }
        }
        fn to_target(&mut self, x: &V) -> Target {
            match x {
                V::Port(_) => Target::Port(self.current.clone(), x.clone()),
                V::PortRef(_) => fail(format!("a port of a child can not be a target: {}. Connect it instead: {} -> ...", show(x), show(x))),
                _ if self.is_own(x) => { self.add_vertex(x); Target::Vertex(x.clone()) }
                _ => { let c = self.current.clone(); self.reference(x, &c); Target::Child(x.clone()) }
            }
        }
        fn key(t: &Target) -> (u8, u64, u64) {
            match t { Target::Vertex(v) => (0, vid(v), 0), Target::Child(v) => (1, vid(v), 0), Target::Port(v, p) => (2, vid(v), port_of(p).id) }
        }
        fn resolve(&mut self, t: &Target) -> V {
            let k = Build::key(t);
            if let Some(v) = self.resolved.get(&k) { return v.clone(); }
            if self.resolving.contains(&k) {
                let what = match t { Target::Vertex(v) => show(v), Target::Child(v) => format!("entry of {}", show(v)), Target::Port(v, p) => format!("{}({})", show(p), show(v)) };
                fail(format!("flow cycle without a vertex at {}", what))
            }
            self.resolving.insert(k);
            let v = match t {
                Target::Vertex(v) => v.clone(),
                Target::Child(n) => {
                    let entry = match self.scopes.get(&vid(n)) {
                        None => fail(format!("no flow for {}", show(n))),
                        Some(s) => match &s.entry { None => fail(format!("the flow of {} has no entry", show(n))), Some(e) => e.clone() },
                    };
                    self.resolve(&entry)
                }
                Target::Port(n, p) => self.resolve_port(n, p),
            };
            self.resolving.remove(&k);
            self.resolved.insert(k, v.clone());
            v
        }
        fn resolve_port(&mut self, node: &V, port: &V) -> V {
            let bound = self.bindings.get(&vid(node)).and_then(|ps| ps.iter().find(|(p, _)| eq(p, port)).map(|(_, t)| t.clone()));
            if let Some(t) = bound { return self.resolve(&t); }
            if same(node, &self.root) {
                if let Some((_, v)) = self.exits.iter().find(|(p, _)| eq(p, port)) { return v.clone(); }
                let v = new_node(EXIT_KIND, "", vec![port.clone()]);
                self.exits.push((port.clone(), v.clone()));
                self.add_vertex(&v);
                return v;
            }
            let parent = self.flow_parent[&vid(node)].clone();
            if port_of(port).inherited { return self.resolve_port(&parent, port); }
            fail(format!("port {} of {} is not connected; connect it in the flow of {}", show(port), show(node), show(&parent)))
        }
    }

    pub fn f_entry(b: &mut Build, t: &V) {
        if b.scope().entry.is_some() { fail(format!("the flow of {} already has an entry", show(&b.current))) }
        let t = b.to_target(t);
        b.scope().entry = Some(t);
    }
    pub fn f_connect(b: &mut Build, source: &V, target: &V, label: &V) {
        let t = b.to_target(target);
        let current = b.current.clone();
        match source {
            V::Port(_) => fail(format!("a port of the node itself can not be connected; only ports of children: {}({})", show(source), show(&current))),
            V::PortRef(r) => {
                if !is_null(label) { fail(format!("only edges from vertices can be labeled: {}", show(source))) }
                b.reference(&r.0, &current);
                b.bind(&r.0, &r.1, t);
            }
            _ if b.is_own(source) => { b.add_vertex(source); b.edges.push((source.clone(), t, label.clone())); }
            _ => {
                if !is_null(label) { fail(format!("only edges from vertices can be labeled: {}", show(source))) }
                b.reference(source, &current);
                b.bind(source, &port_next(), t);
            }
        }
    }
    pub fn f_vertex(b: &mut Build, v: V) -> V { let id = vid(&v); b.scope().own.insert(id); b.add_vertex(&v); v }
    pub fn f_bound(b: &mut Build, port: &V) -> V { let c = b.current.clone(); V::Bool(b.is_bound(&c, port)) }
    pub fn f_sequence(b: &mut Build, l: &V) {
        let a = items(l).clone();
        if a.is_empty() { f_entry(b, &port_next()); return; }
        f_entry(b, &a[0]);
        for k in 0..a.len() - 1 { f_connect(b, &a[k], &a[k + 1], &V::Null); }
        f_connect(b, &a[a.len() - 1], &port_next(), &V::Null);
    }

    fn flow_build(flows: &Flows, graph: usize, root: &V) -> V {
        let mut b = Build {
            root: root.clone(), current: V::Null, scopes: HashMap::new(), flow_parent: HashMap::new(), bindings: HashMap::new(),
            queue: VecDeque::new(), vertices: Vec::new(), vertex_set: HashSet::new(), exits: Vec::new(), edges: Vec::new(),
            resolved: HashMap::new(), resolving: HashSet::new(),
        };
        b.queue.push_back(root.clone());
        while let Some(n) = b.queue.pop_front() {
            if b.scopes.contains_key(&vid(&n)) { continue; }
            b.scopes.insert(vid(&n), ScopeData::default());
            b.current = n.clone();
            if !(flows.rule)(graph, &mut b, &n) {
                let parent = b.flow_parent.get(&vid(&n)).map(|p| format!(", used in the flow of {}", node_of(p).name)).unwrap_or_default();
                fail(format!("no flow defined for {}{}", node_of(&n).name, parent))
            }
        }
        let entry = b.resolve(&Target::Child(root.clone()));
        let specs = std::mem::take(&mut b.edges);
        let mut edges = Vec::new();
        for (f, t, l) in specs { let to = b.resolve(&t); edges.push(new_edge(f, to, l)); }
        let mut vs = vec![entry.clone()];
        vs.extend(b.vertices.iter().cloned());
        make_graph(root.clone(), entry, &vs, &edges, b.exits.clone())
    }

    thread_local! {
        static FLOW_GRAPHS: RefCell<HashMap<(usize, u64), V>> = RefCell::new(HashMap::new());
        static FLOW_OWNERS: RefCell<HashMap<(usize, u64), V>> = RefCell::new(HashMap::new());
    }
    pub fn fg_get(flows: &Flows, g: usize, root: &V) -> V {
        if let Some(x) = FLOW_GRAPHS.with(|m| m.borrow().get(&(g, vid(root))).cloned()) { return x; }
        let graph = flow_build(flows, g, root);
        FLOW_GRAPHS.with(|m| m.borrow_mut().insert((g, vid(root)), graph.clone()));
        for v in &graph_of(&graph).vertices { FLOW_OWNERS.with(|m| m.borrow_mut().insert((g, vid(v)), graph.clone())); }
        graph
    }
    pub fn fg_of(flows: &Flows, g: usize, v: &V) -> V {
        if let Some(x) = FLOW_OWNERS.with(|m| m.borrow().get(&(g, vid(v))).cloned()) { return x; }
        let mut current = v.clone();
        while !is_null(&current) {
            if (flows.is_root)(g, &current) { return fg_get(flows, g, &current); }
            current = parent(&current);
        }
        V::Null
    }
    pub fn fg_succ(flows: &Flows, g: usize, v: &V) -> V { let graph = fg_of(flows, g, v); if is_null(&graph) { list(vec![]) } else { g_succ(&graph, v) } }
    pub fn fg_pred(flows: &Flows, g: usize, v: &V) -> V { let graph = fg_of(flows, g, v); if is_null(&graph) { list(vec![]) } else { g_pred(&graph, v) } }

    // ---- spans ----------------------------------------------------------------------------

    pub static SPAN_FIELDS: [&str; 6] = ["source", "line", "column", "offset", "length", "text"];
    pub fn new_span(source: V, line: V, column: V, offset: V, length: V, text: V) -> V {
        new_rec(SPAN_RID, "Span", &SPAN_FIELDS, false, vec![source, line, column, offset, length, text])
    }
    pub fn span_of(v: &V) -> V { match v { V::Node(n) => n.span.borrow().clone(), _ => V::Null } }

    // ---- regular expressions --------------------------------------------------------------

    /** A program of the backtracking matcher (see RegexProgram in Forge): triples op a b. */
    pub struct Regex { pub code: &'static [i32], pub sets: &'static [&'static [i32]], pub slots: usize }

    fn word_byte(c: u8) -> bool { c.is_ascii_alphanumeric() || c == b'_' }

    fn regex_run(r: &Regex, s: &[u8], pc0: usize, start: usize) -> Option<usize> {
        let n = s.len();
        let mut slots = vec![usize::MAX; r.slots.max(1)];
        // entries: (pc, pos) to try, or (-1 - slot, old value) to restore
        let mut stack: Vec<(i64, usize)> = vec![(pc0 as i64, start)];
        while let Some((pc, p)) = stack.pop() {
            if pc < 0 { slots[(-1 - pc) as usize] = p; continue; }
            let (mut pc, mut p) = (pc as usize, p);
            loop {
                let (op, a, b) = (r.code[3 * pc], r.code[3 * pc + 1], r.code[3 * pc + 2]);
                let ok = match op {
                    0 => if p < n && s[p] as i32 == a { p += 1; pc += 1; true } else { false },
                    1 => if p < n && s[p] != b'\n' && s[p] != b'\r' { p += 1; pc += 1; true } else { false },
                    2 => {
                        if p < n {
                            let c = s[p] as i32;
                            let set = r.sets[a as usize];
                            let found = set.chunks(2).any(|w| c >= w[0] && c <= w[1]);
                            if found != (b == 1) { p += 1; pc += 1; true } else { false }
                        } else { false }
                    }
                    3 => { stack.push((b as i64, p)); pc = a as usize; true }
                    4 => { pc = a as usize; true }
                    5 => return Some(p),
                    6 => { let matched = regex_run(r, s, pc + 2, p).is_some(); if matched != (a == 1) { pc += 1; true } else { false } }
                    7 => if p == 0 { pc += 1; true } else { false },
                    8 => if p == n || (p + 1 == n && s[p] == b'\n') { pc += 1; true } else { false },
                    9 | 10 => {
                        let before = p > 0 && word_byte(s[p - 1]);
                        let after = p < n && word_byte(s[p]);
                        if (before != after) == (op == 9) { pc += 1; true } else { false }
                    }
                    11 => { stack.push((-1 - a as i64, slots[a as usize])); slots[a as usize] = p; pc += 1; true }
                    12 => if slots[a as usize] == p { false } else { pc += 1; true },
                    _ => fail("bad regular expression program"),
                };
                if !ok { break; }
            }
        }
        None
    }

    pub fn regex_match(r: &Regex, s: &[u8], start: usize) -> Option<usize> { regex_run(r, s, 0, start) }

    // ==== parser runtime ======================================================================

    /** A failed parse: where it failed and what would have been accepted there. */
    #[derive(Debug, Clone)]
    pub struct ParseError {
        pub message: String,
        /** Character offset for text input, bit offset for binary input. */
        pub offset: usize,
        /** 1-based line and column (0 for binary input). */
        pub line: usize,
        pub column: usize,
        pub expected: Vec<String>,
    }

    pub struct Mismatch;
    pub type R = Result<V, Mismatch>;
    pub type P<'a> = dyn Fn(&mut PScope) -> R + 'a;
    type Body = Rc<dyn Fn(&mut PScope) -> R>;
    type Extend = Rc<dyn Fn(&mut PScope, &V) -> R>;

    pub struct Label { name: &'static str, value: RefCell<Option<V>> }
    impl Label {
        pub fn new(name: &'static str) -> Label { Label { name, value: RefCell::new(None) } }
        pub fn get(&self) -> V { self.value.borrow().clone().unwrap_or_else(|| fail(format!("label '{}' was not matched", self.name))) }
    }

    pub struct Terminal { pub re: Regex, pub literal: Option<&'static str>, pub display: &'static str }
    pub struct Bits { pub bits: &'static [bool], pub display: &'static str }

    pub struct Alt { precedence: i32, operand: Option<i32>, parse: Option<Body>, extend: Option<Extend> }
    pub struct Rule { pub name: &'static str, pub is_abstract: bool, pub body: Option<Body>, alternatives: Vec<Alt>, operators: Vec<Alt> }
    impl Rule {
        pub fn new(name: &'static str, body: Option<Body>) -> Rule { Rule { name, is_abstract: body.is_none(), body, alternatives: Vec::new(), operators: Vec::new() } }
        pub fn on(&mut self, p: Body) { self.alternatives.push(Alt { precedence: i32::MAX, operand: None, parse: Some(p), extend: None }); }
        pub fn prefix(&mut self, prec: i32, p: Body) { self.alternatives.push(Alt { precedence: prec, operand: Some(prec), parse: Some(p), extend: None }); }
        pub fn infix_left(&mut self, prec: i32, e: Extend) { self.operators.push(Alt { precedence: prec, operand: Some(prec + 1), parse: None, extend: Some(e) }); }
        pub fn infix_right(&mut self, prec: i32, e: Extend) { self.operators.push(Alt { precedence: prec, operand: Some(prec), parse: None, extend: Some(e) }); }
        pub fn postfix(&mut self, prec: i32, e: Extend) { self.operators.push(Alt { precedence: prec, operand: Some(prec), parse: None, extend: Some(e) }); }
    }

    pub struct Grammar { pub skip: Option<Regex>, pub keywords: &'static [&'static str], pub terminals: Vec<Terminal>, pub bits: Vec<Bits>, pub rules: Vec<Rule> }

    #[derive(Clone)]
    struct Memo { ok: bool, value: V, end: usize, head: Option<u64> }

    fn word_char(c: u8) -> bool { word_byte(c) || c >= 128 }

    pub struct PScope {
        g: Rc<Grammar>,
        text: Vec<u8>,
        binary: bool,
        unit: usize,
        size: usize,
        pos: usize,
        memo: HashMap<(usize, usize, i32), Memo>,
        stack: Vec<u64>,
        left_recursive: HashSet<u64>,
        involved: HashSet<u64>,
        furthest: i64,
        expected: Vec<String>,
        operators: Vec<Option<(usize, i32)>>,
        frames: u64,
    }

    impl PScope {
        fn mismatch(&mut self, what: &str) -> Mismatch {
            if self.pos as i64 > self.furthest { self.furthest = self.pos as i64; self.expected.clear(); }
            if self.pos as i64 == self.furthest && !self.expected.iter().any(|e| e == what) { self.expected.push(what.to_string()); }
            Mismatch
        }
        fn skip_from(&self, from: usize) -> usize {
            let Some(skip) = &self.g.skip else { return from };
            if from % self.unit != 0 { return from; }
            let mut p = from;
            loop {
                match regex_match(skip, &self.text, p / self.unit) {
                    Some(e) if e != p / self.unit => p = e * self.unit,
                    _ => return p,
                }
            }
        }
        pub fn terminal(&mut self, t: usize) -> R {
            let g = self.g.clone();
            let t = &g.terminals[t];
            self.pos = self.skip_from(self.pos);
            if self.pos % self.unit != 0 { return Err(self.mismatch(t.display)); }
            let start = self.pos / self.unit;
            let Some(e) = regex_match(&t.re, &self.text, start) else { return Err(self.mismatch(t.display)) };
            let value = String::from_utf8_lossy(&self.text[start..e]).to_string();
            match t.literal {
                Some(lit) => {
                    if let Some(&last) = lit.as_bytes().last() {
                        if word_char(last) && e < self.text.len() && word_char(self.text[e]) { return Err(self.mismatch(t.display)); }
                    }
                }
                None => if g.keywords.iter().any(|k| *k == value) { return Err(self.mismatch(t.display)); },
            }
            self.pos = e * self.unit;
            Ok(s(&value))
        }
        fn require_binary(&self) { if !self.binary { fail("bit and byte primitives need binary input") } }
        fn bit_at(&self, p: usize) -> bool { (self.text[p >> 3] >> (7 - (p & 7))) & 1 == 1 }
        pub fn bits(&mut self, b: usize) -> R {
            self.require_binary();
            let g = self.g.clone();
            let b = &g.bits[b];
            let w = b.bits.len();
            if self.pos + w > self.size { return Err(self.mismatch(b.display)); }
            for k in 0..w { if self.bit_at(self.pos + k) != b.bits[k] { return Err(self.mismatch(b.display)); } }
            self.pos += w;
            Ok(s(b.display))
        }
        pub fn uint(&mut self, width: &V) -> R {
            let w = int_of(width);
            self.require_binary();
            if !(0..=32).contains(&w) { fail("width must be between 0 and 32") }
            let w = w as usize;
            if self.pos + w > self.size { return Err(self.mismatch(&format!("{} more bits", w))); }
            let mut v: u64 = 0;
            for k in 0..w { v = (v << 1) | (self.bit_at(self.pos + k) as u64); }
            self.pos += w;
            Ok(V::Int(v as u32 as i32))
        }
        pub fn sint(&mut self, width: &V) -> R {
            let w = int_of(width);
            if !(1..=32).contains(&w) { fail("width must be between 1 and 32") }
            let mut v = int_of(&self.uint(width)?) as u32 as i64;
            if w < 32 && (v >> (w - 1)) & 1 == 1 { v -= 1i64 << w; }
            Ok(V::Int(v as i32))
        }
        pub fn bit(&mut self) -> R { Ok(V::Bool(int_of(&self.uint(&V::Int(1))?) == 1)) }
        pub fn u1(&mut self) -> R { self.uint(&V::Int(8)) }
        pub fn u2(&mut self) -> R { self.uint(&V::Int(16)) }
        pub fn u4(&mut self) -> R { self.uint(&V::Int(32)) }
        pub fn bytes(&mut self, count: &V) -> R {
            let c = int_of(count) as usize;
            self.require_binary();
            if self.pos + c * 8 > self.size { return Err(self.mismatch(&format!("{} more bytes", c))); }
            let mut r = Vec::new();
            for _ in 0..c { r.push(self.uint(&V::Int(8))?); }
            Ok(list(r))
        }

        pub fn optional(&mut self, body: &P<'_>) -> R {
            let start = self.pos;
            match body(self) { Ok(v) => Ok(v), Err(_) => { self.pos = start; Ok(V::Null) } }
        }
        pub fn many(&mut self, body: &P<'_>) -> R {
            let mut r = Vec::new();
            loop {
                let start = self.pos;
                match body(self) {
                    Ok(v) => { r.push(v); if self.pos == start { return Ok(list(r)); } }
                    Err(_) => { self.pos = start; return Ok(list(r)); }
                }
            }
        }
        pub fn some(&mut self, body: &P<'_>) -> R {
            let first = body(self)?;
            let mut r = vec![first];
            r.extend(items(&self.many(body)?).iter().cloned());
            Ok(list(r))
        }
        pub fn repeat(&mut self, count: &V, body: &P<'_>) -> R {
            let mut r = Vec::new();
            for _ in 0..int_of(count) { r.push(body(self)?); }
            Ok(list(r))
        }
        pub fn and_(&mut self, body: &P<'_>) -> R {
            let start = self.pos;
            let r = body(self);
            self.pos = start;
            r.map(|_| V::Null)
        }
        pub fn not_(&mut self, description: &str, body: &P<'_>) -> R {
            let start = self.pos;
            let matched = body(self).is_ok();
            self.pos = start;
            if matched { Err(self.mismatch(&format!("not {}", description))) } else { Ok(V::Null) }
        }
        pub fn one_of(&mut self, alternatives: &[&P<'_>]) -> R {
            let start = self.pos;
            for a in alternatives {
                match a(self) { Ok(v) => return Ok(v), Err(_) => self.pos = start }
            }
            Err(Mismatch)
        }
        pub fn seq(&mut self, parts: &[(&P<'_>, Option<&Label>)], value_index: i32) -> R {
            let mut values = Vec::new();
            for (parse, label) in parts {
                let x = parse(self)?;
                if let Some(l) = label { *l.value.borrow_mut() = Some(x.clone()); }
                values.push(x);
            }
            Ok(match value_index { -2 => V::Null, -1 => list(values), i => values[i as usize].clone() })
        }

        fn line_of(&self, offset: usize) -> usize { self.text[..offset.min(self.text.len())].iter().filter(|&&c| c == b'\n').count() + 1 }
        fn column_of(&self, offset: usize) -> usize {
            let upto = offset.min(self.text.len());
            match self.text[..upto].iter().rposition(|&c| c == b'\n') { Some(i) => offset - i, None => offset + 1 }
        }

        fn record(&self, value: &V, start: usize, end: usize) {
            let V::Node(n) = value else { return };
            if !is_null(&n.span.borrow()) { return; }
            let from = self.skip_from(start) / self.unit;
            let to = (end + self.unit - 1) / self.unit;
            let span = if self.binary {
                new_span(V::Null, V::Int(0), V::Int(0), V::Int(from as i32), V::Int((to - from) as i32), s(""))
            } else {
                let text = String::from_utf8_lossy(&self.text[from..to.max(from)]).to_string();
                new_span(V::Null, V::Int(self.line_of(from) as i32), V::Int(self.column_of(from) as i32), V::Int(from as i32), V::Int((to - from) as i32), s(&text))
            };
            *n.span.borrow_mut() = span;
        }

        pub fn operand(&mut self) -> R {
            match self.operators.last().cloned().flatten() {
                Some((rule, prec)) => self.apply_prec(rule, prec),
                None => fail("an operand outside of an operator"),
            }
        }

        fn in_operator(&mut self, rule: usize, operand: Option<i32>, block: impl FnOnce(&mut PScope) -> R) -> R {
            self.operators.push(operand.map(|p| (rule, p)));
            let r = block(self);
            self.operators.pop();
            r
        }

        fn climb(&mut self, rule: usize, min_precedence: i32) -> R {
            let g = self.g.clone();
            let r = &g.rules[rule];
            let start = self.pos;
            let mut left = None;
            for a in &r.alternatives {
                let parse = a.parse.clone().unwrap();
                match self.in_operator(rule, a.operand, |s| parse(s)) {
                    Ok(v) => { self.record(&v, start, self.pos); left = Some(v); break; }
                    Err(_) => self.pos = start,
                }
            }
            let Some(mut left) = left else {
                if r.alternatives.is_empty() { fail(format!("abstract rule '{}' has no alternatives", r.name)) }
                return Err(Mismatch);
            };
            loop {
                let mut extended_any = false;
                for op in &r.operators {
                    if op.precedence < min_precedence { continue; }
                    let before = self.pos;
                    let extend = op.extend.clone().unwrap();
                    let current = left.clone();
                    match self.in_operator(rule, op.operand, |s| extend(s, &current)) {
                        Ok(x) => {
                            if self.pos == before { continue; }
                            self.record(&x, start, self.pos);
                            left = x;
                            extended_any = true;
                            break;
                        }
                        Err(_) => self.pos = before,
                    }
                }
                if !extended_any { return Ok(left); }
            }
        }

        fn run(&mut self, rule: usize, precedence: i32) -> R {
            let g = self.g.clone();
            match &g.rules[rule].body { Some(body) => body(self), None => self.climb(rule, precedence) }
        }

        fn attempt(&mut self, rule: usize, precedence: i32, start: usize, key: (usize, usize, i32)) -> bool {
            self.pos = start;
            let r = self.run(rule, precedence);
            let end = self.pos;
            let m = self.memo.get_mut(&key).unwrap();
            match r { Ok(v) => { m.value = v; m.end = end; m.ok = true; } Err(_) => m.ok = false }
            m.ok
        }

        pub fn apply(&mut self, rule: usize) -> R { self.apply_prec(rule, i32::MIN) }

        /** Applies a rule with memoization and left recursion (seed growing). */
        pub fn apply_prec(&mut self, rule: usize, precedence: i32) -> R {
            let start = self.pos;
            let key = (rule, start, precedence);
            if let Some(m) = self.memo.get(&key).cloned() {
                if let Some(h) = m.head {
                    self.left_recursive.insert(h);
                    for f in self.stack.iter().rev().take_while(|f| **f != h) { self.involved.insert(*f); }
                }
                if !m.ok { return Err(Mismatch); }
                self.pos = m.end;
                return Ok(m.value);
            }
            self.frames += 1;
            let frame = self.frames;
            self.memo.insert(key, Memo { ok: false, value: V::Null, end: start, head: Some(frame) });
            self.stack.push(frame);
            self.operators.push(None);
            self.attempt(rule, precedence, start, key);
            if self.left_recursive.contains(&frame) {
                loop {
                    let m = self.memo[&key].clone();
                    if !m.ok { break; }
                    if !self.attempt(rule, precedence, start, key) || self.memo[&key].end <= m.end {
                        self.memo.insert(key, Memo { ok: true, value: m.value, end: m.end, head: Some(frame) });
                        break;
                    }
                }
            }
            self.operators.pop();
            self.stack.pop();
            let result = self.memo[&key].clone();
            if self.involved.contains(&frame) { self.memo.remove(&key); } else { self.memo.get_mut(&key).unwrap().head = None; }
            self.left_recursive.remove(&frame);
            self.involved.remove(&frame);
            if !result.ok { self.pos = start; return Err(Mismatch); }
            self.pos = result.end;
            self.record(&result.value, start, result.end);
            Ok(result.value)
        }

        fn error(&self) -> ParseError {
            let at = self.furthest.max(0) as usize;
            let described = self.expected.join(" or ");
            if !self.binary {
                let found = if at < self.text.len() { format!("'{}'", self.text[at] as char) } else { "end of input".to_string() };
                let (line, column) = (self.line_of(at), self.column_of(at));
                return ParseError { message: format!("{}:{}: expected {}, found {}", line, column, described, found), offset: at, line, column, expected: self.expected.clone() };
            }
            let (b, bit) = (at / 8, at % 8);
            let place = if bit == 0 { format!("byte {}", b) } else { format!("byte {}, bit {}", b, bit) };
            let found = if b < self.text.len() { format!("0x{:02X}", self.text[b]) } else { "end of input".to_string() };
            ParseError { message: format!("{}: expected {}, found {}", place, described, found), offset: at, line: 0, column: 0, expected: self.expected.clone() }
        }

        fn parse_all(&mut self, rule: usize) -> Result<V, ParseError> {
            if let Ok(result) = self.apply(rule) {
                self.pos = self.skip_from(self.pos);
                if self.pos == self.size { return Ok(result); }
                if self.pos as i64 > self.furthest { self.furthest = self.pos as i64; self.expected.clear(); }
                if self.pos as i64 == self.furthest && !self.expected.iter().any(|e| e == "end of input") { self.expected.push("end of input".into()); }
            }
            Err(self.error())
        }
    }

    fn new_scope(g: Rc<Grammar>, text: Vec<u8>, binary: bool) -> PScope {
        let unit = if binary { 8 } else { 1 };
        let size = text.len() * unit;
        PScope { g, text, binary, unit, size, pos: 0, memo: HashMap::new(), stack: Vec::new(), left_recursive: HashSet::new(),
                 involved: HashSet::new(), furthest: -1, expected: Vec::new(), operators: Vec::new(), frames: 0 }
    }

    /** Parses all of the input; a failed parse panics with a [ParseError], see [catch_parse]. */
    pub fn parse_text(g: Rc<Grammar>, rule: usize, input: &V) -> V {
        match new_scope(g, str_of(input).as_bytes().to_vec(), false).parse_all(rule) { Ok(v) => v, Err(e) => std::panic::panic_any(e) }
    }
    pub fn parse_binary(g: Rc<Grammar>, rule: usize, input: &V) -> V {
        let bytes = items(input).iter().map(|b| (int_of(b) & 0xFF) as u8).collect();
        match new_scope(g, bytes, true).parse_all(rule) { Ok(v) => v, Err(e) => std::panic::panic_any(e) }
    }

    /** Runs f; a failed parse in it becomes an Err (other errors still panic). */
    pub fn catch_parse<T>(f: impl FnOnce() -> T) -> Result<T, ParseError> {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(|info| {
            if info.payload().downcast_ref::<ParseError>().is_none() { eprintln!("{}", info); }
        }));
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
        std::panic::set_hook(previous);
        match r {
            Ok(x) => Ok(x),
            Err(e) => match e.downcast::<ParseError>() { Ok(p) => Err(*p), Err(e) => std::panic::resume_unwind(e) },
        }
    }

    /** Runs f on a thread with a stack of [megabytes], for the deep recursion of attribute evaluation. */
    pub fn with_stack<T: Send + 'static>(megabytes: usize, f: impl FnOnce() -> T + Send + 'static) -> T {
        std::thread::Builder::new().stack_size(megabytes << 20).spawn(f).unwrap().join().unwrap_or_else(|e| std::panic::resume_unwind(e))
    }
}
// ==== end of Forge runtime =====================================================================
