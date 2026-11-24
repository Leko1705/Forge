// ==== Forge runtime ============================================================================
// Values, attribute caches, fixpoints of circular attributes, graphs, flows, regular expressions and the
// parser engine. C++17, standard library only.
#ifndef FORGE_RUNTIME_HPP
#define FORGE_RUNTIME_HPP

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <pthread.h>

namespace rt {

/** An error while evaluating: a missing value, no matching case, an attribute that depends on itself. */
struct ForgeError : std::runtime_error {
    explicit ForgeError(const std::string& m) : std::runtime_error(m) {}
};

[[noreturn]] inline void fail(const std::string& message) { throw ForgeError(message); }

struct V;
using List = std::vector<V>;
struct MapData;
struct Rec;
struct Node;
struct Graph;
struct Edge;
struct Port;
struct PortRef;

/** A value of Forge. Lists, maps and records are immutable; nodes are compared by identity. */
struct V {
    enum Tag : uint8_t { NUL, INT, FLOAT, BOOL, STR, LIST, SET, MAP, REC, NODE, GRAPH, EDGE, PORT, PORTREF, FLOWGRAPH, NOMATCH, LONG, FN };
    Tag tag = NUL;
    union { int32_t i; float f; bool b; int64_t l; };
    std::shared_ptr<void> p;
    V() : i(0) {}

