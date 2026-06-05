package dev.affogato.compiler;

import dev.affogato.compiler.fixture.DiagnosticFixtureRunner;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/**
 * Lexer-focused failure fixtures under {@code src/test/resources/lexer/}.
 */
public final class AffogatoLexerFixtureTest {
    private static final Path LEXER_ROOT = Path.of("src/test/resources/lexer");

    @Test
    public void lexerFixturesFailWithExpectedDiagnostics() throws Exception {
        DiagnosticFixtureRunner.runAllUnder(LEXER_ROOT, List.of());
    }
}
