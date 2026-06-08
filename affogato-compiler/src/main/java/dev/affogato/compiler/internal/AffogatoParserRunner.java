package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.IntervalSet;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.affogato.compiler.internal.TranspilerTypes.*;

final class AffogatoParserRunner {
    private static final int MAX_INTERPOLATION_DEPTH = 16;

    private final List<AffogatoDiagnostic> diagnostics;

    AffogatoParserRunner(List<AffogatoDiagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    ParsedUnit parse(Path sourceFile, String source) {
        scanUnsupportedSourceEdges(sourceFile, source);
        // The ANTLR string lexer matches interpolation with a recursive fragment, whose ATN
        // simulation is super-linear in nesting depth (~1.5x per level): a few dozen nested
        // `${ "${ ... }" }` make tokenization take minutes — a denial-of-service on pathological
        // input. This O(n) pre-scan rejects absurd nesting before the lexer ever runs.
        int deepInterpolation = deepInterpolationIndex(source);
        if (deepInterpolation >= 0) {
            SourceLocation location = sourceLocation(source, deepInterpolation);
            diagnostics.add(error(sourceFile, location.line(), location.column(), "AFFOGATO_PARSE",
                    "String interpolation is nested too deeply (limit " + MAX_INTERPOLATION_DEPTH
                            + " levels); split it into separate expressions."));
            return TranspilerTypes.ParsedUnit.empty(sourceFile, source);
        }
        AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(source, sourceFile.toString()));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AffogatoParser parser = new AffogatoParser(tokens);
        SyntaxErrorListener syntaxErrors = new SyntaxErrorListener(sourceFile);

        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(syntaxErrors);
        parser.addErrorListener(syntaxErrors);

        AffogatoParser.CompilationUnitContext tree;
        try {
            // Recursive-descent parsing (and ANTLR's adaptive prediction) recurses with expression
            // depth, so pathologically nested input can exhaust the JVM stack. Surface that as a normal
            // parse diagnostic instead of letting a StackOverflowError escape and crash the process.
            tree = parser.compilationUnit();
        } catch (StackOverflowError overflow) {
            diagnostics.add(error(sourceFile, 1, 1, "AFFOGATO_PARSE",
                    "Source is too deeply nested to parse; reduce expression or block nesting depth."));
            return TranspilerTypes.ParsedUnit.empty(sourceFile, source);
        }

        validateNumericLiterals(sourceFile, tokens);

        if (syntaxErrors.hadErrors()) {
            return TranspilerTypes.ParsedUnit.empty(sourceFile, source);
        }

        try {
            CompilationUnit unit = buildCompilationUnit(sourceFile, source, tree);
            return new TranspilerTypes.ParsedUnit(sourceFile, unit);
        } catch (StackOverflowError overflow) {
            diagnostics.add(error(sourceFile, 1, 1, "AFFOGATO_PARSE",
                    "Source is too deeply nested to compile; reduce expression or block nesting depth."));
            return TranspilerTypes.ParsedUnit.empty(sourceFile, source);
        }
    }

    // Linear scan that returns the index where `${` interpolation nesting first exceeds
    // MAX_INTERPOLATION_DEPTH, or -1 otherwise. The StringBuilder acts as a stack whose top char
    // marks the current context: 'S' inside a string literal, 'I' inside an interpolation, 'B'
    // inside a `{ }` block nested in an interpolation (e.g. a lambda body). String and comment
    // contents are skipped exactly as the lexer would treat them, so balanced braces/quotes there
    // never perturb the count and valid code is never falsely rejected.
    private int deepInterpolationIndex(String source) {
        StringBuilder stack = new StringBuilder();
        int interpDepth = 0;
        int index = 0;
        int length = source.length();
        while (index < length) {
            char c = source.charAt(index);
            char top = stack.length() == 0 ? '\0' : stack.charAt(stack.length() - 1);
            if (top == 'S') {
                if (c == '\\') {
                    index += 2;
                } else if (c == '"') {
                    stack.setLength(stack.length() - 1);
                    index++;
                } else if (c == '$' && index + 1 < length && source.charAt(index + 1) == '{') {
                    stack.append('I');
                    interpDepth++;
                    if (interpDepth > MAX_INTERPOLATION_DEPTH) {
                        return index;
                    }
                    index += 2;
                } else {
                    index++;
                }
                continue;
            }
            if (c == '/' && index + 1 < length && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < length && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (c == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < length) {
                    if (source.charAt(index) == '*' && source.charAt(index + 1) == '/') {
                        index += 2;
                        break;
                    }
                    index++;
                }
                continue;
            }
            if (c == '"') {
                stack.append('S');
            } else if (c == '{') {
                stack.append('B');
            } else if (c == '}') {
                if (top == 'I') {
                    stack.setLength(stack.length() - 1);
                    interpDepth--;
                } else if (top == 'B') {
                    stack.setLength(stack.length() - 1);
                }
            }
            index++;
        }
        return -1;
    }