    template <class T> T& as() const { return *static_cast<T*>(p.get()); }
    bool is_null() const { return tag == NUL; }
};

inline V make(V::Tag tag, std::shared_ptr<void> p) { V v; v.tag = tag; v.p = std::move(p); return v; }
inline V Null() { return V(); }
inline V NoMatch() { V v; v.tag = V::NOMATCH; return v; }
inline V I(int32_t x) { V v; v.tag = V::INT; v.i = x; return v; }
inline V Lg(int64_t x) { V v; v.tag = V::LONG; v.l = x; return v; }
inline V F(float x) { V v; v.tag = V::FLOAT; v.f = x; return v; }
inline V B(bool x) { V v; v.tag = V::BOOL; v.b = x; return v; }
inline V S(std::string s) { return make(V::STR, std::make_shared<std::string>(std::move(s))); }
inline V L(List items) { return make(V::LIST, std::make_shared<List>(std::move(items))); }

struct MapData { std::vector<std::pair<V, V>> entries; };
/** A function value: it receives its arguments as a list. */
using Fn = std::function<V(const List&)>;
inline V fn(Fn f) { return make(V::FN, std::make_shared<Fn>(std::move(f))); }
struct Rec { int rid; std::string name; std::vector<std::string> names; std::vector<V> fields; bool item; };
struct Node {
    int kind;
    int64_t id;
    std::vector<V> fields;
    V parent;   // the parent node, or null
    V span;
    std::unordered_map<int, V> cache;
};
struct Edge { V from, to, label; };
struct Port { std::string name; bool inherited; int id; };
struct PortRef { V node; V port; };

constexpr int EXIT_KIND = -1;
constexpr int SYNTHETIC_KIND = -2;
constexpr int SPAN_RID = -1;

inline std::vector<std::string>& kind_names() { static std::vector<std::string> names; return names; }
inline int64_t next_id() { static int64_t counter = 0; return ++counter; }

inline bool truth(const V& v) { if (v.tag != V::BOOL) fail("a bool was expected"); return v.b; }
inline int32_t int_of(const V& v) { if (v.tag != V::INT) fail("an int was expected"); return v.i; }
inline int64_t long_of(const V& v) { if (v.tag != V::LONG) fail("a long was expected"); return v.l; }
inline V call(const V& f, const List& args) { if (f.tag != V::FN) fail("a function was expected"); return f.as<Fn>()(args); }
inline float float_of(const V& v) { if (v.tag != V::FLOAT) fail("a float was expected"); return v.f; }
inline const std::string& str_of(const V& v) { if (v.tag != V::STR) fail("a str was expected"); return v.as<std::string>(); }
/** The elements of a list or a set (without duplicates, in the order they were added). */
inline const List& items(const V& v) { if (v.tag != V::LIST && v.tag != V::SET) fail("a list was expected"); return v.as<List>(); }
inline const std::vector<std::pair<V, V>>& entries(const V& v) { if (v.tag != V::MAP) fail("a map was expected"); return v.as<MapData>().entries; }
// the elements of a temporary would be destroyed with it: `for (auto& x : items(f()))` does not compile
const List& items(const V&& v) = delete;
const std::vector<std::pair<V, V>>& entries(const V&& v) = delete;
inline Node& node_of(const V& v) { if (v.tag != V::NODE) fail("a node was expected"); return v.as<Node>(); }

// ---- arithmetic -----------------------------------------------------------------------------

inline int32_t wrap(int64_t x) { return static_cast<int32_t>(static_cast<uint32_t>(x)); }
// longs: the same operations on 64 bits (unsigned, so that overflow wraps)
inline int64_t lw(uint64_t x) { return static_cast<int64_t>(x); }
inline uint64_t lu(const V& a) { return static_cast<uint64_t>(long_of(a)); }
inline V add(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(lw(lu(a) + lu(b))); return I(wrap(int64_t(int_of(a)) + int_of(b))); }
inline V sub(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(lw(lu(a) - lu(b))); return I(wrap(int64_t(int_of(a)) - int_of(b))); }
inline V mul(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(lw(lu(a) * lu(b))); return I(wrap(int64_t(int_of(a)) * int_of(b))); }
inline V div(const V& a, const V& b) {
    if (a.tag == V::LONG) {
        int64_t d = long_of(b);
        if (d == 0) fail("/ by zero");
        if (d == -1) return Lg(lw(0 - lu(a)));
        return Lg(long_of(a) / d);
    }
    int32_t d = int_of(b);
    if (d == 0) fail("/ by zero");
    if (d == -1) return I(wrap(-int64_t(int_of(a))));
    return I(int_of(a) / d);
}
inline V rem(const V& a, const V& b) {
    if (a.tag == V::LONG) {
        int64_t d = long_of(b);
        if (d == 0) fail("/ by zero");
        if (d == -1) return Lg(0);
        return Lg(long_of(a) % d);
    }
    int32_t d = int_of(b);
    if (d == 0) fail("/ by zero");
    if (d == -1) return I(0);
    return I(int_of(a) % d);
}
inline V neg(const V& a) { if (a.tag == V::LONG) return Lg(lw(0 - lu(a))); return I(wrap(-int64_t(int_of(a)))); }
inline V band(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(long_of(a) & long_of(b)); return I(int_of(a) & int_of(b)); }
inline V bor(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(long_of(a) | long_of(b)); return I(int_of(a) | int_of(b)); }
inline V bxor(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(long_of(a) ^ long_of(b)); return I(int_of(a) ^ int_of(b)); }
inline V bnot(const V& a) { if (a.tag == V::LONG) return Lg(~long_of(a)); return I(~int_of(a)); }
inline V i2l(const V& a) { return Lg(int_of(a)); }
inline V l2i(const V& a) { return I(static_cast<int32_t>(static_cast<uint32_t>(lu(a)))); }
inline V l2f(const V& a) { return F(static_cast<float>(long_of(a))); }
inline V fadd(const V& a, const V& b) { return F(float_of(a) + float_of(b)); }
inline V fsub(const V& a, const V& b) { return F(float_of(a) - float_of(b)); }
inline V fmul(const V& a, const V& b) { return F(float_of(a) * float_of(b)); }
inline V fdiv(const V& a, const V& b) { return F(float_of(a) / float_of(b)); }
inline V fneg(const V& a) { return F(-float_of(a)); }
inline V i2f(const V& a) { return F(static_cast<float>(int_of(a))); }
inline V f2i(const V& a) {
    float f = float_of(a);
    if (std::isnan(f)) return I(0);
    if (f >= 2147483647.0f) return I(2147483647);
    if (f <= -2147483648.0f) return I(-2147483647 - 1);
    return I(static_cast<int32_t>(f));
}
inline V f2l(const V& a) {
    float f = float_of(a);
    if (std::isnan(f)) return Lg(0);
    if (f >= 9223372036854775807.0f) return Lg(INT64_MAX);
    if (f <= -9223372036854775808.0f) return Lg(INT64_MIN);
    return Lg(static_cast<int64_t>(f));
}
inline V concat(const V& a, const V& b) { return S(str_of(a) + str_of(b)); }

// ---- equality and text ----------------------------------------------------------------------

inline bool eq(const V& a, const V& b) {
    if (a.tag != b.tag) return false;
    switch (a.tag) {
        case V::NUL: case V::NOMATCH: return true;
        case V::INT: return a.i == b.i;
        case V::LONG: return a.l == b.l;
        case V::FN: return a.p == b.p;
        case V::FLOAT: return a.f == b.f || (std::isnan(a.f) && std::isnan(b.f));
        case V::BOOL: return a.b == b.b;
        case V::STR: return a.as<std::string>() == b.as<std::string>();
        case V::LIST: {
            const List& x = a.as<List>(); const List& y = b.as<List>();
            if (x.size() != y.size()) return false;
            for (size_t k = 0; k < x.size(); k++) if (!eq(x[k], y[k])) return false;
            return true;
        }
        case V::SET: {
            const List& x = a.as<List>(); const List& y = b.as<List>();
            if (x.size() != y.size()) return false;
            for (auto& e : x) {
                bool found = false;
                for (auto& f : y) if (eq(e, f)) { found = true; break; }
                if (!found) return false;
            }
            return true;
        }
        case V::MAP: {
            auto& x = a.as<MapData>().entries; auto& y = b.as<MapData>().entries;
            if (x.size() != y.size()) return false;
            for (auto& e : x) {
                bool found = false;
                for (auto& f : y) if (eq(e.first, f.first)) { if (!eq(e.second, f.second)) return false; found = true; break; }
                if (!found) return false;
            }
            return true;
        }
        case V::REC: {
            Rec& x = a.as<Rec>(); Rec& y = b.as<Rec>();
            if (x.rid != y.rid || x.name != y.name || x.fields.size() != y.fields.size()) return false;
            for (size_t k = 0; k < x.fields.size(); k++) if (!eq(x.fields[k], y.fields[k])) return false;
            return true;
        }
        case V::FLOWGRAPH: return a.i == b.i;
        case V::PORTREF: return eq(a.as<PortRef>().node, b.as<PortRef>().node) && a.as<PortRef>().port.p == b.as<PortRef>().port.p;
        default: return a.p == b.p;
    }
}

inline bool same(const V& a, const V& b) { return a.tag == V::NODE && b.tag == V::NODE && a.p == b.p; }

inline std::string show_float(float f) {
    if (std::isnan(f)) return "NaN";
    if (std::isinf(f)) return f > 0 ? "Infinity" : "-Infinity";
    if (f == 0.0f) return std::signbit(f) ? "-0.0" : "0.0";
    char buf[64];
    for (int digits = 1; digits <= 9; digits++) {
        std::snprintf(buf, sizeof buf, "%.*e", digits - 1, static_cast<double>(f));
        if (std::strtof(buf, nullptr) == f) break;
    }
    std::string s = buf;
    size_t e = s.find('e');
    std::string mantissa = s.substr(0, e);
    int exponent = std::atoi(s.c_str() + e + 1);
    bool negative = mantissa[0] == '-';
    std::string digits;
    for (char c : mantissa) if (c >= '0' && c <= '9') digits += c;
    std::string text;
    int n = static_cast<int>(digits.size());
    if (exponent >= -3 && exponent < 7) {
        if (exponent >= 0) {
            std::string whole = n > exponent + 1 ? digits.substr(0, exponent + 1) : digits + std::string(exponent + 1 - n, '0');
            std::string fraction = n > exponent + 1 ? digits.substr(exponent + 1) : "0";
            text = whole + "." + fraction;
        } else {
            text = "0." + std::string(-exponent - 1, '0') + digits;
        }
    } else {
        text = digits.substr(0, 1) + "." + (n > 1 ? digits.substr(1) : "0") + "E" + std::to_string(exponent);
    }
    return (negative ? "-" : "") + text;
}

inline std::string show(const V& v);

inline std::string join(const List& xs) {
    std::string r;
    for (size_t k = 0; k < xs.size(); k++) { if (k) r += ", "; r += show(xs[k]); }
    return r;
}

inline std::string show(const V& v) {
    switch (v.tag) {
        case V::NUL: return "null";
        case V::INT: return std::to_string(v.i);
        case V::LONG: return std::to_string(v.l);
        case V::FN: return "<function>";
        case V::FLOAT: return show_float(v.f);
        case V::BOOL: return v.b ? "true" : "false";
        case V::STR: return v.as<std::string>();
        case V::LIST: case V::SET: return "[" + join(v.as<List>()) + "]";
        case V::MAP: {
            std::string r = "{";
            bool first = true;
            for (auto& e : v.as<MapData>().entries) { if (!first) r += ", "; first = false; r += show(e.first) + "=" + show(e.second); }
            return r + "}";
        }
        case V::REC: {
            Rec& r = v.as<Rec>();
            if (r.rid == SPAN_RID) return (r.fields[0].is_null() ? "" : show(r.fields[0]) + ":") + show(r.fields[1]) + ":" + show(r.fields[2]);
            if (r.item && r.fields.empty()) return r.name;
            std::string s = r.name + "(";
            for (size_t k = 0; k < r.fields.size(); k++) { if (k) s += ", "; s += r.names[k] + "=" + show(r.fields[k]); }
            return s + ")";
        }
        case V::NODE: {
            Node& n = v.as<Node>();
            if (n.kind == EXIT_KIND) return "exit(" + show(n.fields[0]) + ")";
            if (n.kind == SYNTHETIC_KIND) return show(n.fields[0]);
            return kind_names()[n.kind] + "(" + join(n.fields) + ")";
        }
        case V::GRAPH: return "Graph";
        case V::EDGE: {
            Edge& e = v.as<Edge>();
            return e.label.is_null() ? show(e.from) + " -> " + show(e.to) : show(e.from) + " -[" + show(e.label) + "]-> " + show(e.to);
        }
        case V::PORT: return v.as<Port>().name;
        case V::PORTREF: return show(v.as<PortRef>().port) + "(" + show(v.as<PortRef>().node) + ")";
        case V::FLOWGRAPH: return "FlowGraph";
        case V::NOMATCH: return "<no match>";
    }
    return "?";
}

// ---- lists, maps, records -------------------------------------------------------------------

inline V nn(const V& v) { if (v.is_null()) fail("null value where a value is required (!!)"); return v; }
template <class F> V elvis(const V& v, F alt) { return v.is_null() ? alt() : v; }

/** A position for [n] elements: below 0 counts from the end. */
inline long position(long i, long n) {
    long k = i < 0 ? i + n : i;
    if (k < 0 || k >= n) fail("index " + std::to_string(i) + " out of bounds for length " + std::to_string(n));
    return k;
}
/** A bound of a slice: below 0 counts from the end, clamped to 0..n; null is the default. */
inline long bound(const V& b, long n, long dflt) {
    long k = b.is_null() ? dflt : (int_of(b) < 0 ? int_of(b) + n : int_of(b));
    return std::max(0L, std::min(n, k));
}
inline V get(const V& l, const V& i) {
    const List& a = items(l);
    return a[position(int_of(i), static_cast<long>(a.size()))];
}
inline V size(const V& v) {
    if (v.tag == V::LIST || v.tag == V::SET) return I(static_cast<int32_t>(v.as<List>().size()));
    if (v.tag == V::MAP) return I(static_cast<int32_t>(v.as<MapData>().entries.size()));
    if (v.tag == V::STR) return I(static_cast<int32_t>(v.as<std::string>().size()));
    fail("size of a value that is no list, set, map or str");
}
inline V slice(const V& v, const V& lo, const V& hi) {
    if (v.tag == V::STR) {
        const std::string& s = v.as<std::string>();
        long n = static_cast<long>(s.size()), a = bound(lo, n, 0), b = bound(hi, n, n);
        return S(a >= b ? "" : s.substr(a, b - a));
    }
    const List& l = items(v);
    long n = static_cast<long>(l.size()), a = bound(lo, n, 0), b = bound(hi, n, n);
    return L(a >= b ? List() : List(l.begin() + a, l.begin() + b));
}
inline V char_at(const V& s, const V& i) { const std::string& t = str_of(s); return S(std::string(1, t[position(int_of(i), static_cast<long>(t.size()))])); }
inline V range(const V& a, const V& b) { List r; for (int32_t i = int_of(a); i < int_of(b); i++) r.push_back(I(i)); return L(r); }
inline V shl(const V& a, const V& b) {
    if (a.tag == V::LONG) return Lg(lw(lu(a) << (int_of(b) & 63)));
    return I(static_cast<int32_t>(static_cast<uint32_t>(int_of(a)) << (int_of(b) & 31)));
}
inline V shr(const V& a, const V& b) { if (a.tag == V::LONG) return Lg(long_of(a) >> (int_of(b) & 63)); return I(int_of(a) >> (int_of(b) & 31)); }
inline V ushr(const V& a, const V& b) {
    if (a.tag == V::LONG) return Lg(lw(lu(a) >> (int_of(b) & 63)));
    return I(static_cast<int32_t>(static_cast<uint32_t>(int_of(a)) >> (int_of(b) & 31)));
}
inline V tuple_get(const V& t, int index) { return items(t)[index]; }

// ---- sets
inline V make_set(const List& xs) {
    List r;
    for (auto& x : xs) { bool known = false; for (auto& y : r) if (eq(x, y)) { known = true; break; } if (!known) r.push_back(x); }
    return make(V::SET, std::make_shared<List>(std::move(r)));
}
inline V set_contains(const V& s, const V& x) { for (auto& y : items(s)) if (eq(x, y)) return B(true); return B(false); }
inline V set_union(const V& a, const V& b) { List r = items(a); for (auto& x : items(b)) r.push_back(x); return make_set(r); }
inline V set_remove(const V& s, const V& x) { List r; for (auto& y : items(s)) if (!eq(x, y)) r.push_back(y); return make_set(r); }
inline V to_set(const V& l) { return make_set(items(l)); }
inline V to_list(const V& s) { return L(items(s)); }

// ---- builtins of str, List and Map
inline V str_index_of(const V& s, const V& t) { auto i = str_of(s).find(str_of(t)); return I(i == std::string::npos ? -1 : static_cast<int32_t>(i)); }
inline V str_contains(const V& s, const V& t) { return B(str_of(s).find(str_of(t)) != std::string::npos); }
inline V starts_with(const V& s, const V& t) { const std::string& a = str_of(s); const std::string& b = str_of(t); return B(a.size() >= b.size() && a.compare(0, b.size(), b) == 0); }
inline V ends_with(const V& s, const V& t) { const std::string& a = str_of(s); const std::string& b = str_of(t); return B(a.size() >= b.size() && a.compare(a.size() - b.size(), b.size(), b) == 0); }
inline V split(const V& s, const V& sep) {
    const std::string& t = str_of(s); const std::string& p = str_of(sep);
    if (p.empty()) fail("the separator of split is empty");
    List r;
    size_t from = 0;
    while (true) {
        size_t i = t.find(p, from);
        if (i == std::string::npos) { r.push_back(S(t.substr(from))); return L(r); }
        r.push_back(S(t.substr(from, i - from)));
        from = i + p.size();
    }
}
inline V replace(const V& s, const V& a, const V& b) {
    if (str_of(a).empty()) fail("the text to replace is empty");
    V parts = split(s, a);
    std::string r;
    bool first = true;
    for (auto& x : items(parts)) { if (!first) r += str_of(b); first = false; r += str_of(x); }
    return S(r);
}
inline bool is_space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f'; }
inline V trim(const V& s) {
    const std::string& t = str_of(s);
    size_t i = 0, j = t.size();
    while (i < j && is_space(t[i])) i++;
    while (j > i && is_space(t[j - 1])) j--;
    return S(t.substr(i, j - i));
}
inline V upper(const V& s) { std::string t = str_of(s); for (auto& c : t) if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 32); return S(t); }
inline V lower(const V& s) { std::string t = str_of(s); for (auto& c : t) if (c >= 'A' && c <= 'Z') c = static_cast<char>(c + 32); return S(t); }
inline V code(const V& s, const V& i) { const std::string& t = str_of(s); return I(static_cast<unsigned char>(t[position(int_of(i), static_cast<long>(t.size()))])); }
inline V utf8(const V& s) { List r; for (unsigned char c : str_of(s)) r.push_back(I(c)); return L(r); }
inline V char_of(const V& code) {
    uint32_t c = static_cast<uint32_t>(int_of(code));
    std::string r;
    if (c < 0x80) r += static_cast<char>(c);
    else if (c < 0x800) { r += static_cast<char>(0xC0 | (c >> 6)); r += static_cast<char>(0x80 | (c & 0x3F)); }
    else if (c < 0x10000) { r += static_cast<char>(0xE0 | (c >> 12)); r += static_cast<char>(0x80 | ((c >> 6) & 0x3F)); r += static_cast<char>(0x80 | (c & 0x3F)); }
    else { r += static_cast<char>(0xF0 | (c >> 18)); r += static_cast<char>(0x80 | ((c >> 12) & 0x3F)); r += static_cast<char>(0x80 | ((c >> 6) & 0x3F)); r += static_cast<char>(0x80 | (c & 0x3F)); }
    return S(r);
}
inline V unescape(const V& s) {
    const std::string& t = str_of(s);
    std::string r;
    for (size_t i = 0; i < t.size(); i++) {
        if (t[i] == '\\' && i + 1 < t.size()) {
            char e = t[++i];
            r += e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == '0' ? '\0' : e;
        } else {
            r += t[i];
        }
    }
    return S(r);
}
inline V to_int(const V& s) {
    const std::string& t = str_of(s);
    size_t from = !t.empty() && (t[0] == '+' || t[0] == '-') ? 1 : 0;
    bool ok = from < t.size();
    for (size_t i = from; i < t.size(); i++) if (t[i] < '0' || t[i] > '9') ok = false;
    if (ok) {
        long long v = 0;
        for (size_t i = from; i < t.size() && v <= 2147483648LL; i++) v = v * 10 + (t[i] - '0');
        if (t[0] == '-') v = -v;
        if (v >= -2147483648LL && v <= 2147483647LL) return I(static_cast<int32_t>(v));
    }
    fail("not an int: \"" + t + "\"");
}
inline V to_long(const V& s) {
    const std::string& t = str_of(s);
    size_t from = !t.empty() && (t[0] == '+' || t[0] == '-') ? 1 : 0;
    bool ok = from < t.size() && t.size() - from <= 19;
    for (size_t i = from; i < t.size(); i++) if (t[i] < '0' || t[i] > '9') ok = false;
    if (ok) {
        // in unsigned arithmetic: 19 digits do not overflow it
        uint64_t v = 0;
        for (size_t i = from; i < t.size(); i++) v = v * 10 + static_cast<uint64_t>(t[i] - '0');
        if (t[0] == '-' && v <= 9223372036854775808ULL) return Lg(lw(0 - v));
        if (t[0] != '-' && v <= 9223372036854775807ULL) return Lg(static_cast<int64_t>(v));
    }
    fail("not a long: \"" + t + "\"");
}
inline V to_float(const V& s) {
    const std::string& t = str_of(s);
    size_t n = t.size(), i = n > 0 && (t[0] == '+' || t[0] == '-') ? 1 : 0;
    auto digits = [&] { size_t start = i; while (i < n && t[i] >= '0' && t[i] <= '9') i++; return i - start; };
    size_t whole = digits(), fraction = 0;
    if (i < n && t[i] == '.') { i++; fraction = digits(); }
    bool ok = whole + fraction > 0;
    if (i < n && (t[i] == 'e' || t[i] == 'E')) {
        i++;
        if (i < n && (t[i] == '+' || t[i] == '-')) i++;
        if (digits() == 0) ok = false;
    }
    if (!ok || i != n) fail("not a float: \"" + t + "\"");
    return F(std::strtof(t.c_str(), nullptr));
}
inline V join_str(const V& l, const V& sep) {
    std::string r;
    bool first = true;
    for (auto& x : items(l)) { if (!first) r += str_of(sep); first = false; r += str_of(x); }
    return S(r);
}
inline V list_index_of(const V& l, const V& x) {
    const List& a = items(l);
    for (size_t i = 0; i < a.size(); i++) if (eq(a[i], x)) return I(static_cast<int32_t>(i));
    return I(-1);
}
inline V reversed(const V& l) { const List& a = items(l); return L(List(a.rbegin(), a.rend())); }
inline V map_keys(const V& m) { List r; for (auto& e : entries(m)) r.push_back(e.first); return L(r); }
inline V map_values(const V& m) { List r; for (auto& e : entries(m)) r.push_back(e.second); return L(r); }
inline V map_remove(const V& m, const V& k) {
    auto d = std::make_shared<MapData>();
    for (auto& e : entries(m)) if (!eq(e.first, k)) d->entries.push_back(e);
    return make(V::MAP, d);
}
inline V list_concat(const V& a, const V& b) { List r = items(a); r.insert(r.end(), items(b).begin(), items(b).end()); return L(std::move(r)); }
inline V list_append(const V& a, const V& x) { List r = items(a); r.push_back(x); return L(std::move(r)); }
inline V tail(const V& l) { const List& a = items(l); return L(List(a.begin() + 1, a.end())); }
inline bool contains(const V& l, const V& x) { for (auto& y : items(l)) if (eq(x, y)) return true; return false; }
template <class F> V map_list(const V& l, F f) { List r; for (auto& x : items(l)) r.push_back(f(x)); return L(std::move(r)); }

