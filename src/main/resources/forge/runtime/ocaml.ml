(* ==== Forge runtime ==========================================================================
   Values, attribute caches, fixpoints of circular attributes, graphs, flows, regular expressions and the
   parser engine. *)
module Rt = struct

exception Forge_error of string
exception Mismatch

(** A failed parse: where it failed and what would have been accepted there. *)
exception Parse_error of string * int * int * int * string list
(* message, offset (characters, or bits for binary input), line, column (0 for binary input), expected *)

let fail msg = raise (Forge_error msg)

(** A value of Forge. Lists, maps and records are immutable; nodes are compared by identity. *)
type v =
  | Null
  | Int of int
  | Float of float
  | Bool of bool
  | Str of string
  | List of v array
  | Set of v array      (* without duplicates, in the order the elements were added *)
  | Map of (v * v) array
  | Rec of rcd
  | Node of node
  | Graph of graph
  | Edge of edge
  | Port of port
  | PortRef of v * port
  | FlowGraph of int
  | NoMatch

(** Records and enum items: [rid] is the type, [item] whether it is an enum item. *)
and rcd = { rid : int; rname : string; fnames : string array; fields : v array; item : bool }

(** Nodes, and the exit and synthetic vertices of flows ([kind] < 0). *)
and node = {
  kind : int;
  nid : int;
  nfields : v array;
  mutable parent : node option;
  mutable span : v;
  cache : (int, v) Hashtbl.t;
}

and edge = { efrom : v; eto : v; elabel : v }
and port = { pname : string; inherited : bool; pid : int }

and graph = {
  groot : v;
  gentry : v;
  gvertices : v array;
  gedges : v array;
  gexits : (port * v) list;
  gindex : (int, int) Hashtbl.t;
  gout : v list array;
  gin : v list array;
}

let exit_kind = -1
let synthetic_kind = -2
let span_rid = -1

(* set by the generated code *)
let kind_names : string array ref = ref [||]

let counter = ref 0
let next_id () = incr counter; !counter

(* ---- values ------------------------------------------------------------------------------ *)

let wrap x = ((x + 0x80000000) land 0xFFFFFFFF) - 0x80000000
let f32 x = Int32.float_of_bits (Int32.bits_of_float x)

let truth = function Bool b -> b | v -> fail "a bool was expected"
let int_of = function Int i -> i | _ -> fail "an int was expected"
let float_of = function Float f -> f | _ -> fail "a float was expected"
let str_of = function Str s -> s | _ -> fail "a str was expected"
let items = function List a | Set a -> a | _ -> fail "a list was expected"
let entries = function Map a -> a | _ -> fail "a map was expected"
let node_of = function Node n -> n | _ -> fail "a node was expected"

let add a b = Int (wrap (int_of a + int_of b))
let sub a b = Int (wrap (int_of a - int_of b))
let mul a b = Int (wrap (int_of a * int_of b))
let div a b = let d = int_of b in if d = 0 then fail "/ by zero" else Int (wrap (int_of a / d))
let rem a b = let d = int_of b in if d = 0 then fail "/ by zero" else Int (wrap (int_of a mod d))
let neg a = Int (wrap (- (int_of a)))
let land_ a b = Int (int_of a land int_of b)
let lor_ a b = Int (int_of a lor int_of b)
let lxor_ a b = Int (int_of a lxor int_of b)
let lnot_ a = Int (wrap (lnot (int_of a)))
let fadd a b = Float (f32 (float_of a +. float_of b))
let fsub a b = Float (f32 (float_of a -. float_of b))
let fmul a b = Float (f32 (float_of a *. float_of b))
let fdiv a b = Float (f32 (float_of a /. float_of b))
let fneg a = Float (-. (float_of a))
let i2f a = Float (f32 (float_of_int (int_of a)))
let f2i a =
  let f = float_of a in
  if Float.is_nan f then Int 0
  else if f >= 2147483647.0 then Int 2147483647
  else if f <= -2147483648.0 then Int (-2147483648)
  else Int (truncate f)
let concat a b = Str (str_of a ^ str_of b)

let rec eq a b =
  match a, b with
  | Null, Null -> true
  | Int x, Int y -> x = y
  | Float x, Float y -> x = y || (Float.is_nan x && Float.is_nan y)
  | Bool x, Bool y -> x = y
  | Str x, Str y -> String.equal x y
  | List x, List y -> Array.length x = Array.length y && (let ok = ref true in Array.iteri (fun i v -> if !ok && not (eq v y.(i)) then ok := false) x; !ok)
  | Set x, Set y -> Array.length x = Array.length y && Array.for_all (fun a -> Array.exists (fun b -> eq a b) y) x
  | Map x, Map y ->
    Array.length x = Array.length y
    && Array.for_all (fun (k, v) -> match Array.find_opt (fun (k2, _) -> eq k k2) y with Some (_, v2) -> eq v v2 | None -> false) x
  | Rec x, Rec y -> x.rid = y.rid && x.rname = y.rname && Array.length x.fields = Array.length y.fields
                    && (let ok = ref true in Array.iteri (fun i v -> if !ok && not (eq v y.fields.(i)) then ok := false) x.fields; !ok)
  | Node x, Node y -> x == y
  | Graph x, Graph y -> x == y
  | Edge x, Edge y -> x == y
  | Port x, Port y -> x == y
  | PortRef (n, p), PortRef (m, q) -> eq n m && p == q
  | FlowGraph x, FlowGraph y -> x = y
  | _ -> false

let same a b = match a, b with Node x, Node y -> x == y | _ -> false

let show_float f =
  if Float.is_nan f then "NaN"
  else if f = Float.infinity then "Infinity"
  else if f = Float.neg_infinity then "-Infinity"
  else if f = 0.0 then (if 1.0 /. f < 0.0 then "-0.0" else "0.0")
  else begin
    let s = ref "" in
    (try for digits = 1 to 9 do
        s := Printf.sprintf "%.*e" (digits - 1) f;
        if f32 (float_of_string !s) = f then raise Exit
      done with Exit -> ());
    let s = !s in
    let e = String.index s 'e' in
    let mantissa = String.sub s 0 e and exponent = int_of_string (String.sub s (e + 1) (String.length s - e - 1)) in
    let negative = mantissa.[0] = '-' in
    let digits = String.concat "" (String.split_on_char '.' (if negative then String.sub mantissa 1 (String.length mantissa - 1) else mantissa)) in
    let n = String.length digits in
    let text =
      if exponent >= -3 && exponent < 7 then begin
        if exponent >= 0 then begin
          let whole = if n > exponent + 1 then String.sub digits 0 (exponent + 1) else digits ^ String.make (exponent + 1 - n) '0' in
          let fraction = if n > exponent + 1 then String.sub digits (exponent + 1) (n - exponent - 1) else "0" in
          whole ^ "." ^ fraction
        end else "0." ^ String.make (- exponent - 1) '0' ^ digits
      end else
        String.make 1 digits.[0] ^ "." ^ (if n > 1 then String.sub digits 1 (n - 1) else "0") ^ "E" ^ string_of_int exponent
    in
    (if negative then "-" else "") ^ text
  end

