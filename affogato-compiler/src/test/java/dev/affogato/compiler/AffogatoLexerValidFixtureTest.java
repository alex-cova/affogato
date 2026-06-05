package dev.affogato.compiler;

import dev.affogato.compiler.fixture.DiagnosticFixtureRunner;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Lexer/parser smoke fixtures that must compile under {@code src/test/resources/lexer-valid/}.
 */
public final class AffogatoLexerValidFixtureTest {
    private static final Path ROOT = Path.of("src/test/resources/lexer-valid");

    @Test
    public void lexerValidFixturesCompile() throws Exception {
        DiagnosticFixtureRunner.runAllValidUnder(ROOT);
    }
}
