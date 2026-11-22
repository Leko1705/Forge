import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The externals of kiln.forge and jvm.forge. */
public final class KilnModuleExternals {
    private KilnModuleExternals() {}

    public static List<Object> str_utf8(String self) {
        List<Object> bytes = new ArrayList<>();
        for (byte b : self.getBytes(StandardCharsets.UTF_8)) bytes.add(b & 0xFF);
        return bytes;
    }

    public static String int_str(Integer self) { return String.valueOf(self); }

    public static Integer str_toInt(String self) { return Integer.parseInt(self); }

    public static String str_unquote(String self) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < self.length() - 1; i++) {
            char c = self.charAt(i);
            if (c == '\\' && i + 1 < self.length() - 1) {
                char e = self.charAt(++i);
                sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