let rec show v =
  match v with
  | Null -> "null"
  | Int i -> string_of_int i
  | Float f -> show_float f
  | Bool b -> if b then "true" else "false"
  | Str s -> s
  | List a | Set a -> "[" ^ String.concat ", " (Array.to_list (Array.map show a)) ^ "]"
  | Map a -> "{" ^ String.concat ", " (Array.to_list (Array.map (fun (k, x) -> show k ^ "=" ^ show x) a)) ^ "}"
  | Rec r when r.rid = span_rid ->
    (match r.fields.(0) with Str s -> s ^ ":" | _ -> "") ^ show r.fields.(1) ^ ":" ^ show r.fields.(2)
  | Rec r when r.item && Array.length r.fields = 0 -> r.rname
  | Rec r -> r.rname ^ "(" ^ String.concat ", " (Array.to_list (Array.mapi (fun i x -> r.fnames.(i) ^ "=" ^ show x) r.fields)) ^ ")"
  | Node n when n.kind = exit_kind -> "exit(" ^ show n.nfields.(0) ^ ")"
  | Node n when n.kind = synthetic_kind -> show n.nfields.(0)
  | Node n -> (!kind_names).(n.kind) ^ "(" ^ String.concat ", " (Array.to_list (Array.map show n.nfields)) ^ ")"
  | Graph g -> "Graph(" ^ string_of_int (Array.length g.gvertices) ^ " vertices, " ^ string_of_int (Array.length g.gedges) ^ " edges)"
  | Edge e -> (match e.elabel with Null -> show e.efrom ^ " -> " ^ show e.eto | l -> show e.efrom ^ " -[" ^ show l ^ "]-> " ^ show e.eto)
  | Port p -> p.pname
  | PortRef (n, p) -> p.pname ^ "(" ^ show n ^ ")"
  | FlowGraph _ -> "FlowGraph"
  | NoMatch -> "<no match>"

let nn v = match v with Null -> fail "null value where a value is required (!!)" | _ -> v
let elvis v alt = match v with Null -> alt () | _ -> v
let is_null v = match v with Null -> true | _ -> false

(** A position for [n] elements: below 0 counts from the end. *)
let position i n =
  let k = if i < 0 then i + n else i in
  if k < 0 || k >= n then fail ("index " ^ string_of_int i ^ " out of bounds for length " ^ string_of_int n) else k
(** A bound of a slice: below 0 counts from the end, clamped to 0..n; null is the default. *)
let bound b n default =
  let k = match b with Null -> default | Int i -> if i < 0 then i + n else i | _ -> fail "an int was expected" in
  max 0 (min n k)
let get l i = let a = items l in a.(position (int_of i) (Array.length a))
let size v = match v with
  | List a | Set a -> Int (Array.length a) | Map a -> Int (Array.length a) | Str s -> Int (String.length s)
  | _ -> fail "size of a value that is no list, set, map or str"
let slice v lo hi =
  match v with
  | Str s -> let n = String.length s in let a = bound lo n 0 and b = bound hi n n in Str (if a >= b then "" else String.sub s a (b - a))
  | _ -> let l = items v in let n = Array.length l in let a = bound lo n 0 and b = bound hi n n in List (if a >= b then [||] else Array.sub l a (b - a))
let char_at s i = let t = str_of s in Str (String.make 1 t.[position (int_of i) (String.length t)])
let range a b = let a = int_of a and b = int_of b in List (if b <= a then [||] else Array.init (b - a) (fun i -> Int (a + i)))
let shl a b = Int (wrap (int_of a lsl (int_of b land 31)))
let shr a b = Int (int_of a asr (int_of b land 31))
let ushr a b = Int (wrap ((int_of a land 0xFFFFFFFF) lsr (int_of b land 31)))
let list_concat a b = List (Array.append (items a) (items b))
let list_append a x = List (Array.append (items a) [| x |])
let tail l = let a = items l in List (Array.sub a 1 (Array.length a - 1))
let contains l x = Array.exists (fun y -> eq x y) (items l)
let fold l init f = Array.fold_left f init (items l)
let map_list l f = List (Array.map f (items l))

let map_put_raw (m : (v * v) array) k x =
  let rec find i = if i >= Array.length m then -1 else if eq k (fst m.(i)) then i else find (i + 1) in
  match find 0 with
  | -1 -> Array.append m [| (k, x) |]
  | i -> let c = Array.copy m in c.(i) <- (k, x); c
let map_of pairs = Map (List.fold_left (fun m (k, x) -> map_put_raw m k x) [||] pairs)
let map_put m k x = Map (map_put_raw (entries m) k x)
let map_merge a b = Map (Array.fold_left (fun m (k, x) -> map_put_raw m k x) (entries a) (entries b))
let map_has m k = Array.exists (fun (k2, _) -> eq k k2) (entries m)
let map_at m k =
  match Array.find_opt (fun (k2, _) -> eq k k2) (entries m) with
  | Some (_, x) -> x
  | None -> fail ("key " ^ show k ^ " is missing in the map")

(* ---- sets *)
let dedupe a = Array.of_list (List.rev (Array.fold_left (fun acc x -> if List.exists (fun y -> eq x y) acc then acc else x :: acc) [] a))
let set_of xs = Set (dedupe (Array.of_list xs))
let set_contains s x = Array.exists (fun y -> eq x y) (items s)
let set_union a b = Set (dedupe (Array.append (items a) (items b)))
let set_remove s x = Set (Array.of_list (List.filter (fun y -> not (eq x y)) (Array.to_list (items s))))
let to_set l = Set (dedupe (items l))
let to_list s = List (Array.copy (items s))

(* ---- builtins of str, List and Map *)
let find_from s t from =
  let n = String.length s and m = String.length t in
  let rec go i = if i + m > n then -1 else if String.sub s i m = t then i else go (i + 1) in
  if from > n then -1 else go from
