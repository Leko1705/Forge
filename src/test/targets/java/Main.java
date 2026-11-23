public final class Main {
    public static void main(String[] args) {
        for (Object line : Conformance.report()) System.out.println(line);
    }
}
