(* The Kiln compiler, generated in OCaml: ./kilnc <file.kiln> [-d <dir>] *)
open Kiln.Rt

let () =
  let args = Array.to_list Sys.argv |> List.tl in
  let path = match args with p :: _ -> p | [] -> prerr_endline "usage: kilnc <file.kiln> [-d <dir>]"; exit 1 in
  let dir = match args with _ :: "-d" :: d :: _ -> d | _ -> "." in
  let base = Filename.remove_extension (Filename.basename path) in
  let class_name = String.capitalize_ascii base in
  let source = In_channel.with_open_bin path In_channel.input_all in
  let result =
    try Kiln.compile (Str class_name) (Str source)
    with Parse_error (message, _, _, _, _) -> prerr_endline (path ^ ":" ^ message); exit 1
  in
  Array.iter (fun d ->
    let label = if show (field d 0) = "ERROR" then "error" else "warning" in
    prerr_endline (Printf.sprintf "%s:%s:%s: %s: %s" path (show (field d 1)) (show (field d 2)) label (show (field d 3))))
    (items (field result 0));
  match field result 1 with
  | Null -> exit 1
  | bytes ->
    let data = String.init (Array.length (items bytes)) (fun i -> Char.chr (int_of (items bytes).(i))) in
    (try Sys.mkdir dir 0o755 with Sys_error _ -> ());
    let out = Filename.concat dir (class_name ^ ".class") in
    Out_channel.with_open_bin out (fun oc -> output_string oc data);
    Printf.printf "wrote %s: %d bytes\n" out (String.length data)
