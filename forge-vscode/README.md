# Forge for VS Code

Support for [Forge](https://github.com/Leko1705/Forge), the compiler definition language, in VS Code. The extension
runs the Forge language server ([forge-lsp](../forge-lsp)), which uses the Forge compiler itself, so the editor sees
what `forge -f` sees.

- **Diagnostics** while typing: the syntax errors of the compiler's parser and the errors and warnings of its
  analysis, with the imported files (as they are in their editors, also before they are saved) and the standard library.
- **Completion** of members after `.`, of the names in scope and of keywords.
- **Go to Definition**, also into imported files and the standard library, and from an `import` to its file;
  **Find All References** in the open files; **hover** documentation.
- **Outline** and breadcrumbs, **semantic highlighting** by what the compiler resolves a name to (an attribute, a
  field, a port, ...), and folding.
- **Forge: Compile** (in the editor's run menu, the context menus and the command palette) runs the compiler on the
  file, with the settings `forge.compile.*`: the output directory (`build/generated/forge` by default, relative to
  the workspace folder), the package, the target (`kotlin`, `java`, `python`, `cpp`, `rust`, `ocaml`, `javascript`)
  and optimization.

## Requirements

Java 21 or newer. The extension looks for it in the setting `forge.java.home`, in `JAVA_HOME`, and on the `PATH`.

## Settings

| Setting | |
|---|---|
| `forge.java.home` | The Java installation that runs the server and the compiler. |
| `forge.server.path` | Another jar of the language server, e.g. one built from the repository. |
| `forge.server.vmArguments` | Arguments for the Java VM of the server, e.g. `-Xmx1g`. |
| `forge.compile.outputDirectory`, `.package`, `.target`, `.optimize`, `.keep` | The options of the compiler (`-d`, `-p`, `-t`, `-opt`, `-keep`). |
| `forge.trace.server` | Traces the messages between VS Code and the server (in the output "Forge"). |

## Building

```
npm install
npm run server     # builds the language server with Gradle and copies it to server/forge-lsp.jar
npm run build      # bundles the extension to out/extension.cjs
npm test           # the grammar, finding Java, the command line of the compiler
npm run package    # forge-vscode-<version>.vsix
code --install-extension forge-vscode-1.0.0.vsix
```

To try changes, open this folder in VS Code and run **Run the Forge extension** (F5): it opens the examples of the
repository in a window with the extension.
