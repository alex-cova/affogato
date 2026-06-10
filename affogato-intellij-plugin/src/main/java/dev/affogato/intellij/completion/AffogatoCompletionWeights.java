package dev.affogato.intellij.completion;

public final class AffogatoCompletionWeights {
    public static final int SNIPPET = 500;
    public static final int LOCAL = 420;
    public static final int NAMED_ARGUMENT = 410;
    public static final int PARAMETER = 400;
    public static final int SAME_PACKAGE_TYPE = 360;
    public static final int ENCLOSING_MEMBER = 340;
    public static final int PROJECT_TYPE = 280;
    public static final int IMPORTED_JAVA = 240;
    public static final int JAVA_CLASSPATH = 180;
    public static final int KEYWORD = 120;
    public static final int DEFAULT = 0;

    private AffogatoCompletionWeights() {
    }
}
