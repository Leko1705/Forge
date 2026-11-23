/** The externals of conformance.forge. */
public final class ConformanceExternals {
    private ConformanceExternals() {}
    public static String int_text(Integer self) { return String.valueOf(self); }
    public static Integer str_toInt(String self) { return Integer.parseInt(self); }
    public static String str_shout(String self) { return self.toUpperCase() + "!"; }
}
