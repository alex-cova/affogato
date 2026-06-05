package dev.affogato.compiler;

import dev.affogato.compiler.fixture.DiagnosticFixtureRunner;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/**
 * Parser-focused failure fixtures under {@code src/test/resources/parser/}.
 */
public final class AffogatoParserFixtureTest {
    private static final Path PARSER_ROOT = Path.of("src/test/resources/parser");

    @Test
    public void parserFixturesFailWithExpectedDiagnostics() throws Exception {
        DiagnosticFixtureRunner.runAllUnder(PARSER_ROOT, List.of());
    }
}