let str_index_of s t = Int (find_from (str_of s) (str_of t) 0)
let str_contains s t = find_from (str_of s) (str_of t) 0 >= 0
let starts_with s t = let s = str_of s and t = str_of t in String.length t <= String.length s && String.sub s 0 (String.length t) = t
let ends_with s t = let s = str_of s and t = str_of t in
  String.length t <= String.length s && String.sub s (String.length s - String.length t) (String.length t) = t
let split s sep =
  let s = str_of s and p = str_of sep in
  if p = "" then fail "the separator of split is empty";
  let rec go from acc =
    match find_from s p from with
    | -1 -> List.rev (String.sub s from (String.length s - from) :: acc)
    | i -> go (i + String.length p) (String.sub s from (i - from) :: acc)
  in
  List (Array.of_list (List.map (fun x -> Str x) (go 0 [])))
let replace s a b =
  if str_of a = "" then fail "the text to replace is empty";
  let parts = split s a in
  Str (String.concat (str_of b) (List.map str_of (Array.to_list (items parts))))
let is_space c = c = ' ' || c = '\t' || c = '\n' || c = '\r' || c = '\011' || c = '\012'
let trim s =
  let s = str_of s in
  let n = String.length s in
  let i = ref 0 and j = ref (n - 1) in
  while !i < n && is_space s.[!i] do incr i done;
  while !j >= !i && is_space s.[!j] do decr j done;
  Str (String.sub s !i (!j - !i + 1))
let upper s = Str (String.uppercase_ascii (str_of s))
let lower s = Str (String.lowercase_ascii (str_of s))
let code s i = let t = str_of s in Int (Char.code t.[position (int_of i) (String.length t)])
let utf8 s = let t = str_of s in List (Array.init (String.length t) (fun i -> Int (Char.code t.[i])))
let char c =
  let c = int_of c in
  let b = Buffer.create 4 in
  Buffer.add_utf_8_uchar b (Uchar.of_int c);
  Str (Buffer.contents b)
let unescape s =
  let s = str_of s in
  let b = Buffer.create (String.length s) in
  let i = ref 0 in
  while !i < String.length s do
    (if s.[!i] = '\\' && !i + 1 < String.length s then begin
       incr i;
       Buffer.add_char b (match s.[!i] with 'n' -> '\n' | 't' -> '\t' | 'r' -> '\r' | '0' -> '\000' | c -> c)
     end else Buffer.add_char b s.[!i]);
    incr i
  done;
  Str (Buffer.contents b)
let all_digits s from = from < String.length s && (let ok = ref true in String.iteri (fun i c -> if i >= from && not (c >= '0' && c <= '9') then ok := false) s; !ok)
let to_int s =
  let t = str_of s in
  let from = if String.length t > 0 && (t.[0] = '+' || t.[0] = '-') then 1 else 0 in
  if not (all_digits t from) then fail ("not an int: \"" ^ t ^ "\"");
  match int_of_string_opt (if t.[0] = '+' then String.sub t 1 (String.length t - 1) else t) with
  | Some v when v >= -2147483648 && v <= 2147483647 -> Int v
  | _ -> fail ("not an int: \"" ^ t ^ "\"")
let to_float s =
  let t = str_of s in
  let n = String.length t in
  let i = ref (if n > 0 && (t.[0] = '+' || t.[0] = '-') then 1 else 0) in
  let digits () = let start = !i in while !i < n && t.[!i] >= '0' && t.[!i] <= '9' do incr i done; !i - start in
  let whole = digits () in
  let fraction = if !i < n && t.[!i] = '.' then (incr i; digits ()) else 0 in
  let ok = ref (whole + fraction > 0) in
  if !i < n && (t.[!i] = 'e' || t.[!i] = 'E') then begin
    incr i;
    if !i < n && (t.[!i] = '+' || t.[!i] = '-') then incr i;
    if digits () = 0 then ok := false
  end;
  if not !ok || !i <> n then fail ("not a float: \"" ^ t ^ "\"");
  Float (f32 (float_of_string t))
let join l sep = Str (String.concat (str_of sep) (List.map str_of (Array.to_list (items l))))
let list_index_of l x =
  let a = items l in
  let rec go i = if i >= Array.length a then -1 else if eq a.(i) x then i else go (i + 1) in
  Int (go 0)
let reversed l = let a = items l in let n = Array.length a in List (Array.init n (fun i -> a.(n - 1 - i)))
let map_keys m = List (Array.map fst (entries m))
let map_values m = List (Array.map snd (entries m))
let map_remove m k = Map (Array.of_list (List.filter (fun (k2, _) -> not (eq k k2)) (Array.to_list (entries m))))

let match_cases subject cases alt =
  let rec go = function
    | [] -> (match alt with Some f -> f () | None -> fail ("no match for " ^ show subject))
    | c :: rest -> (match c () with NoMatch -> go rest | r -> r)
  in
  go cases

let new_rec rid rname fnames item fields = Rec { rid; rname; fnames; fields; item }
let field v i = match v with Rec r -> r.fields.(i) | Node n -> n.nfields.(i) | _ -> fail "a record was expected"
let rec_is v rid = match v with Rec r -> r.rid = rid | _ -> false

(* ---- nodes ------------------------------------------------------------------------------- *)

let kind v = match v with Node n -> n.kind | _ -> fail ("not a vertex: " ^ show v)
let kind_in v kinds = match v with Node n -> Array.mem n.kind kinds | _ -> false
let is_vertex v = match v with Node _ -> true | _ -> false

let children n =
  let acc = ref [] in
  Array.iter (fun f ->
    match f with
    | Node c -> acc := c :: !acc
    | List a -> Array.iter (function Node c -> acc := c :: !acc | _ -> ()) a
    | _ -> ()) n.nfields;
  List.rev !acc

let new_node kind fields =
  let n = { kind; nid = next_id (); nfields = fields; parent = None; span = Null; cache = Hashtbl.create 8 } in
  List.iter (fun c -> c.parent <- Some n) (children n);
  Node n

let parent v = match (node_of v).parent with Some p -> Some (Node p) | None -> None
let rec root n = match n.parent with Some p -> root p | None -> n

(** The nodes of the tree below a node (including it), parents before children. *)
let preorder n =
  let result = ref [] in
  let rec walk n = result := Node n :: !result; List.iter walk (children n) in
  walk n;
  List.rev !result

let index_of_child list child =
  let a = items list in
  let rec find i = if i >= Array.length a then -1 else if same a.(i) child then i else find (i + 1) in
  find 0