inline void put_raw(std::vector<std::pair<V, V>>& m, const V& k, const V& x) {
    for (auto& e : m) if (eq(e.first, k)) { e.second = x; return; }
    m.emplace_back(k, x);
}
inline V M(std::vector<std::pair<V, V>> pairs) {
    auto d = std::make_shared<MapData>();
    for (auto& e : pairs) put_raw(d->entries, e.first, e.second);
    return make(V::MAP, d);
}
inline V map_put(const V& m, const V& k, const V& x) { auto d = std::make_shared<MapData>(); d->entries = entries(m); put_raw(d->entries, k, x); return make(V::MAP, d); }
inline V map_merge(const V& a, const V& b) { auto d = std::make_shared<MapData>(); d->entries = entries(a); for (auto& e : entries(b)) put_raw(d->entries, e.first, e.second); return make(V::MAP, d); }
inline bool map_has(const V& m, const V& k) { for (auto& e : entries(m)) if (eq(e.first, k)) return true; return false; }
inline V map_at(const V& m, const V& k) {
    for (auto& e : entries(m)) if (eq(e.first, k)) return e.second;
    fail("key " + show(k) + " is missing in the map");
}

inline V new_rec(int rid, const char* name, std::vector<std::string> names, bool item, List fields) {
    return make(V::REC, std::make_shared<Rec>(Rec{rid, name, std::move(names), std::move(fields), item}));
}
inline V field(const V& v, int k) {
    if (v.tag == V::REC) return v.as<Rec>().fields[k];
    if (v.tag == V::NODE) return v.as<Node>().fields[k];
    fail("a record was expected, got " + show(v));
}
inline bool rec_is(const V& v, int rid) { return v.tag == V::REC && v.as<Rec>().rid == rid; }

