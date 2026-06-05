package dev.affogato.compiler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.affogato.compiler.parser.AffogatoLexer;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.Test;

public final class AffogatoStringLiteralTokenTest {
    @Test
    public void interpolatedStringsRemainSingleStringLiteralTokens() {
        List<String> inputs = List.of(
                "\"plain\"",
                "\"$name\"",
                "\"${x}\"",
                "\"${greet(\"x\")}\"",
                "\"a ${b} c\"",
                "\"${f(\"${g}\")}\"",
                "\"${call(() -> { return \"z\"; })}\"",
                "\"\\$literal\"",
                "\"x\\u0041y\"");

        for (String input : inputs) {
            AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(input));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();
            List<Token> visible = tokens.getTokens().stream()
                    .filter(token -> token.getType() != Token.EOF)
                    .toList();

            assertEquals(input, 1, visible.size());
            assertEquals(input, AffogatoLexer.StringLiteral, visible.getFirst().getType());
            assertEquals(input, visible.getFirst().getText());
        }
    }

    @Test
    public void unsupportedTokenScannerDoesNotBreakOnQuotesInsideInterpolation() {
        String input = "\"${m[\"k\"]}\"";
        AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(input));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        assertTrue(tokens.getTokens().stream()
                .anyMatch(token -> token.getType() == AffogatoLexer.StringLiteral
                        && token.getText().equals(input)));
    }
}