(** An inh equation for a list field: [value i] if the child is element [k] of the list and [key i] = k. *)
let inh_list list child key value otherwise =
  let k = index_of_child list child in
  if k < 0 then otherwise ()
  else begin
    let n = Array.length (items list) in
    let rec scan i = if i >= n then otherwise () else if int_of (key (Int i)) = k then value (Int i) else scan (i + 1) in
    scan 0
  end

(* ---- caches and fixpoints ---------------------------------------------------------------- *)

type cache = { cid : int; cname : string }
type cycle = { mutable iteration : int; mutable changed : bool; mutable attrs : circular list }
and circular = { ccid : int; mutable approx : (int, v) Hashtbl.t; mutable visited : (int, node * int) Hashtbl.t }

let current_cycle : cycle option ref = ref None
let new_cache name = { cid = next_id (); cname = name }
let new_circular () = { ccid = next_id (); approx = Hashtbl.create 16; visited = Hashtbl.create 16 }

(** The value of an attribute, computed once per node. Nothing is cached while a fixpoint is computed. *)
let cached c v compute =
  let n = node_of v in
  match Hashtbl.find_opt n.cache c.cid with
  | Some x -> x
  | None ->
    let busy = - c.cid in
    if Hashtbl.mem n.cache busy then
      fail ("attribute '" ^ c.cname ^ "' of " ^ show v ^ " depends on itself; declare it as circular");
    Hashtbl.replace n.cache busy Null;
    (match compute () with
     | x -> Hashtbl.remove n.cache busy; (if !current_cycle = None then Hashtbl.replace n.cache c.cid x); x
     | exception e -> Hashtbl.remove n.cache busy; raise e)

let approx_of c n initial = match Hashtbl.find_opt c.approx n.nid with Some x -> x | None -> initial

let step c n initial body cy =
  Hashtbl.replace c.visited n.nid (n, cy.iteration);
  let old = approx_of c n initial in
  let value = body () in
  if not (eq value old) || not (Hashtbl.mem c.approx n.nid) then begin
    if not (eq value old) then cy.changed <- true;
    Hashtbl.replace c.approx n.nid value
  end;
  value

(** A circular attribute: a fixpoint, starting at [initial]. *)
let circular c v initial body =
  let n = node_of v in
  match Hashtbl.find_opt n.cache c.ccid with
  | Some x -> x
  | None ->
    match !current_cycle with
    | Some cy ->
      if not (List.memq c cy.attrs) then cy.attrs <- c :: cy.attrs;
      (match Hashtbl.find_opt c.visited n.nid with
       | Some (_, it) when it = cy.iteration -> approx_of c n initial
       | _ -> step c n initial body cy)
    | None ->
      let cy = { iteration = 0; changed = false; attrs = [ c ] } in
      current_cycle := Some cy;
      let finish () =
        current_cycle := None;
        List.iter (fun a -> a.approx <- Hashtbl.create 16; a.visited <- Hashtbl.create 16) cy.attrs
      in
      (try
         let continue = ref true in
         while !continue do
           cy.changed <- false;
           cy.iteration <- cy.iteration + 1;
           ignore (step c n initial body cy);
           continue := cy.changed
         done;
         List.iter (fun a ->
           Hashtbl.iter (fun id (m, it) -> if it = cy.iteration then Hashtbl.replace m.cache a.ccid (Hashtbl.find a.approx id)) a.visited)
           cy.attrs;
         let result = Hashtbl.find n.cache c.ccid in
         finish ();
         result
       with e -> finish (); raise e)

(* ---- graphs ------------------------------------------------------------------------------ *)

let vid v = (node_of v).nid

let make_graph root entry vertices edges exits =
  let index = Hashtbl.create 64 in
  let distinct = ref [] and count = ref 0 in
  let add v = let i = vid v in if not (Hashtbl.mem index i) then (Hashtbl.replace index i !count; incr count; distinct := v :: !distinct) in
  List.iter add vertices;
  List.iter (function Edge e -> add e.efrom; add e.eto | _ -> ()) edges;
  let vs = Array.of_list (List.rev !distinct) in
  let gout = Array.make (Array.length vs) [] and gin = Array.make (Array.length vs) [] in
  List.iter (function
    | Edge e as ev ->
      let f = Hashtbl.find index (vid e.efrom) and t = Hashtbl.find index (vid e.eto) in
      gout.(f) <- ev :: gout.(f);
      gin.(t) <- ev :: gin.(t)
    | _ -> ()) edges;
  Graph { groot = root; gentry = entry; gvertices = vs; gedges = Array.of_list edges; gexits = exits; gindex = index;
          gout = Array.map List.rev gout; gin = Array.map List.rev gin }

let graph_of = function Graph g -> g | _ -> fail "a graph was expected"

(** A graph from edges, e.g. a call graph. The vertices are entry and the endpoints of edges. *)
let graph_from entry edges =
  let es = Array.to_list (items edges) in
  make_graph Null entry (entry :: List.concat_map (function Edge e -> [ e.efrom; e.eto ] | _ -> []) es) es []

let new_edge f t l = Edge { efrom = f; eto = t; elabel = l }
let edge_field v i = match v with Edge e -> (match i with 0 -> e.efrom | 1 -> e.eto | _ -> e.elabel) | _ -> fail "an edge was expected"
let graph_field v i =
  let g = graph_of v in
  match i with 0 -> g.gentry | 1 -> List g.gvertices | 2 -> List g.gedges | _ -> g.groot

let index_in g v = match v with Node n -> (match Hashtbl.find_opt g.gindex n.nid with Some i -> i | None -> -1) | _ -> -1
let g_contains g v = Bool (index_in (graph_of g) v >= 0)
let g_index g v = Int (index_in (graph_of g) v)
let g_out g v = let g = graph_of g in let i = index_in g v in List (if i < 0 then [||] else Array.of_list g.gout.(i))
let g_in g v = let g = graph_of g in let i = index_in g v in List (if i < 0 then [||] else Array.of_list g.gin.(i))
let g_succ g v = List (Array.map (fun e -> edge_field e 1) (items (g_out g v)))
let g_pred g v = List (Array.map (fun e -> edge_field e 0) (items (g_in g v)))
let g_exit g p = let name = str_of p in
  match List.find_opt (fun (q, _) -> q.pname = name) (graph_of g).gexits with Some (_, v) -> v | None -> Null

let g_reachable gv =
  let g = graph_of gv in
  let seen = Array.make (Array.length g.gvertices) false in
  let result = ref [] in
  let work = ref [ g.gentry ] in
  while !work <> [] do
    let v = List.hd !work in
    work := List.tl !work;
    let i = index_in g v in
    if i >= 0 && not seen.(i) then begin
      seen.(i) <- true;
      result := v :: !result;
      work := Array.to_list (items (g_succ gv v)) @ !work
    end
  done;
  List (Array.of_list (List.rev !result))