// ---- nodes ----------------------------------------------------------------------------------

inline int kind(const V& v) { if (v.tag != V::NODE) fail("not a vertex: " + show(v)); return v.as<Node>().kind; }
inline bool kind_in(const V& v, std::initializer_list<int> kinds) {
    if (v.tag != V::NODE) return false;
    int k = v.as<Node>().kind;
    for (int x : kinds) if (x == k) return true;
    return false;
}
inline bool is_vertex(const V& v) { return v.tag == V::NODE; }

inline std::vector<V> children(const Node& n) {
    std::vector<V> r;
    for (auto& f : n.fields) {
        if (f.tag == V::NODE) r.push_back(f);
        else if (f.tag == V::LIST) for (auto& x : f.as<List>()) if (x.tag == V::NODE) r.push_back(x);
    }
    return r;
}

inline V new_node(int kind, List fields) {
    auto n = std::make_shared<Node>();
    n->kind = kind;
    n->id = next_id();
    n->fields = std::move(fields);
    V v = make(V::NODE, n);
    for (auto& c : children(*n)) c.as<Node>().parent = v;
    return v;
}

inline V root(V v) { while (!node_of(v).parent.is_null()) v = node_of(v).parent; return v; }

/** The nodes of the tree below a node (including it), parents before children. */
inline std::vector<V> preorder(const V& n) {
    std::vector<V> result, work{n};
    while (!work.empty()) {
        V x = work.back();
        work.pop_back();
        result.push_back(x);
        auto c = children(x.as<Node>());
        for (auto it = c.rbegin(); it != c.rend(); ++it) work.push_back(*it);
    }
    return result;
}

inline int index_of_child(const V& list, const V& child) {
    const List& a = items(list);
    for (size_t k = 0; k < a.size(); k++) if (same(a[k], child)) return static_cast<int>(k);
    return -1;
}

// ---- caches and fixpoints -------------------------------------------------------------------

struct Circular;
struct Cycle { int iteration = 0; bool changed = false; std::vector<Circular*> attributes; };
inline Cycle*& current_cycle() { static Cycle* c = nullptr; return c; }

struct Cache {
    int id;
    std::string name;
    explicit Cache(std::string n) : id(static_cast<int>(next_id())), name(std::move(n)) {}
};

/** The value of an attribute, computed once per node. Nothing is cached while a fixpoint is computed. */
template <class F> V cached(const Cache& c, const V& v, F compute) {
    Node& n = node_of(v);
    auto it = n.cache.find(c.id);
    if (it != n.cache.end()) return it->second;
    if (n.cache.count(-c.id)) fail("attribute '" + c.name + "' of " + show(v) + " depends on itself; declare it as circular");
    n.cache[-c.id] = V();
    V value;
    try {
        value = compute();
    } catch (...) {
        n.cache.erase(-c.id);
        throw;
    }
    n.cache.erase(-c.id);
    if (current_cycle() == nullptr) n.cache[c.id] = value;
    return value;
}

/** A circular attribute: a fixpoint, starting at its initial value. */
struct Circular {
    int id = static_cast<int>(next_id());
    std::unordered_map<int64_t, V> approx;
    std::unordered_map<int64_t, std::pair<V, int>> visited;

    V approx_of(const Node& n, const V& initial) { auto it = approx.find(n.id); return it == approx.end() ? initial : it->second; }

    template <class F> V step(const V& v, const V& initial, F& body, Cycle& c) {
        Node& n = v.as<Node>();
        visited[n.id] = {v, c.iteration};
        V old = approx_of(n, initial);
        V value = body();
        if (!eq(value, old) || !approx.count(n.id)) {
            if (!eq(value, old)) c.changed = true;
            approx[n.id] = value;
        }
        return value;
    }

    template <class F> V get(const V& v, const V& initial, F body) {
        Node& n = node_of(v);
        auto it = n.cache.find(id);
        if (it != n.cache.end()) return it->second;
        Cycle* cycle = current_cycle();
        if (cycle != nullptr) {
            bool known = false;
            for (auto* a : cycle->attributes) if (a == this) known = true;
            if (!known) cycle->attributes.push_back(this);
            auto seen = visited.find(n.id);
            if (seen != visited.end() && seen->second.second == cycle->iteration) return approx_of(n, initial);
            return step(v, initial, body, *cycle);
        }
        Cycle c;
        c.attributes.push_back(this);
        current_cycle() = &c;
        auto finish = [&] {
            current_cycle() = nullptr;
            for (auto* a : c.attributes) { a->approx.clear(); a->visited.clear(); }
        };
        try {
            do {
                c.changed = false;
                c.iteration++;
                step(v, initial, body, c);
            } while (c.changed);
            for (auto* a : c.attributes)
                for (auto& e : a->visited)
                    if (e.second.second == c.iteration) e.second.first.as<Node>().cache[a->id] = a->approx[e.first];
            V result = n.cache[id];
            finish();
            return result;
        } catch (...) {
            finish();
            throw;
        }
    }
};

// ---- graphs ---------------------------------------------------------------------------------

struct Graph {
    V root, entry;
    List vertices, edges;
    std::vector<std::pair<V, V>> exits;   // port, vertex
    std::unordered_map<int64_t, int> index;
    std::vector<List> out, in;
};

