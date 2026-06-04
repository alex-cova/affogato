package dev.affogato.golden.interop;

public final class JavaOuter {
    private JavaOuter() {
    }

    public static final class Inner {
        private final String label;

        public Inner(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