let g_postorder gv =
  let g = graph_of gv in
  let seen = Array.make (Array.length g.gvertices) false in
  let result = ref [] in
  let rec visit v =
    let i = index_in g v in
    if i >= 0 && not seen.(i) then begin
      seen.(i) <- true;
      Array.iter visit (items (g_succ gv v));
      result := v :: !result
    end
  in
  visit g.gentry;
  List (Array.of_list (List.rev !result))

let g_rpo gv = List (Array.of_list (List.rev (Array.to_list (items (g_postorder gv)))))

let g_dot gv =
  let g = graph_of gv in
  let esc s = String.concat "\\\\" (List.map (fun p -> String.concat "\\\"" (String.split_on_char '"' p)) (String.split_on_char '\\' s)) in
  let b = Buffer.create 256 in
  Buffer.add_string b "digraph G {\n";
  Array.iteri (fun i v -> Buffer.add_string b (Printf.sprintf "  n%d [label=\"%s\"];\n" i (esc (show v)))) g.gvertices;
  Array.iter (fun e ->
    Buffer.add_string b (Printf.sprintf "  n%d -> n%d" (index_in g (edge_field e 0)) (index_in g (edge_field e 1)));
    (match edge_field e 2 with Null -> () | l -> Buffer.add_string b (Printf.sprintf " [label=\"%s\"]" (esc (show l))));
    Buffer.add_string b ";\n") g.gedges;
  Buffer.add_string b "}";
  Str (Buffer.contents b)

let new_port name inherited = { pname = name; inherited; pid = next_id () }
let port_next = new_port "next" false
let port_of = function Port p -> p | _ -> fail "a port was expected"

let exit_vertex p = new_node exit_kind [| Port p |]
let synthetic_vertex name owner = new_node synthetic_kind [| name; owner |]

(* flows: set by the generated code *)
type scope = {
  fnode : v;
  build : build;
  mutable entry_spec : target option;
  own : (int, unit) Hashtbl.t;
}
and target = TVertex of v | TChild of v | TPort of v * port
and build = {
  broot : v;
  rule : scope -> v -> bool;
  scopes : (int, scope) Hashtbl.t;
  flow_parent : (int, v) Hashtbl.t;
  bindings : (int, (port * target) list ref) Hashtbl.t;
  queue : v Queue.t;
  mutable bvertices : v list;
  vertex_set : (int, unit) Hashtbl.t;
  mutable exits : (port * v) list;
  mutable bedges : (v * target * v) list;
  resolved : (int * int * int, v) Hashtbl.t;
  resolving : (int * int * int, unit) Hashtbl.t;
}

let flow_rules : (int -> scope -> v -> bool) ref = ref (fun _ _ _ -> false)
let flow_is_root : (int -> v -> bool) ref = ref (fun _ _ -> false)

let add_vertex b v = let i = vid v in if not (Hashtbl.mem b.vertex_set i) then (Hashtbl.replace b.vertex_set i (); b.bvertices <- v :: b.bvertices)
let reference b child by =
  if not (same child b.broot) && not (Hashtbl.mem b.flow_parent (vid child)) then begin
    Hashtbl.replace b.flow_parent (vid child) by;
    Queue.add child b.queue
  end
let bind b child p t =
  let ports = match Hashtbl.find_opt b.bindings (vid child) with Some l -> l | None -> let l = ref [] in Hashtbl.replace b.bindings (vid child) l; l in
  if List.exists (fun (q, _) -> q == p) !ports then fail ("port " ^ p.pname ^ " of " ^ show child ^ " is connected twice");
  ports := !ports @ [ (p, t) ]
let rec is_bound b node p =
  (match Hashtbl.find_opt b.bindings (vid node) with Some l -> List.exists (fun (q, _) -> q == p) !l | None -> false)
  || (p.inherited && (match Hashtbl.find_opt b.flow_parent (vid node) with Some parent -> is_bound b parent p | None -> false))

let is_own s x = same x s.fnode || (match x with Node n -> Hashtbl.mem s.own n.nid | _ -> false)
let to_target s x =
  match x with
  | Port p -> TPort (s.fnode, p)
  | PortRef _ -> fail ("a port of a child can not be a target: " ^ show x ^ ". Connect it instead: " ^ show x ^ " -> ...")
  | _ when is_own s x -> add_vertex s.build x; TVertex x
  | _ -> reference s.build x s.fnode; TChild x

let f_entry s t =
  if s.entry_spec <> None then fail ("the flow of " ^ show s.fnode ^ " already has an entry");
  s.entry_spec <- Some (to_target s t)

let f_connect s source t label =
  let t = to_target s t in
  match source with
  | Port p -> fail ("a port of the node itself can not be connected; only ports of children: " ^ p.pname ^ "(" ^ show s.fnode ^ ")")
  | PortRef (child, p) ->
    if label <> Null then fail ("only edges from vertices can be labeled: " ^ show source);
    reference s.build child s.fnode;
    bind s.build child p t
  | _ when is_own s source -> add_vertex s.build source; s.build.bedges <- (source, t, label) :: s.build.bedges
  | _ ->
    if label <> Null then fail ("only edges from vertices can be labeled: " ^ show source);
    reference s.build source s.fnode;
    bind s.build source port_next t

let f_vertex s v = Hashtbl.replace s.own (vid v) (); add_vertex s.build v; v
let f_bound s p = Bool (is_bound s.build s.fnode (port_of p))
let f_sequence s l =
  let a = items l in
  let n = Array.length a in
  if n = 0 then f_entry s (Port port_next)
  else begin
    f_entry s a.(0);
    for i = 0 to n - 2 do f_connect s a.(i) a.(i + 1) Null done;
    f_connect s a.(n - 1) (Port port_next) Null
  end

let key = function TVertex v -> (0, vid v, 0) | TChild v -> (1, vid v, 0) | TPort (v, p) -> (2, vid v, p.pid)

let rec resolve b t =
  let k = key t in
  match Hashtbl.find_opt b.resolved k with
  | Some v -> v
  | None ->
    if Hashtbl.mem b.resolving k then
      fail ("flow cycle without a vertex at " ^ (match t with TVertex v -> show v | TChild v -> "entry of " ^ show v | TPort (v, p) -> p.pname ^ "(" ^ show v ^ ")"));
    Hashtbl.replace b.resolving k ();
    let v =
      match t with
      | TVertex v -> v
      | TChild n ->
        (match Hashtbl.find_opt b.scopes (vid n) with
         | None -> fail ("no flow for " ^ show n)
         | Some { entry_spec = None; _ } -> fail ("the flow of " ^ show n ^ " has no entry")
         | Some { entry_spec = Some e; _ } -> resolve b e)
      | TPort (n, p) -> resolve_port b n p
    in
    Hashtbl.remove b.resolving k;
    Hashtbl.replace b.resolved k v;
    v