inline V new_edge(V f, V t, V l) { return make(V::EDGE, std::make_shared<Edge>(Edge{std::move(f), std::move(t), std::move(l)})); }
inline int64_t vid(const V& v) { return node_of(v).id; }

inline V make_graph(V root, V entry, const List& vertices, const List& edges, std::vector<std::pair<V, V>> exits) {
    auto g = std::make_shared<Graph>();
    g->root = std::move(root);
    g->entry = std::move(entry);
    g->edges = edges;
    g->exits = std::move(exits);
    auto add = [&](const V& v) { if (!g->index.count(vid(v))) { g->index[vid(v)] = static_cast<int>(g->vertices.size()); g->vertices.push_back(v); } };
    for (auto& v : vertices) add(v);
    for (auto& e : edges) { add(e.as<Edge>().from); add(e.as<Edge>().to); }
    g->out.resize(g->vertices.size());
    g->in.resize(g->vertices.size());
    for (auto& e : edges) {
        g->out[g->index[vid(e.as<Edge>().from)]].push_back(e);
        g->in[g->index[vid(e.as<Edge>().to)]].push_back(e);
    }
    return make(V::GRAPH, g);
}

inline Graph& graph_of(const V& v) { if (v.tag != V::GRAPH) fail("a graph was expected"); return v.as<Graph>(); }

/** A graph from edges, e.g. a call graph. The vertices are entry and the endpoints of edges. */
inline V graph_from(const V& entry, const V& edges) {
    List vs{entry};
    for (auto& e : items(edges)) { vs.push_back(e.as<Edge>().from); vs.push_back(e.as<Edge>().to); }
    return make_graph(V(), entry, vs, items(edges), {});
}

inline V edge_field(const V& v, int k) { if (v.tag != V::EDGE) fail("an edge was expected"); Edge& e = v.as<Edge>(); return k == 0 ? e.from : k == 1 ? e.to : e.label; }
inline V graph_field(const V& v, int k) {
    Graph& g = graph_of(v);
    switch (k) { case 0: return g.entry; case 1: return L(g.vertices); case 2: return L(g.edges); default: return g.root; }
}

inline int index_in(const Graph& g, const V& v) {
    if (v.tag != V::NODE) return -1;
    auto it = g.index.find(v.as<Node>().id);
    return it == g.index.end() ? -1 : it->second;
}
inline V g_contains(const V& g, const V& v) { return B(index_in(graph_of(g), v) >= 0); }
inline V g_index(const V& g, const V& v) { return I(index_in(graph_of(g), v)); }
inline V g_out(const V& g, const V& v) { int k = index_in(graph_of(g), v); return L(k < 0 ? List() : graph_of(g).out[k]); }
inline V g_in(const V& g, const V& v) { int k = index_in(graph_of(g), v); return L(k < 0 ? List() : graph_of(g).in[k]); }
inline V g_succ(const V& g, const V& v) { V out = g_out(g, v); List r; for (auto& e : items(out)) r.push_back(e.as<Edge>().to); return L(r); }
inline V g_pred(const V& g, const V& v) { V in = g_in(g, v); List r; for (auto& e : items(in)) r.push_back(e.as<Edge>().from); return L(r); }
inline V g_exit(const V& g, const V& port) {
    for (auto& e : graph_of(g).exits) if (e.first.as<Port>().name == str_of(port)) return e.second;
    return V();
}
inline V g_reachable(const V& gv) {
    Graph& g = graph_of(gv);
    std::vector<bool> seen(g.vertices.size());
    List result, work{g.entry};
    while (!work.empty()) {
        V v = work.back();
        work.pop_back();
        int k = index_in(g, v);
        if (k < 0 || seen[k]) continue;
        seen[k] = true;
        result.push_back(v);
        V succ = g_succ(gv, v);
        const List& s = items(succ);
        for (auto it = s.rbegin(); it != s.rend(); ++it) work.push_back(*it);
    }
    return L(result);
}
inline V g_postorder(const V& gv) {
    Graph& g = graph_of(gv);
    std::vector<bool> seen(g.vertices.size());
    List result;
    std::vector<std::pair<V, std::pair<List, size_t>>> stack;
    auto push = [&](const V& v) {
        int k = index_in(g, v);
        if (k < 0 || seen[k]) return;
        seen[k] = true;
        V succ = g_succ(gv, v);
        stack.push_back({v, {items(succ), 0}});
    };
    push(g.entry);
    while (!stack.empty()) {
        auto& top = stack.back();
        if (top.second.second < top.second.first.size()) {
            V c = top.second.first[top.second.second++];
            push(c);
        } else {
            result.push_back(top.first);
            stack.pop_back();
        }
    }
    return L(result);
}
inline V g_rpo(const V& gv) { V post = g_postorder(gv); const List& r = items(post); return L(List(r.rbegin(), r.rend())); }
inline std::string dot_escape(const std::string& s) {
    std::string r;
    for (char c : s) { if (c == '\\' || c == '"') r += '\\'; r += c; }
    return r;
}
inline V g_dot(const V& gv) {
    Graph& g = graph_of(gv);
    std::string s = "digraph G {\n";
    for (size_t k = 0; k < g.vertices.size(); k++) s += "  n" + std::to_string(k) + " [label=\"" + dot_escape(show(g.vertices[k])) + "\"];\n";
    for (auto& e : g.edges) {
        Edge& x = e.as<Edge>();
        s += "  n" + std::to_string(index_in(g, x.from)) + " -> n" + std::to_string(index_in(g, x.to));
        if (!x.label.is_null()) s += " [label=\"" + dot_escape(show(x.label)) + "\"]";
        s += ";\n";
    }
    return S(s + "}");
}

inline V new_port(const char* name, bool inherited) { return make(V::PORT, std::make_shared<Port>(Port{name, inherited, static_cast<int>(next_id())})); }
inline V port_next() { static V p = new_port("next", false); return p; }
inline V port_ref(const V& node, const V& port) { return make(V::PORTREF, std::make_shared<PortRef>(PortRef{node, port})); }
inline V exit_vertex(const V& port) { return new_node(EXIT_KIND, {port}); }
inline V synthetic_vertex(const V& name, const V& owner) { return new_node(SYNTHETIC_KIND, {name, owner}); }
inline V flow_graph(int id) { V v; v.tag = V::FLOWGRAPH; v.i = id; return v; }

// ---- flows ----------------------------------------------------------------------------------

struct Build;
/** A flow target: 0 a vertex, 1 a child (its entry), 2 a port of a node. */
struct Target { int kind; V node; V port; };

struct Scope {
    V node;
    Build* build;
    bool has_entry = false;
    Target entry_spec;
    std::unordered_set<int64_t> own;
};

using FlowRule = bool (*)(int graph, Scope& s, const V& node);
using IsRoot = bool (*)(int graph, const V& node);
inline FlowRule& flow_rule() { static FlowRule r = nullptr; return r; }
inline IsRoot& flow_is_root() { static IsRoot r = nullptr; return r; }

struct Build {
    V root;
    int graph;
    std::unordered_map<int64_t, std::unique_ptr<Scope>> scopes;
    std::unordered_map<int64_t, V> flow_parent;
    std::unordered_map<int64_t, std::vector<std::pair<V, Target>>> bindings;
    std::deque<V> queue;
    List vertices;
    std::unordered_set<int64_t> vertex_set;
    std::vector<std::pair<V, V>> exits;
    std::vector<std::pair<V, std::pair<Target, V>>> edges;
    std::unordered_map<std::string, V> resolved;
    std::unordered_set<std::string> resolving;

