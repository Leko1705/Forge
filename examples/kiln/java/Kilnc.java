import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** The Kiln compiler, generated in Java: java Kilnc <file.kiln> [-d <dir>] */
public final class Kilnc {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: java Kilnc <file.kiln> [-d <dir>]");
            System.exit(1);
        }
        File file = new File(args[0]);
        String outDir = args.length >= 3 && args[1].equals("-d") ? args[2] : ".";
        String name = file.getName().replaceFirst("\\.[^.]*$", "");
        String className = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        KilnModule.Compiled result;
        try {
            result = KilnModule.compile(className, Files.readString(file.toPath()));
        } catch (KilnModule.ParseException e) {
            System.err.println(file + ":" + e.getMessage());
            System.exit(1);
            return;
        }
        for (Object o : result.diagnostics) {
            KilnModule.Diagnostic d = (KilnModule.Diagnostic) o;
            String label = d.severity.equals(KilnModule.Severity.ERROR) ? "error" : "warning";
            System.err.println(file + ":" + d.line + ":" + d.column + ": " + label + ": " + d.message);
        }
        if (result.bytes == null) System.exit(1);
        List<Object> bytes = result.bytes;
        byte[] data = new byte[bytes.size()];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (int) (Integer) bytes.get(i);
        File out = new File(outDir, className + ".class");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), data);
        System.out.println("wrote " + out + ": " + data.length + " bytes");
    }
}
