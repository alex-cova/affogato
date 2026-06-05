package dev.affogato.compiler.internal;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * Grammar synchronization validator.
 *
 * <p>Ensures that every keyword and operator token defined in the compiler's
 * {@code AffogatoLexer.g4} is also present in the IntelliJ plugin's
 * {@code Affogato.flex} and {@code Affogato.bnf}, and vice-versa, so that a
 * syntactic change to the language cannot silently diverge between the two
 * front-ends.
 *
 * <p>The test is intentionally file-path-based so it runs without any build
 * artefact pre-processing: it reads the grammar source files directly from the
 * project tree and parses their token literals with simple regular expressions.
 * This makes the test fast, dependency-free, and always up-to-date.
 *
 * <h2>What is checked</h2>
 * <ul>
 *   <li><strong>Keywords</strong> – single-quoted string literals that consist
 *       entirely of lower-case letters (e.g. {@code 'class'}, {@code 'return'}).
 *   <li><strong>Operators / punctuation</strong> – single-quoted string literals
 *       that consist entirely of non-letter characters (e.g. {@code '->'}, {@code '+='}).
 * </ul>
 *
 * <h2>What is intentionally NOT checked</h2>
 * <ul>
 *   <li>Token names (ANTLR rule names vs. IntelliJ {@code AffogatoTypes} field
 *       names) – the test only compares the raw literal values.
 *   <li>Whitespace, comment, identifier, and numeric literal rules – these are
 *       structural and differ by design between the two grammars.
 * </ul>
 */
public class GrammarSyncTest {

    // ── Relative paths from the project root ──────────────────────────────────

    private static final String ANTLR_LEXER_PATH =
            "affogato-compiler/src/main/antlr/dev/affogato/compiler/parser/AffogatoLexer.g4";

    private static final String JFLEX_PATH =
            "affogato-intellij-plugin/src/main/jflex/Affogato.flex";

    private static final String BNF_PATH =
            "affogato-intellij-plugin/src/main/grammar/Affogato.bnf";

    // ── Structural literals excluded from cross-grammar comparison ────────────
    //
    // Some tokens in AffogatoLexer.g4 begin with a literal prefix that appears
    // inside a named rule (e.g. LINE_COMMENT starts with '//', BLOCK_COMMENT
    // starts with '/*') yet the IntelliJ plugin represents those tokens via
    // regex patterns in JFlex/BNF rather than verbatim string literals.
    // Comparing them across grammars would always produce false positives,
    // so we exclude them from operator/keyword sync checks.
    private static final Set<String> STRUCTURAL_LITERALS = Set.of(
            "//",   // LINE_COMMENT prefix  – handled by regex in JFlex/BNF
            "/*"    // BLOCK_COMMENT prefix – handled by regex in JFlex/BNF
    );

    // ── Extraction patterns ────────────────────────────────────────────────────


    /**
     * Matches single-quoted literals in ANTLR ({@code 'class'}, {@code '->'}).
     * We reuse this for BNF too since Grammar-Kit uses the same quoting style.
     */
    private static final Pattern ANTLR_LITERAL =
            Pattern.compile("'([^']+)'");

    /**
     * Matches double-quoted literals in JFlex rule actions
     * ({@code "class"}, {@code "->"}).
     */
    private static final Pattern JFLEX_LITERAL =
            Pattern.compile("\"([^\"]+)\"\\s*\\{");

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    public void antlrKeywordsArePresentInJflex() throws IOException {
        Set<String> antlr = keywords(extractAntlrLiterals());
        Set<String> jflex = keywords(extractJflexLiterals());
        assertSubset("Keywords in AffogatoLexer.g4 but MISSING in Affogato.flex", antlr, jflex);
    }

    @Test
    public void jflexKeywordsArePresentInAntlr() throws IOException {
        Set<String> jflex = keywords(extractJflexLiterals());
        Set<String> antlr = keywords(extractAntlrLiterals());
        assertSubset("Keywords in Affogato.flex but MISSING in AffogatoLexer.g4", jflex, antlr);
    }

    @Test
    public void antlrKeywordsArePresentInBnf() throws IOException {
        Set<String> antlr = keywords(extractAntlrLiterals());
        Set<String> bnf   = keywords(extractBnfLiterals());
        assertSubset("Keywords in AffogatoLexer.g4 but MISSING in Affogato.bnf", antlr, bnf);
    }

    @Test
    public void bnfKeywordsArePresentInAntlr() throws IOException {
        Set<String> bnf  = keywords(extractBnfLiterals());
        Set<String> antlr = keywords(extractAntlrLiterals());
        assertSubset("Keywords in Affogato.bnf but MISSING in AffogatoLexer.g4", bnf, antlr);
    }

    @Test
    public void antlrOperatorsArePresentInJflex() throws IOException {
        Set<String> antlr = operators(extractAntlrLiterals());
        Set<String> jflex = operators(extractJflexLiterals());
        assertSubset("Operators in AffogatoLexer.g4 but MISSING in Affogato.flex", antlr, jflex);
    }

    @Test
    public void jflexOperatorsArePresentInAntlr() throws IOException {
        Set<String> jflex = operators(extractJflexLiterals());
        Set<String> antlr = operators(extractAntlrLiterals());
        assertSubset("Operators in Affogato.flex but MISSING in AffogatoLexer.g4", jflex, antlr);
    }