and resolve_port b n p =
  match (match Hashtbl.find_opt b.bindings (vid n) with Some l -> List.assq_opt p !l | None -> None) with
  | Some t -> resolve b t
  | None ->
    if same n b.broot then begin
      match List.assq_opt p b.exits with
      | Some v -> v
      | None -> let v = exit_vertex p in b.exits <- b.exits @ [ (p, v) ]; add_vertex b v; v
    end else begin
      let parent = Hashtbl.find b.flow_parent (vid n) in
      if p.inherited then resolve_port b parent p
      else fail ("port " ^ p.pname ^ " of " ^ show n ^ " is not connected; connect it in the flow of " ^ show parent)
    end

let flow_build root rule =
  let b = { broot = root; rule; scopes = Hashtbl.create 64; flow_parent = Hashtbl.create 64; bindings = Hashtbl.create 64;
            queue = Queue.create (); bvertices = []; vertex_set = Hashtbl.create 64; exits = []; bedges = [];
            resolved = Hashtbl.create 64; resolving = Hashtbl.create 16 } in
  Queue.add root b.queue;
  while not (Queue.is_empty b.queue) do
    let n = Queue.pop b.queue in
    if not (Hashtbl.mem b.scopes (vid n)) then begin
      let s = { fnode = n; build = b; entry_spec = None; own = Hashtbl.create 4 } in
      Hashtbl.replace b.scopes (vid n) s;
      if not (rule s n) then begin
        let name v = (!kind_names).(kind v) in
        fail ("no flow defined for " ^ name n ^ (match Hashtbl.find_opt b.flow_parent (vid n) with Some p -> ", used in the flow of " ^ name p | None -> ""))
      end
    end
  done;
  let entry = resolve b (TChild root) in
  let edges = ref [] in
  List.iter (fun (f, t, l) -> edges := new_edge f (resolve b t) l :: !edges) (List.rev b.bedges);
  let edges = List.rev !edges in
  make_graph root entry (entry :: List.rev b.bvertices) edges b.exits

let flow_graphs : (int * int, v) Hashtbl.t = Hashtbl.create 16
let flow_owners : (int * int, v) Hashtbl.t = Hashtbl.create 64

let fg_get gid root =
  match Hashtbl.find_opt flow_graphs (gid, vid root) with
  | Some g -> g
  | None ->
    let g = flow_build root (!flow_rules gid) in
    Hashtbl.replace flow_graphs (gid, vid root) g;
    Array.iter (fun v -> Hashtbl.replace flow_owners (gid, vid v) g) (graph_of g).gvertices;
    g

let fg_of gid v =
  match Hashtbl.find_opt flow_owners (gid, vid v) with
  | Some g -> g
  | None ->
    let rec up n = if !flow_is_root gid (Node n) then fg_get gid (Node n) else match n.parent with Some p -> up p | None -> Null in
    up (node_of v)

let fg_succ gid v = match fg_of gid v with Null -> List [||] | g -> g_succ g v
let fg_pred gid v = match fg_of gid v with Null -> List [||] | g -> g_pred g v

(* ---- spans ------------------------------------------------------------------------------- *)

let span_fields = [| "source"; "line"; "column"; "offset"; "length"; "text" |]
let new_span source line column offset length text =
  Rec { rid = span_rid; rname = "Span"; fnames = span_fields; fields = [| source; line; column; offset; length; text |]; item = false }
let span_of v = match v with Node n -> n.span | _ -> Null

(* ---- regular expressions ------------------------------------------------------------------ *)

(** A program of the backtracking matcher (see RegexProgram in Forge): triples op a b. *)
type regex = { code : int array; sets : int array array; slots : int }

let word_byte c = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c = '_'

(** The end of the match of [r] at [start] in [s], or -1. *)
let rec regex_run r s pc0 start =
  let n = String.length s in
  let slots = Array.make (max 1 r.slots) (-1) in
  (* stack entries: (pc, pos) to try, or (-1 - slot, old value) to restore *)
  let stack = ref [ (pc0, start) ] in
  let result = ref (-1) in
  while !result < 0 && !stack <> [] do
    let (pc, p) = List.hd !stack in
    stack := List.tl !stack;
    if pc < 0 then slots.(-1 - pc) <- p
    else begin
      let pc = ref pc and p = ref p and go = ref true in
      while !go do
        let op = r.code.(3 * !pc) and a = r.code.(3 * !pc + 1) and b = r.code.(3 * !pc + 2) in
        match op with
        | 0 -> if !p < n && Char.code s.[!p] = a then (incr p; incr pc) else go := false
        | 1 -> if !p < n && s.[!p] <> '\n' && s.[!p] <> '\r' then (incr p; incr pc) else go := false
        | 2 ->
          if !p < n then begin
            let c = Char.code s.[!p] and set = r.sets.(a) in
            let found = ref false in
            let i = ref 0 in
            while not !found && !i < Array.length set do
              if c >= set.(!i) && c <= set.(!i + 1) then found := true;
              i := !i + 2
            done;
            if !found <> (b = 1) then (incr p; incr pc) else go := false
          end else go := false
        | 3 -> stack := (b, !p) :: !stack; pc := a
        | 4 -> pc := a
        | 5 -> result := !p; go := false
        | 6 ->
          let matched = regex_run r s (!pc + 2) !p >= 0 in
          if matched <> (a = 1) then incr pc else go := false
        | 7 -> if !p = 0 then incr pc else go := false
        | 8 -> if !p = n || (!p = n - 1 && s.[!p] = '\n') then incr pc else go := false
        | 9 | 10 ->
          let before = !p > 0 && word_byte s.[!p - 1] and after = !p < n && word_byte s.[!p] in
          if (before <> after) = (op = 9) then incr pc else go := false
        | 11 -> stack := (-1 - a, slots.(a)) :: !stack; slots.(a) <- !p; incr pc
        | 12 -> if slots.(a) = !p then go := false else incr pc
        | _ -> fail "bad regular expression program"
      done
    end
  done;
  !result

let regex_match r s start = regex_run r s 0 start

(* ==== parser runtime ======================================================================= *)

