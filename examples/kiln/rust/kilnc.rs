// The Kiln compiler, generated in Rust: ./kilnc <file.kiln> [-d <dir>]
mod kiln;

use kiln::rt;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        eprintln!("usage: kilnc <file.kiln> [-d <dir>]");
        std::process::exit(1);
    }
    let path = args[0].clone();
    let dir = if args.len() >= 3 && args[1] == "-d" { args[2].clone() } else { ".".to_string() };
    let base = std::path::Path::new(&path).file_stem().unwrap().to_string_lossy().to_string();
    let class_name = format!("{}{}", base[..1].to_uppercase(), &base[1..]);
    let source = std::fs::read_to_string(&path).expect("can not read the file");
    let status = rt::with_stack(512, move || {
        let result = match rt::catch_parse(|| kiln::compile(rt::s(&class_name), rt::s(&source))) {
            Ok(r) => r,
            Err(e) => { eprintln!("{}:{}", path, e.message); return 1; }
        };
        for d in rt::items(&rt::field(&result, 0)) {
            let label = if rt::show(&rt::field(d, 0)) == "ERROR" { "error" } else { "warning" };
            eprintln!("{}:{}:{}: {}: {}", path, rt::show(&rt::field(d, 1)), rt::show(&rt::field(d, 2)), label, rt::show(&rt::field(d, 3)));
        }
        let bytes = rt::field(&result, 1);
        if rt::is_null(&bytes) { return 1; }
        let data: Vec<u8> = rt::items(&bytes).iter().map(|b| rt::int_of(b) as u8).collect();
        std::fs::create_dir_all(&dir).unwrap();
        let out = format!("{}/{}.class", dir, class_name);
        std::fs::write(&out, &data).unwrap();
        println!("wrote {}: {} bytes", out, data.len());
        0
    });
    std::process::exit(status);
}
