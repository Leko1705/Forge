"""The Kiln compiler, generated in Python: python3 kilnc.py <file.kiln> [-d <dir>]"""
import os
import sys

import kiln


def main(args):
    if not args:
        sys.exit("usage: kilnc.py <file.kiln> [-d <dir>]")
    path = args[0]
    out_dir = args[args.index("-d") + 1] if "-d" in args else "."
    name = os.path.splitext(os.path.basename(path))[0]
    class_name = name[:1].upper() + name[1:]
    with open(path, encoding="utf-8") as f:
        source = f.read()
    try:
        result = kiln.compile(class_name, source)
    except kiln.ParseException as e:
        sys.exit(path + ":" + e.message)
    for d in result.diagnostics:
        label = "error" if d.severity == kiln.Severity.ERROR else "warning"
        print(f"{path}:{d.line}:{d.column}: {label}: {d.message}", file=sys.stderr)
    if result.bytes is None:
        sys.exit(1)
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, class_name + ".class")
    with open(out, "wb") as f:
        f.write(bytes(result.bytes))
    print(f"wrote {out}: {len(result.bytes)} bytes")


if __name__ == "__main__":
    main(sys.argv[1:])
