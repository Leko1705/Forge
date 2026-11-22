// Runs the conformance program.
mod conformance;
mod conformance_externals;

use conformance::rt;

fn main() {
    rt::with_stack(512, || {
        let report = conformance::report();
        for line in rt::items(&report) {
            println!("{}", rt::str_of(line));
        }
    });
}
