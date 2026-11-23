(* Runs the conformance program; sets its externals first. *)
open Conformance.Rt

let () =
  Conformance.ext_int_text := (fun v -> Str (string_of_int (int_of v)));
  Conformance.ext_str_toInt := (fun v -> Int (int_of_string (str_of v)));
  Conformance.ext_str_shout := (fun v -> Str (String.uppercase_ascii (str_of v) ^ "!"));
  Array.iter (fun line -> print_endline (str_of line)) (items (Conformance.report ()))
