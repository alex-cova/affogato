package dev.affogato.compiler.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class AffogatoCliTest {
    @Test
    public void helpFlagPrintsUsageAndReturnsZero() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            int code = AffogatoCli.run(new String[] {"--help"});
            require(code == 0, "Help should exit 0, was " + code);
            String text = out.toString(StandardCharsets.UTF_8);
            require(text.contains("Usage:"), "Help should print usage.");
            require(text.contains("--classpath"), "Help should document classpath.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void missingArgsReturnsUsageError() {
        require(AffogatoCli.run(new String[0]) == 2, "No args should return 2.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
