package dev.affogato.compiler;

import dev.affogato.compiler.fixture.DiagnosticFixtureRunner;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Parser smoke fixtures that must compile under {@code src/test/resources/parser-valid/}.
 */
public final class AffogatoParserValidFixtureTest {
    private static final Path ROOT = Path.of("src/test/resources/parser-valid");

    @Test
    public void parserValidFixturesCompile() throws Exception {
        DiagnosticFixtureRunner.runAllValidUnder(ROOT);
    }
}
