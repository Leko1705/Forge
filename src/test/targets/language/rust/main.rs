// Runs the program of the language features.
mod language;

use language::rt;

fn main() {
    rt::with_stack(512, || {
        let report = language::report();
        for line in rt::items(&report) {
            println!("{}", rt::str_of(line));
        }
    });
}