type label = { lname : string; mutable lset : bool; mutable lvalue : v }
let new_label name = { lname = name; lset = false; lvalue = Null }
let label_get l = if l.lset then l.lvalue else fail ("label '" ^ l.lname ^ "' was not matched")
let label_set l x = l.lset <- true; l.lvalue <- x

type terminal = { re : regex; literal : string option; display : string }
type bits = { bits : bool array; bdisplay : string }

type rule = {
  rule_name : string;
  rule_id : int;
  abstract : bool;
  mutable body : pscope -> v;
  mutable alternatives : alt list;
  mutable rule_ops : alt list;
}
and alt = { precedence : int; operand : int option; parse : pscope -> v; extend : pscope -> v -> v }
and memo = { mutable ok : bool; mutable value : v; mutable mend : int; mutable head : int option }
and pscope = {
  text : string;
  binary : bool;
  skip : regex option;
  keywords : string list;
  source : string option;
  unit : int;
  psize : int;
  mutable pos : int;
  memos : (int * int * int, memo) Hashtbl.t;
  mutable stack : int list;
  left_recursive : (int, unit) Hashtbl.t;
  involved : (int, unit) Hashtbl.t;
  mutable furthest : int;
  mutable expected : string list;
  mutable in_ops : (rule * int) option list;
}

let new_rule name abstract = { rule_name = name; rule_id = next_id (); abstract; body = (fun _ -> fail "rule without a body"); alternatives = []; rule_ops = [] }
let on r parse = r.alternatives <- r.alternatives @ [ { precedence = max_int; operand = None; parse; extend = (fun _ x -> x) } ]
let prefix r p parse = r.alternatives <- r.alternatives @ [ { precedence = p; operand = Some p; parse; extend = (fun _ x -> x) } ]
let infix_left r p extend = r.rule_ops <- r.rule_ops @ [ { precedence = p; operand = Some (p + 1); parse = (fun _ -> Null); extend } ]
let infix_right r p extend = r.rule_ops <- r.rule_ops @ [ { precedence = p; operand = Some p; parse = (fun _ -> Null); extend } ]
let postfix r p extend = r.rule_ops <- r.rule_ops @ [ { precedence = p; operand = Some p; parse = (fun _ -> Null); extend } ]

let pfail s what =
  if s.pos > s.furthest then (s.furthest <- s.pos; s.expected <- []);
  if s.pos = s.furthest && not (List.mem what s.expected) then s.expected <- s.expected @ [ what ];
  raise Mismatch

let word_char c = word_byte c || Char.code c >= 128

let skip_from s from =
  match s.skip with
  | None -> from
  | Some re ->
    if from mod s.unit <> 0 then from
    else begin
      let p = ref from and go = ref true in
      while !go do
        let e = regex_match re s.text (!p / s.unit) in
        if e < 0 || e = !p / s.unit then go := false else p := e * s.unit
      done;
      !p
    end

let skip_ignored s = s.pos <- skip_from s s.pos

let terminal s t =
  skip_ignored s;
  if s.pos mod s.unit <> 0 then pfail s t.display;
  let start = s.pos / s.unit in
  let e = regex_match t.re s.text start in
  if e < 0 then pfail s t.display;
  let value = String.sub s.text start (e - start) in
  (match t.literal with
   | Some lit ->
     let l = String.length lit in
     if l > 0 && word_char lit.[l - 1] && e < String.length s.text && word_char s.text.[e] then pfail s t.display
   | None -> if List.mem value s.keywords then pfail s t.display);
  s.pos <- e * s.unit;
  Str value

let require_binary s = if not s.binary then fail "bit and byte primitives need binary input"
let bit_at s p = (Char.code s.text.[p lsr 3] lsr (7 - (p land 7))) land 1 = 1

let bits s b =
  require_binary s;
  let w = Array.length b.bits in
  if s.pos + w > s.psize then pfail s b.bdisplay;
  Array.iteri (fun i x -> if bit_at s (s.pos + i) <> x then pfail s b.bdisplay) b.bits;
  s.pos <- s.pos + w;
  Str b.bdisplay

let uint s w =
  let w = int_of w in
  require_binary s;
  if w < 0 || w > 32 then fail "width must be between 0 and 32";
  if s.pos + w > s.psize then pfail s (string_of_int w ^ " more bits");
  let v = ref 0 in
  for i = 0 to w - 1 do v := (!v lsl 1) lor (if bit_at s (s.pos + i) then 1 else 0) done;
  s.pos <- s.pos + w;
  Int (wrap !v)

let sint s w =
  let width = int_of w in
  if width < 1 || width > 32 then fail "width must be between 1 and 32";
  let v = int_of (uint s w) land 0xFFFFFFFF in
  Int (wrap (if width < 32 && (v lsr (width - 1)) land 1 = 1 then v - (1 lsl width) else v))

let bit s = Bool (int_of (uint s (Int 1)) = 1)
let u1 s = uint s (Int 8)
let u2 s = uint s (Int 16)
let u4 s = uint s (Int 32)
let bytes s c =
  let c = int_of c in
  require_binary s;
  if s.pos + c * 8 > s.psize then pfail s (string_of_int c ^ " more bytes");
  List (Array.init c (fun _ -> uint s (Int 8)))

let optional s body = let start = s.pos in try body () with Mismatch -> s.pos <- start; Null
let many s body =
  let result = ref [] and go = ref true in
  while !go do
    let start = s.pos in
    (match body () with
     | x -> result := x :: !result; if s.pos = start then go := false
     | exception Mismatch -> s.pos <- start; go := false)
  done;
  List (Array.of_list (List.rev !result))
let some s body = let first = body () in List (Array.append [| first |] (items (many s body)))
let repeat s count body = let n = int_of count in let r = Array.make n Null in for i = 0 to n - 1 do r.(i) <- body () done; List r
let and_ s body = let start = s.pos in (match body () with _ -> s.pos <- start | exception e -> s.pos <- start; raise e); Null
let not_ s description body =
  let start = s.pos in
  let matched = (match body () with _ -> true | exception Mismatch -> false) in
  s.pos <- start;
  if matched then pfail s ("not " ^ description);
  Null
let one_of s alternatives =
  let start = s.pos in
  let rec go = function
    | [] -> raise Mismatch
    | a :: rest -> (try a () with Mismatch -> s.pos <- start; go rest)
  in
  go alternatives
let seq _ items_ value_index =
  let values = List.map (fun (parse, label) -> let x = parse () in (match label with Some l -> label_set l x | None -> ()); x) items_ in
  match value_index with
  | -2 -> Null
  | -1 -> List (Array.of_list values)
  | i -> List.nth values i