    void add_vertex(const V& v) { if (vertex_set.insert(vid(v)).second) vertices.push_back(v); }
    void reference(const V& child, const V& by) {
        if (same(child, root) || flow_parent.count(vid(child))) return;
        flow_parent[vid(child)] = by;
        queue.push_back(child);
    }
    void bind(const V& child, const V& port, const Target& t) {
        auto& ports = bindings[vid(child)];
        for (auto& e : ports) if (e.first.p == port.p) fail("port " + show(port) + " of " + show(child) + " is connected twice");
        ports.push_back({port, t});
    }
    bool is_bound(const V& node, const V& port) {
        auto it = bindings.find(vid(node));
        if (it != bindings.end()) for (auto& e : it->second) if (e.first.p == port.p) return true;
        if (!port.as<Port>().inherited) return false;
        auto p = flow_parent.find(vid(node));
        return p != flow_parent.end() && is_bound(p->second, port);
    }
    static std::string key(const Target& t) {
        return std::to_string(t.kind) + ":" + std::to_string(vid(t.node)) + ":" + (t.kind == 2 ? std::to_string(t.port.as<Port>().id) : "");
    }
    V resolve(const Target& t) {
        std::string k = key(t);
        auto done = resolved.find(k);
        if (done != resolved.end()) return done->second;
        if (resolving.count(k)) {
            std::string what = t.kind == 0 ? show(t.node) : t.kind == 1 ? "entry of " + show(t.node) : show(t.port) + "(" + show(t.node) + ")";
            fail("flow cycle without a vertex at " + what);
        }
        resolving.insert(k);
        V v;
        if (t.kind == 0) v = t.node;
        else if (t.kind == 1) {
            auto s = scopes.find(vid(t.node));
            if (s == scopes.end()) fail("no flow for " + show(t.node));
            if (!s->second->has_entry) fail("the flow of " + show(t.node) + " has no entry");
            v = resolve(s->second->entry_spec);
        } else v = resolve_port(t.node, t.port);
        resolving.erase(k);
        resolved[k] = v;
        return v;
    }
    V resolve_port(const V& node, const V& port) {
        auto it = bindings.find(vid(node));
        if (it != bindings.end()) for (auto& e : it->second) if (e.first.p == port.p) return resolve(e.second);
        if (same(node, root)) {
            for (auto& e : exits) if (e.first.p == port.p) return e.second;
            V v = exit_vertex(port);
            exits.push_back({port, v});
            add_vertex(v);
            return v;
        }
        V parent = flow_parent.at(vid(node));
        if (port.as<Port>().inherited) return resolve_port(parent, port);
        fail("port " + show(port) + " of " + show(node) + " is not connected; connect it in the flow of " + show(parent));
    }
};

inline bool is_own(const Scope& s, const V& x) { return same(x, s.node) || (x.tag == V::NODE && s.own.count(vid(x))); }
inline Target to_target(Scope& s, const V& x) {
    if (x.tag == V::PORT) return Target{2, s.node, x};
    if (x.tag == V::PORTREF) fail("a port of a child can not be a target: " + show(x) + ". Connect it instead: " + show(x) + " -> ...");
    if (is_own(s, x)) { s.build->add_vertex(x); return Target{0, x, V()}; }
    s.build->reference(x, s.node);
    return Target{1, x, V()};
}
inline void f_entry(Scope& s, const V& t) {
    if (s.has_entry) fail("the flow of " + show(s.node) + " already has an entry");
    s.entry_spec = to_target(s, t);
    s.has_entry = true;
}
inline void f_connect(Scope& s, const V& source, const V& target, const V& label) {
    Target t = to_target(s, target);
    if (source.tag == V::PORT) fail("a port of the node itself can not be connected; only ports of children: " + show(source) + "(" + show(s.node) + ")");
    if (source.tag == V::PORTREF) {
        if (!label.is_null()) fail("only edges from vertices can be labeled: " + show(source));
        PortRef& r = source.as<PortRef>();
        s.build->reference(r.node, s.node);
        s.build->bind(r.node, r.port, t);
    } else if (is_own(s, source)) {
        s.build->add_vertex(source);
        s.build->edges.push_back({source, {t, label}});
    } else {
        if (!label.is_null()) fail("only edges from vertices can be labeled: " + show(source));
        s.build->reference(source, s.node);
        s.build->bind(source, port_next(), t);
    }
}
inline V f_vertex(Scope& s, const V& v) { s.own.insert(vid(v)); s.build->add_vertex(v); return v; }
inline V f_bound(Scope& s, const V& port) { return B(s.build->is_bound(s.node, port)); }
inline void f_sequence(Scope& s, const V& list) {
    const List& a = items(list);
    if (a.empty()) { f_entry(s, port_next()); return; }
    f_entry(s, a[0]);
    for (size_t k = 0; k + 1 < a.size(); k++) f_connect(s, a[k], a[k + 1], V());
    f_connect(s, a.back(), port_next(), V());
}

inline V flow_build(int graph, const V& root) {
    Build b;
    b.root = root;
    b.graph = graph;
    b.queue.push_back(root);
    while (!b.queue.empty()) {
        V n = b.queue.front();
        b.queue.pop_front();
        if (b.scopes.count(vid(n))) continue;
        auto scope = std::make_unique<Scope>();
        scope->node = n;
        scope->build = &b;
        Scope& s = *scope;
        b.scopes[vid(n)] = std::move(scope);
        if (!flow_rule()(graph, s, n)) {
            auto p = b.flow_parent.find(vid(n));
            fail("no flow defined for " + kind_names()[kind(n)] + (p == b.flow_parent.end() ? "" : ", used in the flow of " + kind_names()[kind(p->second)]));
        }
    }
    V entry = b.resolve(Target{1, root, V()});
    List edges;
    for (auto& e : b.edges) edges.push_back(new_edge(e.first, b.resolve(e.second.first), e.second.second));
    List vs{entry};
    vs.insert(vs.end(), b.vertices.begin(), b.vertices.end());
    return make_graph(root, entry, vs, edges, b.exits);
}

inline std::unordered_map<std::string, V>& flow_graphs() { static std::unordered_map<std::string, V> m; return m; }
inline std::unordered_map<std::string, V>& flow_owners() { static std::unordered_map<std::string, V> m; return m; }
inline std::string fg_key(int g, const V& v) { return std::to_string(g) + ":" + std::to_string(vid(v)); }

inline V fg_get(int g, const V& root) {
    auto it = flow_graphs().find(fg_key(g, root));
    if (it != flow_graphs().end()) return it->second;
    V graph = flow_build(g, root);
    flow_graphs()[fg_key(g, root)] = graph;
    for (auto& v : graph_of(graph).vertices) flow_owners()[fg_key(g, v)] = graph;
    return graph;
}
inline V fg_of(int g, const V& v) {
    auto it = flow_owners().find(fg_key(g, v));
    if (it != flow_owners().end()) return it->second;
    V current = v;
    while (!current.is_null()) {
        if (flow_is_root()(g, current)) return fg_get(g, current);
        current = node_of(current).parent;
    }
    return V();
}
inline V fg_succ(int g, const V& v) { V graph = fg_of(g, v); return graph.is_null() ? L({}) : g_succ(graph, v); }
inline V fg_pred(int g, const V& v) { V graph = fg_of(g, v); return graph.is_null() ? L({}) : g_pred(graph, v); }

// ---- spans ----------------------------------------------------------------------------------

inline V new_span(V source, V line, V column, V offset, V length, V text) {
    return new_rec(SPAN_RID, "Span", {"source", "line", "column", "offset", "length", "text"}, false,
                   {std::move(source), std::move(line), std::move(column), std::move(offset), std::move(length), std::move(text)});
}
inline V span_of(const V& v) { return v.tag == V::NODE ? v.as<Node>().span : V(); }

// ---- regular expressions --------------------------------------------------------------------

/** A program of the backtracking matcher (see RegexProgram in Forge): triples op a b. */
struct Regex { std::vector<int> code; std::vector<std::vector<int>> sets; int slots; };

inline bool word_byte(unsigned char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }

