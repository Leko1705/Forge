// The Kiln compiler, generated in JavaScript, run with Node.js: node kilnc.js <file.kiln> [-d <dir>]
// (index.html runs the same generated module in a browser.)
import * as fs from "node:fs";
import * as path from "node:path";
import * as kiln from "./kiln.js";

function main(args) {
    if (args.length === 0) {
        console.error("usage: kilnc.js <file.kiln> [-d <dir>]");
        process.exit(2);
    }
    const file = args[0];
    const d = args.indexOf("-d");
    const outDir = d >= 0 ? args[d + 1] : ".";
    const name = path.basename(file, path.extname(file));
    const className = name.substring(0, 1).toUpperCase() + name.substring(1);
    const source = fs.readFileSync(file, "utf-8");
    let result;
    try {
        result = kiln.compile(className, source);
    } catch (e) {
        if (!(e instanceof kiln.ParseException)) throw e;
        console.error(file + ":" + e.message);
        process.exit(1);
    }
    for (const diagnostic of result.diagnostics) {
        const label = diagnostic.severity === kiln.Severity.ERROR ? "error" : "warning";
        process.stderr.write(`${file}:${diagnostic.line}:${diagnostic.column}: ${label}: ${diagnostic.message}\n`);
    }
    if (result.bytes === null) process.exit(1);
    fs.mkdirSync(outDir, { recursive: true });
    const out = path.join(outDir, className + ".class");
    fs.writeFileSync(out, Uint8Array.from(result.bytes));
    console.log(`wrote ${out}: ${result.bytes.length} bytes`);
}

main(process.argv.slice(2));