let frames = ref 0

let line_of s offset =
  let line = ref 1 in
  for i = 0 to min offset (String.length s.text) - 1 do if s.text.[i] = '\n' then incr line done;
  !line
let column_of s offset =
  let rec back i = if i < 0 then -1 else if s.text.[i] = '\n' then i else back (i - 1) in
  offset - (back (min (offset - 1) (String.length s.text - 1)) + 1) + 1

let record s value start e =
  match value with
  | Node n when n.span = Null ->
    let from = skip_from s start / s.unit in
    let t = (e + s.unit - 1) / s.unit in
    n.span <-
      (if s.binary then new_span (match s.source with Some x -> Str x | None -> Null) (Int 0) (Int 0) (Int from) (Int (t - from)) (Str "")
       else new_span (match s.source with Some x -> Str x | None -> Null) (Int (line_of s from)) (Int (column_of s from)) (Int from) (Int (t - from))
           (Str (String.sub s.text from (max 0 (t - from)))))
  | _ -> ()

let rec apply_prec s r precedence =
  let start = s.pos in
  let k = (r.rule_id, start, precedence) in
  match Hashtbl.find_opt s.memos k with
  | Some m ->
    (match m.head with
     | Some h ->
       Hashtbl.replace s.left_recursive h ();
       let rec mark = function [] -> () | f :: rest -> if f <> h then (Hashtbl.replace s.involved f (); mark rest) in
       mark s.stack
     | None -> ());
    if not m.ok then raise Mismatch;
    s.pos <- m.mend;
    m.value
  | None ->
    incr frames;
    let frame = !frames in
    let m = { ok = false; value = Null; mend = start; head = Some frame } in
    Hashtbl.replace s.memos k m;
    s.stack <- frame :: s.stack;
    s.in_ops <- None :: s.in_ops;
    let cleanup () =
      s.in_ops <- List.tl s.in_ops;
      s.stack <- List.tl s.stack;
      m.head <- None;
      if Hashtbl.mem s.involved frame then Hashtbl.remove s.memos k;
      Hashtbl.remove s.left_recursive frame;
      Hashtbl.remove s.involved frame
    in
    (try
       ignore (attempt s r precedence start m);
       if Hashtbl.mem s.left_recursive frame then begin
         let go = ref true in
         while !go && m.ok do
           let value = m.value and e = m.mend in
           if not (attempt s r precedence start m) || m.mend <= e then begin
             m.ok <- true; m.value <- value; m.mend <- e; go := false
           end
         done
       end;
       cleanup ()
     with ex -> cleanup (); raise ex);
    if not m.ok then (s.pos <- start; raise Mismatch);
    s.pos <- m.mend;
    record s m.value start m.mend;
    m.value

and attempt s r precedence start into =
  s.pos <- start;
  (match run s r precedence with
   | x -> into.value <- x; into.mend <- s.pos; into.ok <- true
   | exception Mismatch -> into.ok <- false);
  into.ok

and run s r precedence = if r.abstract then climb s r precedence else r.body s

and in_operator s r operand block =
  s.in_ops <- (match operand with Some p -> Some (r, p) | None -> None) :: s.in_ops;
  match block () with
  | x -> s.in_ops <- List.tl s.in_ops; x
  | exception e -> s.in_ops <- List.tl s.in_ops; raise e

and climb s r min_precedence =
  let start = s.pos in
  let rec first = function
    | [] -> if r.alternatives = [] then fail ("abstract rule '" ^ r.rule_name ^ "' has no alternatives") else raise Mismatch
    | a :: rest ->
      (match in_operator s r a.operand (fun () -> a.parse s) with
       | x -> record s x start s.pos; x
       | exception Mismatch -> s.pos <- start; first rest)
  in
  let left = ref (first r.alternatives) in
  let rec extend = function
    | [] -> false
    | op :: rest ->
      if op.precedence < min_precedence then extend rest
      else begin
        let before = s.pos in
        let current = !left in
        match in_operator s r op.operand (fun () -> op.extend s current) with
        | x -> if s.pos = before then extend rest else (left := x; record s x start s.pos; true)
        | exception Mismatch -> s.pos <- before; extend rest
      end
  in
  while extend r.rule_ops do () done;
  !left

let apply s r = apply_prec s r min_int

let operand s =
  match s.in_ops with
  | Some (r, p) :: _ -> apply_prec s r p
  | _ -> fail "an operand outside of an operator"

let parse_all s r =
  let error () =
    let at = max s.furthest 0 in
    let described = String.concat " or " s.expected in
    let prefix = match s.source with Some x -> x ^ ":" | None -> "" in
    if not s.binary then begin
      let found = if at < String.length s.text then "'" ^ String.make 1 s.text.[at] ^ "'" else "end of input" in
      let line = line_of s at and column = column_of s at in
      Parse_error (prefix ^ string_of_int line ^ ":" ^ string_of_int column ^ ": expected " ^ described ^ ", found " ^ found, at, line, column, s.expected)
    end else begin
      let b = at / 8 and bit = at mod 8 in
      let where = if bit = 0 then "byte " ^ string_of_int b else "byte " ^ string_of_int b ^ ", bit " ^ string_of_int bit in
      let found = if b < String.length s.text then Printf.sprintf "0x%02X" (Char.code s.text.[b]) else "end of input" in
      Parse_error (prefix ^ where ^ ": expected " ^ described ^ ", found " ^ found, at, 0, 0, s.expected)
    end
  in
  match apply s r with
  | result ->
    skip_ignored s;
    if s.pos = s.psize then result
    else begin
      if s.pos > s.furthest then (s.furthest <- s.pos; s.expected <- []);
      if s.pos = s.furthest && not (List.mem "end of input" s.expected) then s.expected <- s.expected @ [ "end of input" ];
      raise (error ())
    end
  | exception Mismatch -> raise (error ())

let new_scope text binary skip keywords source =
  let unit = if binary then 8 else 1 in
  { text; binary; skip; keywords; source; unit; psize = String.length text * unit; pos = 0; memos = Hashtbl.create 256; stack = [];
    left_recursive = Hashtbl.create 8; involved = Hashtbl.create 8; furthest = -1; expected = []; in_ops = [] }

let parse_text r skip keywords input = parse_all (new_scope (str_of input) false skip keywords None) r
let parse_binary r skip keywords input =
  let bytes = String.init (Array.length (items input)) (fun i -> Char.chr (int_of (items input).(i) land 0xFF)) in
  parse_all (new_scope bytes true skip keywords None) r

end
(* ==== end of Forge runtime =================================================================== *)