/** The end of the match of the program at pc in s from start, or -1. */
inline long regex_run(const Regex& r, const std::string& s, int pc0, long start) {
    long n = static_cast<long>(s.size());
    std::vector<long> slots(r.slots > 0 ? r.slots : 1, -1);
    std::vector<std::pair<int, long>> stack{{pc0, start}};
    while (!stack.empty()) {
        auto [pc, p] = stack.back();
        stack.pop_back();
        if (pc < 0) { slots[-1 - pc] = p; continue; }
        while (true) {
            int op = r.code[3 * pc], a = r.code[3 * pc + 1], b = r.code[3 * pc + 2];
            bool ok = true;
            switch (op) {
                case 0: if (p < n && static_cast<unsigned char>(s[p]) == a) { p++; pc++; } else ok = false; break;
                case 1: if (p < n && s[p] != '\n' && s[p] != '\r') { p++; pc++; } else ok = false; break;
                case 2: {
                    if (p >= n) { ok = false; break; }
                    int c = static_cast<unsigned char>(s[p]);
                    bool found = false;
                    const auto& set = r.sets[a];
                    for (size_t k = 0; k + 1 < set.size(); k += 2) if (c >= set[k] && c <= set[k + 1]) { found = true; break; }
                    if (found != (b == 1)) { p++; pc++; } else ok = false;
                    break;
                }
                case 3: stack.push_back({b, p}); pc = a; break;
                case 4: pc = a; break;
                case 5: return p;
                case 6: {
                    bool matched = regex_run(r, s, pc + 2, p) >= 0;
                    if (matched != (a == 1)) pc++; else ok = false;
                    break;
                }
                case 7: if (p == 0) pc++; else ok = false; break;
                case 8: if (p == n || (p == n - 1 && s[p] == '\n')) pc++; else ok = false; break;
                case 9: case 10: {
                    bool before = p > 0 && word_byte(s[p - 1]), after = p < n && word_byte(s[p]);
                    if ((before != after) == (op == 9)) pc++; else ok = false;
                    break;
                }
                case 11: stack.push_back({-1 - a, slots[a]}); slots[a] = p; pc++; break;
                case 12: if (slots[a] == p) ok = false; else pc++; break;
                default: fail("bad regular expression program");
            }
            if (!ok) break;
        }
    }
    return -1;
}

inline long regex_match(const Regex& r, const std::string& s, long start) { return regex_run(r, s, 0, start); }

// ==== parser runtime ===========================================================================

/** A failed parse: where it failed and what would have been accepted there. */
struct ParseException : std::runtime_error {
    /** Character offset for text input, bit offset for binary input. */
    long offset;
    /** 1-based line and column (0 for binary input). */
    long line, column;
    std::vector<std::string> expected;
    ParseException(const std::string& m, long o, long l, long c, std::vector<std::string> e)
        : std::runtime_error(m), offset(o), line(l), column(c), expected(std::move(e)) {}
};

struct Mismatch {};

struct Label {
    std::string name;
    bool set = false;
    V value;
    explicit Label(std::string n) : name(std::move(n)) {}
    V get() const { if (!set) fail("label '" + name + "' was not matched"); return value; }
};

struct Terminal { Regex re; bool has_literal; std::string literal; std::string display; };
struct Bits { std::vector<bool> bits; std::string display; };

struct PScope;
struct Rule;
struct Alt {
    int precedence;
    int operand;   // -2147483648: none
    std::function<V(PScope&)> parse;
    std::function<V(PScope&, const V&)> extend;
};
struct Rule {
    std::string name;
    bool abstract = false;
    int id = static_cast<int>(next_id());
    std::function<V(PScope&)> body;
    std::vector<Alt> alternatives, operators;
    void on(std::function<V(PScope&)> p) { alternatives.push_back({2147483647, INT32_MIN, std::move(p), nullptr}); }
    void prefix(int prec, std::function<V(PScope&)> p) { alternatives.push_back({prec, prec, std::move(p), nullptr}); }
    void infix_left(int prec, std::function<V(PScope&, const V&)> e) { operators.push_back({prec, prec + 1, nullptr, std::move(e)}); }
    void infix_right(int prec, std::function<V(PScope&, const V&)> e) { operators.push_back({prec, prec, nullptr, std::move(e)}); }
    void postfix(int prec, std::function<V(PScope&, const V&)> e) { operators.push_back({prec, prec, nullptr, std::move(e)}); }
};

struct Memo { bool ok; V value; long end; long head; };

inline bool word_char(unsigned char c) { return word_byte(c) || c >= 128; }

struct PScope {
    std::string text;
    bool binary;
    const Regex* skip;
    const std::vector<std::string>* keywords;
    long unit, size;
    long pos = 0;
    std::unordered_map<std::string, Memo> memo;
    std::vector<long> stack;
    std::unordered_set<long> left_recursive, involved;
    long furthest = -1;
    std::vector<std::string> expected;
    std::vector<std::pair<Rule*, int>> operators;   // rule null: inside a rule
    long frames = 0;

    PScope(std::string t, bool bin, const Regex* sk, const std::vector<std::string>* kw)
        : text(std::move(t)), binary(bin), skip(sk), keywords(kw), unit(bin ? 8 : 1), size(static_cast<long>(text.size()) * unit) {}

    [[noreturn]] void mismatch(const std::string& what) {
        if (pos > furthest) { furthest = pos; expected.clear(); }
        if (pos == furthest) {
            bool known = false;
            for (auto& e : expected) if (e == what) known = true;
            if (!known) expected.push_back(what);
        }
        throw Mismatch();
    }

    long skip_from(long from) {
        if (skip == nullptr || from % unit != 0) return from;
        long p = from;
        while (true) {
            long e = regex_match(*skip, text, p / unit);
            if (e < 0 || e == p / unit) return p;
            p = e * unit;
        }
    }

    V terminal(const Terminal& t) {
        pos = skip_from(pos);
        if (pos % unit != 0) mismatch(t.display);
        long start = pos / unit;
        long e = regex_match(t.re, text, start);
        if (e < 0) mismatch(t.display);
        std::string value = text.substr(start, e - start);
        if (t.has_literal) {
            if (!t.literal.empty() && word_char(t.literal.back()) && e < static_cast<long>(text.size()) && word_char(text[e])) mismatch(t.display);
        } else {
            for (auto& k : *keywords) if (k == value) mismatch(t.display);
        }
        pos = e * unit;
        return S(value);
    }

    void require_binary() { if (!binary) fail("bit and byte primitives need binary input"); }
    bool bit_at(long p) { return (static_cast<unsigned char>(text[p >> 3]) >> (7 - (p & 7))) & 1; }

    V bits(const Bits& b) {
        require_binary();
        long w = static_cast<long>(b.bits.size());
        if (pos + w > size) mismatch(b.display);
        for (long k = 0; k < w; k++) if (bit_at(pos + k) != b.bits[k]) mismatch(b.display);
        pos += w;
        return S(b.display);
    }
    V uint(const V& width) {
        int w = int_of(width);
        require_binary();
        if (w < 0 || w > 32) fail("width must be between 0 and 32");
        if (pos + w > size) mismatch(std::to_string(w) + " more bits");
        uint64_t v = 0;
        for (int k = 0; k < w; k++) v = (v << 1) | (bit_at(pos + k) ? 1 : 0);
        pos += w;
        return I(static_cast<int32_t>(static_cast<uint32_t>(v)));
    }
    V sint(const V& width) {
        int w = int_of(width);
        if (w < 1 || w > 32) fail("width must be between 1 and 32");
        int64_t v = static_cast<uint32_t>(int_of(uint(width)));
        if (w < 32 && ((v >> (w - 1)) & 1)) v -= (int64_t(1) << w);
        return I(static_cast<int32_t>(v));
    }
    V bit() { return B(int_of(uint(I(1))) == 1); }
    V u1() { return uint(I(8)); }
    V u2() { return uint(I(16)); }
    V u4() { return uint(I(32)); }
    V bytes(const V& count) {
        int c = int_of(count);
        require_binary();
        if (pos + c * 8 > size) mismatch(std::to_string(c) + " more bytes");
        List r;
        for (int k = 0; k < c; k++) r.push_back(uint(I(8)));
        return L(r);
    }

    template <class F> V optional(F body) {
        long start = pos;
        try { return body(); } catch (const Mismatch&) { pos = start; return V(); }
    }
    template <class F> V many(F body) {
        List r;
        while (true) {
            long start = pos;
            try { r.push_back(body()); } catch (const Mismatch&) { pos = start; return L(r); }
            if (pos == start) return L(r);
        }
    }
    template <class F> V some(F body) {
        V first = body();
        List r{first};
        V rest = many(body);
        for (auto& x : items(rest)) r.push_back(x);
        return L(r);
    }
    template <class F> V repeat(const V& count, F body) {
        List r;
        for (int k = 0; k < int_of(count); k++) r.push_back(body());
        return L(r);
    }
    template <class F> V and_(F body) {
        long start = pos;
        try { body(); } catch (...) { pos = start; throw; }
        pos = start;
        return V();
    }
    template <class F> V not_(const std::string& description, F body) {
        long start = pos;
        bool matched = true;
        try { body(); } catch (const Mismatch&) { matched = false; }
        pos = start;
        if (matched) mismatch("not " + description);
        return V();
    }
    V one_of(std::initializer_list<std::function<V()>> alternatives) {
        long start = pos;
        for (auto& a : alternatives) {
            try { return a(); } catch (const Mismatch&) { pos = start; }
        }
        throw Mismatch();
    }
    V seq(std::initializer_list<std::pair<std::function<V()>, Label*>> parts, int value_index) {
        List values;
        for (auto& part : parts) {
            V x = part.first();
            if (part.second != nullptr) { part.second->value = x; part.second->set = true; }
            values.push_back(x);
        }
        if (value_index == -2) return V();
        if (value_index == -1) return L(values);
        return values[value_index];
    }

