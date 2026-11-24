(* Runs the program of the language features. *)
open Language.Rt

let () = Array.iter (fun line -> print_endline (str_of line)) (items (Language.report ()))