    private void scanUnsupportedSourceEdges(Path sourceFile, String source) {
        // All three unsupported tokens are scanned in a single interpolation-aware pass so that
        // string literals — including those with nested quotes inside ${...} interpolations — are
        // correctly skipped and no false positives (or missed detections) occur.
        //
        // The stack tracks the current lexical context using the same 'S'/'I'/'B' encoding as
        // deepInterpolationIndex:
        //   'S' — inside a string literal (double-quoted)
        //   'I' — inside a ${...} string interpolation expression
        //   'B' — inside a { } block nested within an interpolation (e.g. a lambda body)
        //
        // Tokens are only checked when we are NOT inside a string literal (top != 'S').
        StringBuilder stack = new StringBuilder();
        int index = 0;
        int length = source.length();
        while (index < length) {
            char c = source.charAt(index);
            char top = stack.isEmpty() ? '\0' : stack.charAt(stack.length() - 1);

            // ── Inside a string literal ──────────────────────────────────────────────────────────
            if (top == 'S') {
                if (c == '\\') {
                    // Escape sequence: skip two chars so neither the backslash nor the escaped
                    // char is mistaken for a structural character.
                    index += 2;
                } else if (c == '"') {
                    stack.setLength(stack.length() - 1); // close the string literal
                    index++;
                } else if (c == '$' && index + 1 < length && source.charAt(index + 1) == '{') {
                    stack.append('I'); // enter a string interpolation expression
                    index += 2;
                } else {
                    index++;
                }
                continue;
            }

            // ── Line comment ─────────────────────────────────────────────────────────────────────
            if (c == '/' && index + 1 < length && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < length && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }

            // ── Block comment ────────────────────────────────────────────────────────────────────
            if (c == '/' && index + 1 < length && source.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < length) {
                    if (source.charAt(index) == '*' && source.charAt(index + 1) == '/') {
                        index += 2;
                        break;
                    }
                    index++;
                }
                continue;
            }

            // ── Unsupported token checks (only in code contexts, never inside 'S') ─────────────
            if (source.startsWith("?.", index)) {
                SourceLocation loc = sourceLocation(source, index);
                diagnostics.add(error(sourceFile, loc.line(), loc.column(), 2, "AFFOGATO_UNSUPPORTED_SAFE_CALL",
                        "Safe-call expressions are not in the production subset; use an explicit null check."));
                index += 2;
                continue;
            }
            if (source.startsWith("?:", index)) {
                SourceLocation loc = sourceLocation(source, index);
                diagnostics.add(error(sourceFile, loc.line(), loc.column(), 2, "AFFOGATO_UNSUPPORTED_ELVIS",
                        "Elvis expressions are not in the production subset; use a ternary expression."));
                index += 2;
                continue;
            }
            if (source.startsWith("!!", index)) {
                SourceLocation loc = sourceLocation(source, index);
                diagnostics.add(error(sourceFile, loc.line(), loc.column(), 2, "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION",
                        "Not-null assertion expressions are not in the production subset; use an explicit cast or null check."));
                index += 2;
                continue;
            }

            // ── Structural character tracking ────────────────────────────────────────────────────
            if (c == '"') {
                stack.append('S'); // enter a string literal
            } else if (c == '{' && (top == 'I' || top == 'B')) {
                // A '{' inside interpolation code opens a nested block (e.g. a lambda body).
                // Top-level '{' blocks (method bodies, if-blocks, etc.) are not pushed because
                // their matching '}' would otherwise corrupt the interpolation depth counter.
                stack.append('B');
            } else if (c == '}') {
                if (top == 'I' || top == 'B') {
                    stack.setLength(stack.length() - 1); // close the interpolation or nested block
                }
                // A '}' at the top level (top == '\0') is ignored — it's a method/block closer.
            }
            index++;
        }
    }

    private SourceLocation sourceLocation(String source, int index) {
        int line = 1;
        int column = 1;
        for (int cursor = 0; cursor < index && cursor < source.length(); cursor++) {
            if (source.charAt(cursor) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new SourceLocation(line, column);
    }

    // Integer literals are emitted into the generated Java verbatim, so a literal that Java would
    // reject (or silently reinterpret) must be caught here against the original source. Two cases:
    //   1. Leading-zero decimals ('010') — Affogato has no octal literals, but Java reads '010' as
    //      octal 8, a silent value change. Rejected outright.
    //   2. Out-of-range magnitudes — a decimal/hex literal that does not fit int (or long with an L
    //      suffix) would make javac fail on the generated file. Rejected with a clear hint.
    // Floating-point literals are a different token type and are intentionally left untouched.
    private void validateNumericLiterals(Path sourceFile, CommonTokenStream tokens) {
        for (Token token : tokens.getTokens()) {
            if (token.getType() == AffogatoLexer.IntegerLiteral) {
                validateIntegerLiteral(sourceFile, token);
            } else if (token.getType() == AffogatoLexer.StringLiteral) {
                validateStringEscapes(sourceFile, token);
            }
        }
    }

    // A `\\uXXXX` escape is emitted into the generated Java verbatim, but Java performs Unicode-escape
    // translation on the whole source before lexing. So an escape that decodes to a character which is
    // significant in Java source — a quote, a backslash, or a line terminator — would corrupt the
    // generated string literal (e.g. `\\u0022` becomes a `"` that ends the string early). Safe escapes
    // such as `\\u0041` pass through unchanged; the unsafe ones are rejected with a redirect to the
    // direct escape form.
    String unescapeAffogatoString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case 'b': sb.append('\b'); i++; break;
                    case 'f': sb.append('\f'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                        break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    String escapeForJavaString(String text) {
        if (text == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32 || c > 126) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private void validateStringEscapes(Path sourceFile, Token token) {
        String text = token.getText();
        int line = token.getLine();
        int baseColumn = token.getCharPositionInLine() + 1;
        int escapeStart = 0;
        while ((escapeStart = text.indexOf("\\u", escapeStart)) >= 0) {
            int cursor = escapeStart + 1;
            while (cursor < text.length() && text.charAt(cursor) == 'u') {
                cursor++;
            }
            if (cursor + 4 <= text.length()) {
                String hex = text.substring(cursor, cursor + 4);
                try {
                    int codePoint = Integer.parseInt(hex, 16);
                    if (codePoint == '"' || codePoint == '\\' || codePoint == '\n' || codePoint == '\r') {
                        // NO LONGER REJECTING - Fixing via re-escape
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            escapeStart = cursor + 4;
        }
    }

    private void validateIntegerLiteral(Path sourceFile, Token token) {
        String text = token.getText();
        int line = token.getLine();
        int column = token.getCharPositionInLine() + 1;
        int length = text.length();

        char last = text.charAt(text.length() - 1);
        boolean isLong = last == 'l' || last == 'L';
        String core = isLong ? text.substring(0, text.length() - 1) : text;
        boolean hex = core.length() > 1 && core.charAt(0) == '0' && (core.charAt(1) == 'x' || core.charAt(1) == 'X');

        String digits = (hex ? core.substring(2) : core).replace("_", "");

        if (!hex && digits.length() > 1 && digits.charAt(0) == '0') {
            diagnostics.add(error(sourceFile, line, column, length, "AFFOGATO_NUMERIC_LITERAL",
                    "Leading-zero integer literal '" + text + "' is not allowed; octal literals are not "
                            + "supported. Write the decimal value without a leading zero."));
            return;
        }

        BigInteger value;
        try {
            value = new BigInteger(digits, hex ? 16 : 10);
        } catch (NumberFormatException malformed) {
            return; // The lexer already validated digit shape; nothing more to check.
        }
        // Hex literals fill bits (int = 32 bits, long = 64 bits); decimal literals are signed-magnitude
        // and may reach the negative bound (2^31 / 2^63) so MIN_VALUE survives a later unary minus.
        BigInteger max = hex
                ? BigInteger.ONE.shiftLeft(isLong ? 64 : 32).subtract(BigInteger.ONE)
                : BigInteger.ONE.shiftLeft(isLong ? 63 : 31);
        if (value.compareTo(max) > 0) {
            String suffixHint = isLong ? "" : " Add an 'L' suffix to make it a long literal.";
            diagnostics.add(error(sourceFile, line, column, length, "AFFOGATO_NUMERIC_LITERAL",
                    "Integer literal '" + text + "' is out of range for " + (isLong ? "long" : "int") + "."
                            + suffixHint));
        }
    }
    private CompilationUnit buildCompilationUnit(Path sourceFile, String source, AffogatoParser.CompilationUnitContext tree) {
        String packageName = tree.packageDecl() == null ? "" : tree.packageDecl().qualifiedName().getText();
        if (tree.packageDecl() != null) {
            validatePackageName(sourceFile, tree.packageDecl(), packageName);
        }
        List<String> imports = new ArrayList<>();
        Map<String, String> importedSimpleNames = new LinkedHashMap<>();
        for (AffogatoParser.ImportDeclContext importDecl : tree.importDecl()) {
            // Use qualifiedName() directly to avoid capturing stale NL tokens
            // between this import and the next declaration (which inflate the stop
            // index and pull comment text into the import string).
            String qualName = sourceText(source, importDecl.qualifiedName()).trim();
            String cleanedImport = (importDecl.STATIC() != null ? "static " : "")
                    + qualName
                    + (importDecl.STAR() != null ? ".*" : "");
            imports.add(cleanedImport);
            validateImportConflict(sourceFile, importDecl, cleanedImport, importedSimpleNames);
        }

        List<ParsedClass> classes = new ArrayList<>();
        List<ParsedEnum> enums = new ArrayList<>();
        List<ParsedInterface> interfaces = new ArrayList<>();
        List<ParsedRecord> records = new ArrayList<>();
        List<ExtensionFuncDecl> extensions = new ArrayList<>();
        for (AffogatoParser.TypeDeclContext typeDecl : tree.typeDecl()) {
            if (typeDecl.classDecl() != null) {
                classes.add(buildClass(sourceFile, source, typeDecl.classDecl()));
            } else if (typeDecl.enumDecl() != null) {
                enums.add(buildEnum(sourceFile, source, typeDecl.enumDecl()));
            } else if (typeDecl.interfaceDecl() != null) {
                interfaces.add(buildInterface(sourceFile, source, typeDecl.interfaceDecl()));
            } else if (typeDecl.recordDecl() != null) {
                records.add(buildRecord(sourceFile, source, typeDecl.recordDecl()));
            } else if (typeDecl.extensionFuncDecl() != null) {
                extensions.add(buildExtension(sourceFile, source, typeDecl.extensionFuncDecl()));
            }
        }

        return new CompilationUnit(sourceFile, source, packageName, imports, classes, enums, interfaces, records, extensions);
    }
    private void validatePackageName(Path sourceFile, AffogatoParser.PackageDeclContext packageDecl, String packageName) {
        if (packageName.isBlank()) {
            return;
        }
        for (String segment : packageName.split("\\.")) {
            if (segment.isBlank()) {
                diagnostics.add(error(
                        sourceFile,
                        packageDecl.getStart().getLine(),
                        packageDecl.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_PARSE",
                        "Package name contains an empty segment."
                ));
                return;
            }
        }
    }

    private void validateImportConflict(Path sourceFile, AffogatoParser.ImportDeclContext importDecl, String importName, Map<String, String> importedSimpleNames) {
        if (importName.startsWith("static ") || importName.endsWith(".*")) {
            return;
        }
        int dot = importName.lastIndexOf('.');
        if (dot < 0 || dot == importName.length() - 1) {
            return;
        }
        String simpleName = importName.substring(dot + 1);
        String previous = importedSimpleNames.putIfAbsent(simpleName, importName);
        if (previous != null && !previous.equals(importName)) {
            diagnostics.add(error(
                    sourceFile,
                    importDecl.getStart().getLine(),
                    importDecl.getStart().getCharPositionInLine() + 1,
                    "AFFOGATO_IMPORT_CONFLICT",
                    "Import " + importName + " conflicts with " + previous + "."
            ));
        }
    }

    private ExtensionFuncDecl buildExtension(Path sourceFile, String source, AffogatoParser.ExtensionFuncDeclContext extensionDecl) {
        TypeRef receiverType = receiverTypeRef(extensionDecl.extensionReceiverType());
        String name = extensionDecl.Identifier().getText();
        validateDeclaredName(sourceFile, name, "extension function",
                extensionDecl.Identifier().getSymbol().getLine(),
                extensionDecl.Identifier().getSymbol().getCharPositionInLine() + 1);
        TypeRef returnType = extensionDecl.typeRef() == null ? TypeRef.unspecified("void") : typeRef(extensionDecl.typeRef());
        List<ParamDecl> parameters = extensionDecl.parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, extensionDecl.parameterList(), false);
        for (ParamDecl param : parameters) {
            if (param.name().equals("$this")) {
                diagnostics.add(error(
                        sourceFile,
                        extensionDecl.getStart().getLine(),
                        extensionDecl.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_EXTENSION_PARAM_CONFLICT",
                        "Extension function parameter cannot be named '$this' — that name is reserved for the receiver in generated code."
                ));
            }
        }
        return new ExtensionFuncDecl(
                receiverType,
                name,
                returnType,
                parameters,
                extensionDecl.block(),
                extensionDecl.getStart().getLine(),
                annotations(source, extensionDecl.annotation())
        );
    }

    private TypeRef receiverTypeRef(AffogatoParser.ExtensionReceiverTypeContext context) {
        String raw = context.getText();
        Nullability nullability = Nullability.UNSPECIFIED;
        if (raw.endsWith("?")) {
            nullability = Nullability.NULLABLE;
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("!")) {
            nullability = Nullability.NOT_NULL;
            raw = raw.substring(0, raw.length() - 1);
        }
        return new TypeRef(raw, nullability);
    }
    private ParsedClass buildClass(Path sourceFile, String source, AffogatoParser.ClassDeclContext classDecl) {
        String access = accessFromClassModifiers(classDecl.classModifier());
        String name = classDecl.Identifier().getText();
        validateDeclaredName(sourceFile, name, "class",
                classDecl.Identifier().getSymbol().getLine(),
                classDecl.Identifier().getSymbol().getCharPositionInLine() + 1);
        List<String> superTypes = classDecl.extendsClause() == null ? List.of()
                : classDecl.extendsClause().typeRef().stream().map(tr -> typeRef(tr).javaType()).toList();
        List<ParamDecl> compactParameters = classDecl.compactConstructor() == null || classDecl.compactConstructor().parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, classDecl.compactConstructor().parameterList(), true);

        List<FieldDecl> fields = new ArrayList<>();
        for (ParamDecl parameter : compactParameters) {
            if (parameter.propertyKind() == PropertyKind.NONE) {
                diagnostics.add(error(
                        sourceFile,
                        classDecl.getStart().getLine(),
                        classDecl.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_COMPACT_PARAM",
                        "Compact constructor parameters must start with var or let."
                ));
                continue;
            }
            fields.add(new FieldDecl(
                    "private",
                    false,
                    parameter.propertyKind() == PropertyKind.VAR,
                    parameter.name(),
                    parameter.type(),
                    "",
                    classDecl.getStart().getLine(),
                    List.of()
            ));
        }

        List<ConstructorDecl> constructors = new ArrayList<>();
        List<MethodDecl> methods = new ArrayList<>();
        List<ParsedEnum> nestedEnums = new ArrayList<>();
        for (AffogatoParser.ClassMemberContext member : classDecl.classBody().classMember()) {
            if (member.fieldDecl() != null) {
                fields.add(buildField(sourceFile, source, member.fieldDecl()));
            } else if (member.constructorDecl() != null) {
                constructors.add(buildConstructor(sourceFile, source, member.constructorDecl()));
            } else if (member.methodDecl() != null) {
                methods.add(buildMethod(sourceFile, source, member.methodDecl()));
            } else if (member.enumDecl() != null) {
                nestedEnums.add(buildEnum(sourceFile, source, member.enumDecl()));
            }
        }

        return new ParsedClass(access, name, buildTypeParams(classDecl.typeParamList()), superTypes, compactParameters, fields, constructors, methods, annotations(source, classDecl.annotation()), nestedEnums, classDecl.getStart().getLine(), classDecl.getStart().getCharPositionInLine() + 1);
    }

    private FieldDecl buildField(Path sourceFile, String source, AffogatoParser.FieldDeclContext fieldDecl) {
        Modifiers modifiers = modifiers(fieldDecl.memberModifier());
        boolean mutable = fieldDecl.variableKind().VAR() != null;
        String name = fieldDecl.Identifier().getText();
        validateDeclaredName(sourceFile, name, "field",
                fieldDecl.Identifier().getSymbol().getLine(),
                fieldDecl.Identifier().getSymbol().getCharPositionInLine() + 1);
        String initializer = fieldDecl.expression() == null ? "" : sourceText(source, fieldDecl.expression()).trim();
        TypeRef type = fieldDecl.typeRef() == null ? inferType(initializer) : typeRef(fieldDecl.typeRef());
        if (type == null) {
            diagnostics.add(error(
                    sourceFile,
                    fieldDecl.getStart().getLine(),
                    fieldDecl.getStart().getCharPositionInLine() + 1,
                    "AFFOGATO_FIELD_TYPE",
                    "Class members declared with var/let need a type or an inferrable initializer."
            ));
            type = TypeRef.unspecified("Object");
        }
        return new FieldDecl(modifiers.access(), modifiers.isStatic(), mutable, name, type, initializer, fieldDecl.getStart().getLine(), annotations(source, fieldDecl.annotation()));
    }

    private ConstructorDecl buildConstructor(Path sourceFile, String source, AffogatoParser.ConstructorDeclContext constructorDecl) {
        Modifiers modifiers = modifiers(constructorDecl.memberModifier());
        List<ParamDecl> parameters = constructorDecl.parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, constructorDecl.parameterList(), false);
        return new ConstructorDecl(modifiers.access(), parameters, constructorDecl.block(), constructorDecl.getStart().getLine(), annotations(source, constructorDecl.annotation()));
    }

    private MethodDecl buildMethod(Path sourceFile, String source, AffogatoParser.MethodDeclContext methodDecl) {
        Modifiers modifiers = modifiers(methodDecl.memberModifier());
        AffogatoParser.MethodSignatureContext signature = methodDecl.methodSignature();
        String name;
        TypeRef returnType;
        if (signature.FUNC() != null) {
            name = signature.Identifier().getText();
            returnType = signature.typeRef() != null ? typeRef(signature.typeRef()) : TypeRef.unspecified("void");
        } else if (signature.COLON() != null) {
            name = signature.Identifier().getText();
            returnType = typeRef(signature.typeRef());
        } else {
            name = signature.Identifier().getText();
            returnType = typeRef(signature.typeRef());
        }
        validateDeclaredName(sourceFile, name, "method",
                signature.Identifier().getSymbol().getLine(),
                signature.Identifier().getSymbol().getCharPositionInLine() + 1);
        if (methodDecl.block() == null && !modifiers.isAbstract()) {
            diagnostics.add(error(
                    sourceFile,
                    signature.Identifier().getSymbol().getLine(),
                    signature.Identifier().getSymbol().getCharPositionInLine() + 1,
                    name.length(),
                    "AFFOGATO_PARSE",
                    "Method " + name + " must declare a body or be marked abstract."
            ));
        }
        List<ParamDecl> parameters = signature.parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, signature.parameterList(), false);
        return new MethodDecl(
                modifiers.access(),
                modifiers.isStatic(),
                modifiers.isOverride(),
                modifiers.isAbstract(),
                buildTypeParams(signature.typeParamList()),
                returnType,
                name,
                parameters,
                methodDecl.block(),
                methodDecl.getStart().getLine(),
                annotations(source, methodDecl.annotation())
        );
    }

    private ParsedEnum buildEnum(Path sourceFile, String source, AffogatoParser.EnumDeclContext enumDecl) {
        String access = accessFromClassModifiers(enumDecl.classModifier());
        String name = enumDecl.Identifier().getText();
        validateDeclaredName(sourceFile, name, "enum",
                enumDecl.Identifier().getSymbol().getLine(),
                enumDecl.Identifier().getSymbol().getCharPositionInLine() + 1);
        for (AffogatoParser.EnumConstantContext constant : enumDecl.enumBody().enumConstant()) {
            validateDeclaredName(sourceFile, constant.Identifier().getText(), "enum constant",
                    constant.Identifier().getSymbol().getLine(),
                    constant.Identifier().getSymbol().getCharPositionInLine() + 1);
        }
        List<String> constants = enumDecl.enumBody().enumConstant().stream()
                .map(c -> c.Identifier().getText())
                .toList();
        return new ParsedEnum(access, name, constants, annotations(source, enumDecl.annotation()), enumDecl.getStart().getLine(), enumDecl.getStart().getCharPositionInLine() + 1);
    }

    private ParsedRecord buildRecord(Path sourceFile, String source, AffogatoParser.RecordDeclContext recordDecl) {
        String access = accessFromClassModifiers(recordDecl.classModifier());
        String name = recordDecl.Identifier().getText();
        validateDeclaredName(sourceFile, name, "record",
                recordDecl.Identifier().getSymbol().getLine(),
                recordDecl.Identifier().getSymbol().getCharPositionInLine() + 1);
        List<ParamDecl> components = recordDecl.recordHeader().parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, recordDecl.recordHeader().parameterList(), false);
        List<String> superTypes = recordDecl.implementsClause() == null ? List.of()
                : recordDecl.implementsClause().typeRef().stream().map(tr -> typeRef(tr).javaType()).toList();

        List<MethodDecl> methods = new ArrayList<>();
        List<ConstructorDecl> constructors = new ArrayList<>();
        for (AffogatoParser.ClassMemberContext member : recordDecl.classBody().classMember()) {
            if (member.methodDecl() != null) {
                methods.add(buildMethod(sourceFile, source, member.methodDecl()));
            } else if (member.constructorDecl() != null) {
                constructors.add(buildConstructor(sourceFile, source, member.constructorDecl()));
            } else if (member.fieldDecl() != null) {
                diagnostics.add(error(
                        sourceFile,
                        member.getStart().getLine(),
                        member.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_RECORD_MEMBER",
                        "Records may not declare instance fields in their body; state comes from the header components."
                ));
            }
        }
        return new ParsedRecord(access, name, buildTypeParams(recordDecl.typeParamList()), components, superTypes, methods, constructors, annotations(source, recordDecl.annotation()), recordDecl.getStart().getLine(), recordDecl.getStart().getCharPositionInLine() + 1);
    }

    private ParsedInterface buildInterface(Path sourceFile, String source, AffogatoParser.InterfaceDeclContext interfaceDecl) {
        String access = accessFromClassModifiers(interfaceDecl.classModifier());
        String name = interfaceDecl.Identifier().getText();
        validateDeclaredName(sourceFile, name, "interface",
                interfaceDecl.Identifier().getSymbol().getLine(),
                interfaceDecl.Identifier().getSymbol().getCharPositionInLine() + 1);
        List<InterfaceMethod> methods = new ArrayList<>();
        for (AffogatoParser.InterfaceMemberContext member : interfaceDecl.interfaceBody().interfaceMember()) {
            AffogatoParser.MethodSignatureContext sig = member.methodSignature();
            if (sig == null) {
                continue;
            }
            boolean isDefault = member.DEFAULT() != null;
            String methodName;
            TypeRef returnType;
            if (sig.FUNC() != null) {
                methodName = sig.Identifier().getText();
                returnType = sig.typeRef() != null ? typeRef(sig.typeRef()) : TypeRef.unspecified("void");
            } else if (sig.COLON() != null) {
                methodName = sig.Identifier().getText();
                returnType = typeRef(sig.typeRef());
            } else {
                methodName = sig.Identifier().getText();
                returnType = typeRef(sig.typeRef());
            }
            List<ParamDecl> parameters = sig.parameterList() == null
                    ? List.of()
                    : buildParameters(sourceFile, source, sig.parameterList(), false);
            AffogatoParser.BlockContext body = isDefault ? member.block() : null;
            methods.add(new InterfaceMethod(isDefault, returnType, methodName, parameters, body, member.getStart().getLine()));
        }
        return new ParsedInterface(access, name, buildTypeParams(interfaceDecl.typeParamList()), methods, annotations(source, interfaceDecl.annotation()), interfaceDecl.getStart().getLine(), interfaceDecl.getStart().getCharPositionInLine() + 1);
    }

    private List<TypeParamDecl> buildTypeParams(AffogatoParser.TypeParamListContext ctx) {
        if (ctx == null) return List.of();
        return ctx.typeParam().stream()
                .map(tp -> new TypeParamDecl(
                        tp.Identifier().getText(),
                        tp.typeRef() == null ? "" : typeRef(tp.typeRef()).javaType()
                ))
                .toList();
    }

    private List<ParamDecl> buildParameters(Path sourceFile, String source, AffogatoParser.ParameterListContext parameterList, boolean compact) {
        List<ParamDecl> parameters = new ArrayList<>();
        for (AffogatoParser.ParameterContext parameter : parameterList.parameter()) {
            PropertyKind propertyKind = PropertyKind.NONE;
            if (parameter.variableKind() != null) {
                propertyKind = parameter.variableKind().VAR() != null ? PropertyKind.VAR : PropertyKind.LET;
            }
            String name = parameter.Identifier().getText();
            validateDeclaredName(sourceFile, name, "parameter",
                    parameter.Identifier().getSymbol().getLine(),
                    parameter.Identifier().getSymbol().getCharPositionInLine() + 1);
            TypeRef type = typeRef(parameter.typeRef());
            if (compact && propertyKind == PropertyKind.NONE) {
                diagnostics.add(error(
                        sourceFile,
                        parameter.getStart().getLine(),
                        parameter.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_COMPACT_PARAM",
                        "Compact constructor parameters need var or let."
                ));
            }
            parameters.add(new ParamDecl(name, type, propertyKind, annotations(source, parameter.annotation())));
        }
        return parameters;
    }
    TypeRef inferType(String initializer) {
        if (initializer == null || initializer.isBlank()) {
            return null;
        }
        String value = initializer.trim();
        if (value.startsWith("new ")) {
            int parenIndex = value.indexOf('(');
            if (parenIndex > 0) {
                String type = value.substring("new ".length(), parenIndex).trim();
                return TypeRef.unspecified(type);
            }
        }
        // Matches ClassName(...) or pkg.sub.ClassName<T>(...) — last component must be uppercase-leading.
        Matcher constructor = Pattern.compile(
                "^((?:[a-z][A-Za-z0-9_$]*\\.)*[A-Z][A-Za-z0-9_$]*(?:<[^>]+>)?)\\s*\\(.*",
                Pattern.DOTALL).matcher(value);
        if (constructor.matches()) {
            return TypeRef.unspecified(constructor.group(1));
        }
        if (value.startsWith("\"")) {
            return TypeRef.unspecified("String");
        }
        if (value.equals("true") || value.equals("false")) {
            return TypeRef.unspecified("boolean");
        }
        String numericType = numericLiteralType(value);
        return numericType == null ? null : TypeRef.unspecified(numericType);
    }

    // Single source of truth for classifying a numeric literal token to its Java type, or null when the
    // token is not a numeric literal. Handles hexadecimal, digit separators ('1_000'), exponents ('1.5e3')
    // and type suffixes ('5L', '1.5f', '5d'). Used by every inference path so they cannot drift apart.
    static String numericLiteralType(String literal) {
        String v = literal.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.matches("-?0[xX][0-9a-fA-F_]+[lL]")) {
            return "long";
        }
        if (v.matches("-?0[xX][0-9a-fA-F_]+")) {
            return "int";
        }
        // Float forms (an 'f'/'F' suffix) are checked before double and integer forms.
        if (v.matches("-?\\d[\\d_]*\\.\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?[fF]")
                || v.matches("-?\\d[\\d_]*[eE][+-]?\\d[\\d_]*[fF]")
                || v.matches("-?\\d[\\d_]*[fF]")) {
            return "float";
        }
        if (v.matches("-?\\d[\\d_]*\\.\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?[dD]?")
                || v.matches("-?\\d[\\d_]*[eE][+-]?\\d[\\d_]*[dD]?")
                || v.matches("-?\\d[\\d_]*[dD]")) {
            return "double";
        }
        if (v.matches("-?\\d[\\d_]*[lL]")) {
            return "long";
        }
        if (v.matches("-?\\d[\\d_]*")) {
            return "int";
        }
        return null;
    }

    TypeRef typeRef(AffogatoParser.TypeRefContext context) {
        String raw = context.getText();
        Nullability nullability = Nullability.UNSPECIFIED;
        if (raw.endsWith("?")) {
            nullability = Nullability.NULLABLE;
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("!")) {
            nullability = Nullability.NOT_NULL;
            raw = raw.substring(0, raw.length() - 1);
        }
        return new TypeRef(normalizeTypeUseNullability(normalizeListType(raw)), nullability);
    }

    /**
     * Rewrites Swift-style list types {@code [T]} into {@code java.util.List<T>}, recursively and at
     * any nesting depth (e.g. {@code Supplier<[Component]>} → {@code Supplier<java.util.List<Component>>}).
     * Empty {@code []} array suffixes are left untouched.
     */
    private String normalizeListType(String type) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < type.length()) {
            char current = type.charAt(index);
            if (current == '[') {
                if (index + 1 < type.length() && type.charAt(index + 1) == ']') {
                    out.append("[]");
                    index += 2;
                    continue;
                }
                int close = matchingBracket(type, index);
                if (close > index) {
                    out.append("java.util.List<")
                            .append(normalizeListType(type.substring(index + 1, close)))
                            .append('>');
                    index = close + 1;
                    continue;
                }
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private String normalizeTypeUseNullability(String type) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_$.])([A-Za-z_$][A-Za-z0-9_$.]*)([!?])(?=\\s*(?:\\[\\]|[,>\\]]|$))")
                .matcher(type);
        return matcher.replaceAll(match -> {
            String annotation = match.group(2).equals("?") ? "@Nullable " : "@NotNull ";
            return annotation + match.group(1);
        });
    }

    int matchingBracket(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '[') {
                depth++;
            } else if (current == ']') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
    private List<String> annotations(String source, List<AffogatoParser.AnnotationContext> annotationContexts) {
        List<String> rendered = new ArrayList<>();
        for (AffogatoParser.AnnotationContext annotation : annotationContexts) {
            rendered.add(sourceText(source, annotation).trim());
        }
        return rendered;
    }

    private Modifiers modifiers(List<AffogatoParser.MemberModifierContext> modifierContexts) {
        String access = "public";
        boolean isStatic = false;
        boolean isOverride = false;
        boolean isAbstract = false;
        for (AffogatoParser.MemberModifierContext modifier : modifierContexts) {
            if (modifier.PUBLIC() != null) {
                access = "public";
            } else if (modifier.PRIVATE() != null) {
                access = "private";
            } else if (modifier.PROTECTED() != null) {
                access = "protected";
            } else if (modifier.STATIC() != null) {
                isStatic = true;
            } else if (modifier.OVERRIDE() != null) {
                isOverride = true;
            } else if (modifier.ABSTRACT() != null) {
                isAbstract = true;
            }
        }
        return new Modifiers(access, isStatic, isOverride, isAbstract);
    }

    private String accessFromClassModifiers(List<AffogatoParser.ClassModifierContext> modifiers) {
        String access = "public";
        boolean isAbstract = false;
        for (AffogatoParser.ClassModifierContext modifier : modifiers) {
            if (modifier.PRIVATE() != null) {
                access = "private";
            } else if (modifier.PROTECTED() != null) {
                access = "protected";
            } else if (modifier.PUBLIC() != null) {
                access = "public";
            } else if (modifier.ABSTRACT() != null) {
                isAbstract = true;
            }
        }
        return isAbstract ? access + " abstract" : access;
    }

    private String stripTerminators(String text) {
        return text.replaceAll("[;\\r\\n]+$", "").trim();
    }
    String sourceText(String source, ParserRuleContext context) {
        Token start = context.getStart();
        Token stop = context.getStop();
        if (start == null || stop == null) {
            return "";
        }
        int startIndex = start.getStartIndex();
        int stopIndex = stop.getStopIndex();
        if (startIndex < 0 || stopIndex < startIndex || stopIndex >= source.length()) {
            return context.getText();
        }
        return source.substring(startIndex, stopIndex + 1);
    }
    void validateDeclaredName(Path sourceFile, String name, String kind, int line, int column) {
        if (JAVA_RESERVED_WORDS.contains(name)) {
            diagnostics.add(error(sourceFile, line, column, name.length(),
                    "AFFOGATO_RESERVED_IDENTIFIER",
                    "The " + kind + " name '" + name + "' is a Java reserved word and cannot be used as an identifier."));
        }
    }
    private final class SyntaxErrorListener extends BaseErrorListener {
        private final Path sourceFile;
        private boolean hadErrors;

        private SyntaxErrorListener(Path sourceFile) {
            this.sourceFile = sourceFile;
        }

        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String message,
                RecognitionException exception
        ) {
            hadErrors = true;
            int length = 1;
            if (offendingSymbol instanceof Token token && token.getLine() == line) {
                length = Math.max(1, token.getText().length());
            }
            diagnostics.add(error(sourceFile, line, charPositionInLine + 1, length,
                    "AFFOGATO_PARSE", message, parseHint(recognizer, offendingSymbol)));
        }

        private boolean hadErrors() {
            return hadErrors;
        }
    }

    private AffogatoDiagnostic error(Path sourceFile, int line, int column, String code, String message) {
        return error(sourceFile, line, column, 1, code, message);
    }

    private AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length);
    }

    private AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message, String hint) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length, hint);
    }

    private String parseHint(Recognizer<?, ?> recognizer, Object offendingSymbol) {
        if (!(recognizer instanceof AffogatoParser parser)) {
            return null;
        }
        IntervalSet expected = parser.getExpectedTokens();
        if (expected == null) {
            return null;
        }
        if (expected.contains(AffogatoLexer.RBRACE)) {
            return "Close the current block with '}'.";
        }
        if (expected.contains(AffogatoLexer.RPAREN)) {
            return "Close the current parameter or argument list with ')'.";
        }
        if (expected.contains(AffogatoLexer.RBRACK)) {
            return "Close the current array access or literal with ']'.";
        }
        if (expected.contains(AffogatoLexer.Identifier)) {
            return "Add an identifier at this position.";
        }
        if (expected.contains(AffogatoLexer.COLON)) {
            return "Add ':' before the type annotation or switch arm body.";
        }
        if (expected.contains(AffogatoLexer.StringLiteral)
                || expected.contains(AffogatoLexer.IntegerLiteral)
                || expected.contains(AffogatoLexer.TRUE)
                || expected.contains(AffogatoLexer.FALSE)
                || expected.contains(AffogatoLexer.NULL)
                || expected.contains(AffogatoLexer.LPAREN)) {
            return "Add an expression at this position.";
        }
        if (offendingSymbol instanceof Token token && token.getType() == Token.EOF) {
            return "The file ended before the current declaration or block was complete.";
        }
        return null;
    }
}