    long line_of(long offset) {
        long line = 1;
        for (long k = 0; k < std::min(offset, static_cast<long>(text.size())); k++) if (text[k] == '\n') line++;
        return line;
    }
    long column_of(long offset) {
        long k = std::min(offset, static_cast<long>(text.size())) - 1;
        while (k >= 0 && text[k] != '\n') k--;
        return offset - (k + 1) + 1;
    }

    void record(const V& value, long start, long end) {
        if (value.tag != V::NODE || !value.as<Node>().span.is_null()) return;
        long from = skip_from(start) / unit;
        long to = (end + unit - 1) / unit;
        value.as<Node>().span = binary
            ? new_span(V(), I(0), I(0), I(from), I(to - from), S(""))
            : new_span(V(), I(line_of(from)), I(column_of(from)), I(from), I(to - from), S(text.substr(from, std::max(0L, to - from))));
    }

    V operand() {
        if (operators.empty() || operators.back().first == nullptr) fail("an operand outside of an operator");
        return apply(*operators.back().first, operators.back().second);
    }

    template <class F> V in_operator(Rule& r, int operand_precedence, F block) {
        operators.push_back({operand_precedence == INT32_MIN ? nullptr : &r, operand_precedence});
        try {
            V x = block();
            operators.pop_back();
            return x;
        } catch (...) {
            operators.pop_back();
            throw;
        }
    }

    V climb(Rule& r, int min_precedence) {
        long start = pos;
        V left;
        bool found = false;
        for (auto& a : r.alternatives) {
            try {
                left = in_operator(r, a.operand, [&] { return a.parse(*this); });
                record(left, start, pos);
                found = true;
                break;
            } catch (const Mismatch&) {
                pos = start;
            }
        }
        if (!found) {
            if (r.alternatives.empty()) fail("abstract rule '" + r.name + "' has no alternatives");
            throw Mismatch();
        }
        while (true) {
            bool extended_any = false;
            for (auto& op : r.operators) {
                if (op.precedence < min_precedence) continue;
                long before = pos;
                try {
                    V current = left;
                    V extended = in_operator(r, op.operand, [&] { return op.extend(*this, current); });
                    if (pos == before) continue;
                    left = extended;
                    record(extended, start, pos);
                    extended_any = true;
                    break;
                } catch (const Mismatch&) {
                    pos = before;
                }
            }
            if (!extended_any) return left;
        }
    }

    V run(Rule& r, int precedence) { return r.abstract ? climb(r, precedence) : r.body(*this); }

    bool attempt(Rule& r, int precedence, long start, Memo& into) {
        pos = start;
        try {
            into.value = run(r, precedence);
            into.end = pos;
            into.ok = true;
        } catch (const Mismatch&) {
            into.ok = false;
        }
        return into.ok;
    }

    V apply(Rule& r, int precedence = INT32_MIN) {
        long start = pos;
        std::string key = std::to_string(r.id) + ":" + std::to_string(start) + ":" + std::to_string(precedence);
        auto found = memo.find(key);
        if (found != memo.end()) {
            Memo& m = found->second;
            if (m.head >= 0) {
                left_recursive.insert(m.head);
                for (auto it = stack.rbegin(); it != stack.rend() && *it != m.head; ++it) involved.insert(*it);
            }
            if (!m.ok) throw Mismatch();
            pos = m.end;
            return m.value;
        }
        long frame = ++frames;
        memo[key] = Memo{false, V(), start, frame};
        stack.push_back(frame);
        operators.push_back({nullptr, INT32_MIN});
        auto cleanup = [&] {
            operators.pop_back();
            stack.pop_back();
            memo[key].head = -1;
            if (involved.count(frame)) memo.erase(key);
            left_recursive.erase(frame);
            involved.erase(frame);
        };
        Memo result;
        try {
            Memo m = memo[key];
            attempt(r, precedence, start, m);
            memo[key] = m;
            if (left_recursive.count(frame)) {
                while (m.ok) {
                    V value = m.value;
                    long end = m.end;
                    memo[key] = m;
                    if (!attempt(r, precedence, start, m) || m.end <= end) {
                        m.ok = true; m.value = value; m.end = end;
                        memo[key] = m;
                        break;
                    }
                    memo[key] = m;
                }
            }
            result = m;
            cleanup();
        } catch (...) {
            cleanup();
            throw;
        }
        if (!result.ok) { pos = start; throw Mismatch(); }
        pos = result.end;
        record(result.value, start, result.end);
        return result.value;
    }

    ParseException error() {
        long at = std::max(furthest, 0L);
        std::string described;
        for (size_t k = 0; k < expected.size(); k++) { if (k) described += " or "; described += expected[k]; }
        if (!binary) {
            std::string found = at < static_cast<long>(text.size()) ? std::string("'") + text[at] + "'" : "end of input";
            long line = line_of(at), column = column_of(at);
            return ParseException(std::to_string(line) + ":" + std::to_string(column) + ": expected " + described + ", found " + found, at, line, column, expected);
        }
        long b = at / 8, bit = at % 8;
        std::string where = bit == 0 ? "byte " + std::to_string(b) : "byte " + std::to_string(b) + ", bit " + std::to_string(bit);
        char hex[8];
        std::snprintf(hex, sizeof hex, "0x%02X", b < static_cast<long>(text.size()) ? static_cast<unsigned char>(text[b]) : 0);
        std::string found = b < static_cast<long>(text.size()) ? hex : "end of input";
        return ParseException(where + ": expected " + described + ", found " + found, at, 0, 0, expected);
    }

    V parse_all(Rule& r) {
        try {
            V result = apply(r);
            pos = skip_from(pos);
            if (pos == size) return result;
            if (pos > furthest) { furthest = pos; expected.clear(); }
            if (pos == furthest) {
                bool known = false;
                for (auto& e : expected) if (e == "end of input") known = true;
                if (!known) expected.push_back("end of input");
            }
        } catch (const Mismatch&) {
        }
        throw error();
    }
};

inline V parse_text(Rule& r, const Regex* skip, const std::vector<std::string>* keywords, const V& input) {
    PScope s(str_of(input), false, skip, keywords);
    return s.parse_all(r);
}
inline V parse_binary(Rule& r, const Regex* skip, const std::vector<std::string>* keywords, const V& input) {
    std::string bytes;
    for (auto& b : items(input)) bytes += static_cast<char>(int_of(b) & 0xFF);
    PScope s(bytes, true, skip, keywords);
    return s.parse_all(r);
}

template <class F> V match_cases(const V& subject, std::initializer_list<std::function<V()>> cases, F alt) {
    for (auto& c : cases) { V r = c(); if (r.tag != V::NOMATCH) return r; }
    return alt();
}

/** Runs f on a thread with a stack of [megabytes], for the deep recursion of attribute evaluation. */
template <class F> void with_stack(size_t megabytes, F f) {
    struct Box { F* f; std::exception_ptr error; };
    Box box{&f, nullptr};
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, megabytes * 1024 * 1024);
    pthread_t thread;
    auto run = [](void* p) -> void* {
        Box* b = static_cast<Box*>(p);
        try { (*b->f)(); } catch (...) { b->error = std::current_exception(); }
        return nullptr;
    };
    if (pthread_create(&thread, &attr, run, &box) != 0) { f(); return; }
    pthread_join(thread, nullptr);
    pthread_attr_destroy(&attr);
    if (box.error) std::rethrow_exception(box.error);
}

} // namespace rt

#endif
// ==== end of Forge runtime =====================================================================