    @Test
    public void antlrOperatorsArePresentInBnf() throws IOException {
        Set<String> antlr = operators(extractAntlrLiterals());
        Set<String> bnf   = operators(extractBnfLiterals());
        assertSubset("Operators in AffogatoLexer.g4 but MISSING in Affogato.bnf", antlr, bnf);
    }

    @Test
    public void bnfOperatorsArePresentInAntlr() throws IOException {
        Set<String> bnf   = operators(extractBnfLiterals());
        Set<String> antlr = operators(extractAntlrLiterals());
        assertSubset("Operators in Affogato.bnf but MISSING in AffogatoLexer.g4", bnf, antlr);
    }

    // ── Extraction helpers ────────────────────────────────────────────────────

    /**
     * Reads and returns all single-quoted literal values from named (non-fragment)
     * token rules in {@code AffogatoLexer.g4}.
     *
     * <p>Only lines of the form {@code TOKEN_NAME: 'literal';} or
     * {@code TOKEN_NAME: 'literal' ...;} are considered. Fragment rules and their
     * internal character-class patterns (e.g. interpolation markers, comment
     * delimiters, escape sequences) are intentionally excluded because they are
     * implementation details of the lexer, not surface-level language tokens.
     */
    private Set<String> extractAntlrLiterals() throws IOException {
        String source = Files.readString(projectFile(ANTLR_LEXER_PATH));
        Set<String> result = new LinkedHashSet<>();
        // Match top-level named token rules only:
        //   ^UPPER_NAME: 'literal'...
        // We stop before the first fragment keyword so we don't pick up
        // fragment internals.
        Pattern tokenRule = Pattern.compile(
                "^([A-Z][A-Z_]*)\\s*:\\s*'([^']+)'",
                Pattern.MULTILINE
        );
        Matcher m = tokenRule.matcher(source);
        while (m.find()) {
            String lit = m.group(2);
            if (!STRUCTURAL_LITERALS.contains(lit)) {
                result.add(lit);
            }
        }
        return result;
    }

    /** Reads and returns all double-quoted rule-trigger literals from {@code Affogato.flex}. */
    private Set<String> extractJflexLiterals() throws IOException {
        return extractWithPattern(projectFile(JFLEX_PATH), JFLEX_LITERAL);
    }

    /** Reads and returns all single-quoted literal values from {@code Affogato.bnf}. */
    private Set<String> extractBnfLiterals() throws IOException {
        // BNF uses  KEY='literal'  inside the tokens block, and 'literal' inline in rules.
        // Both are captured by ANTLR_LITERAL (single-quote pattern).
        String source = Files.readString(projectFile(BNF_PATH));
        // Strip the regexp: values – they are structural, not keyword/operator tokens.
        source = source.replaceAll("'regexp:[^']*'", "");
        Set<String> result = new LinkedHashSet<>();
        Matcher m = ANTLR_LITERAL.matcher(source);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private static Set<String> extractWithPattern(Path file, Pattern pattern) throws IOException {
        String source = Files.readString(file);
        Set<String> result = new LinkedHashSet<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    // ── Classification helpers ────────────────────────────────────────────────

    /**
     * Returns the subset of {@code literals} that look like keywords: one or
     * more lower-case ASCII letters only (e.g. {@code "class"}, {@code "return"}).
     */
    private static Set<String> keywords(Set<String> literals) {
        Set<String> result = new TreeSet<>();
        for (String lit : literals) {
            if (lit.matches("[a-z]+")) {
                result.add(lit);
            }
        }
        return result;
    }

    /**
     * Returns the subset of {@code literals} that look like operators or
     * punctuation: one or more non-letter characters only
     * (e.g. {@code "->"}, {@code "+="},  {@code "{"}).
     */
    private static Set<String> operators(Set<String> literals) {
        Set<String> result = new TreeSet<>();
        for (String lit : literals) {
            if (!lit.isEmpty() && lit.chars().noneMatch(Character::isLetter)) {
                result.add(lit);
            }
        }
        return result;
    }

    // ── Assertion helpers ─────────────────────────────────────────────────────

    /**
     * Asserts that every element of {@code required} is present in
     * {@code available}, printing a sorted, human-readable list of any missing
     * tokens to make fixing the divergence straightforward.
     */
    private static void assertSubset(String label, Set<String> required, Set<String> available) {
        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(label).append(":\n");
            for (String token : missing) {
                sb.append("  '").append(token).append("'\n");
            }
            fail(sb.toString());
        }
    }

    // ── File-system helpers ───────────────────────────────────────────────────

    /**
     * Resolves a project-root-relative path, searching upward from the working
     * directory until a directory containing {@code settings.gradle.kts} is
     * found.  This makes the test runnable both from an IDE (where the CWD is
     * typically the module root) and from {@code ./gradlew test} at the project
     * root.
     */
    private static Path projectFile(String relativePath) throws IOException {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path candidate = cwd;
        // Walk up at most 5 levels to find the project root.
        for (int i = 0; i < 5; i++) {
            if (Files.exists(candidate.resolve("settings.gradle.kts"))) {
                Path resolved = candidate.resolve(relativePath);
                if (Files.exists(resolved)) {
                    return resolved;
                }
            }
            Path parent = candidate.getParent();
            if (parent == null) break;
            candidate = parent;
        }
        throw new IOException(
                "Cannot locate project root containing settings.gradle.kts. " +
                "Searched from: " + cwd + ". Relative path: " + relativePath
        );
    }
}
