package dev.affogato.compiler;

import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import java.util.List;
import java.util.Random;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.Test;

public final class AffogatoLexerParserFuzzTest {
    private static final List<String> TOKENS = List.of(
            "class", "func", "let", "var", "return", "if", "else", "for", "in", "while",
            "true", "false", "null", "this", "new", "String", "Int", "List",
            "name", "value", "count", "run", "main", "x", "y", "z",
            "0", "1", "42", "\"text\"", "\"${value}\"",
            "{", "}", "(", ")", "[", "]", ".", ",", ":", ";", "=", "+", "-", "*", "/", "==", "&&", "||", "->"
    );

    @Test(timeout = 5000)
    public void randomInputsDoNotHangOrOverflowLexerParser() {
        Random random = new Random(0xAFF06A70L);
        for (int iteration = 0; iteration < 250; iteration++) {
            String source = randomSource(random);
            parse(source);
        }
    }

    private static String randomSource(Random random) {
        StringBuilder source = new StringBuilder();
        int count = 8 + random.nextInt(80);
        for (int index = 0; index < count; index++) {
            if (random.nextInt(9) == 0) {
                source.append('\n');
            }
            source.append(TOKENS.get(random.nextInt(TOKENS.size())));
            if (random.nextBoolean()) {
                source.append(' ');
            }
        }
        return source.toString();
    }

    private static void parse(String source) {
        try {
            AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(source));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new SilentErrorListener());
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            AffogatoParser parser = new AffogatoParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new SilentErrorListener());
            parser.compilationUnit();
        } catch (StackOverflowError overflow) {
            throw new AssertionError("Parser overflowed for source: " + source, overflow);
        } catch (RuntimeException ignored) {
            // Syntax errors and recovery failures are acceptable; crashes/hangs are not.
        }
    }

    private static final class SilentErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String message, RecognitionException exception) {
        }
    }
}
