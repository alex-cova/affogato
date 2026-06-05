package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.SourceLocations;
import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import static dev.affogato.compiler.internal.TranspilerTypes.*;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AffogatoTranspiler implements AutoCloseable {
    private static final Pattern LOCAL_DECLARATION = Pattern.compile(
            "^(var|let)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([^=]+?))?\\s*(?:=\\s*(.+))?$"
    );
    private static final Pattern PROPERTY_ASSIGNMENT = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$"
    );
    private static final Pattern PROPERTY_COMPOUND_ASSIGNMENT = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*([-+*/%])=\\s*(.+)$"
    );
    private static final Pattern PROPERTY_POSTFIX_INCDEC = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*(\\+\\+|--)$"
    );
    private static final Pattern PROPERTY_PREFIX_INCDEC = Pattern.compile(
            "^(\\+\\+|--)\\s*([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)$"
    );
    private static final Pattern VARIABLE_ASSIGNMENT = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$"
    );
    private static final Pattern INSTANCEOF_ALIAS = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_.$]*)\\s+is\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?)"
    );
    private static final Pattern AS_CAST = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_.$]*)\\s+as\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)"
    );
    private static final Pattern SIMPLE_TYPED_LAMBDA = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([^\\-]+?)\\s*->\\s*(.+)$"
    );

    // Java reserved words and reserved literals that are valid Affogato identifiers (the lexer only
    // reserves Affogato keywords) but cannot be emitted as a Java declaration name. Many overlap with
    // Affogato keywords and never reach an Identifier token; they are listed anyway for completeness.
    private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "_");

    private final List<AffogatoDiagnostic> diagnostics;
    private final JavaResolver javaResolver;
    private final FlowAnalyzer flow;
    private final ClassSymbolTable classSymbols = new ClassSymbolTable();
    private final Map<String, List<ExtensionSymbol>> extensionSymbols = new LinkedHashMap<>();
    private Set<String> activeTypeParams = new HashSet<>();
    // When transforming an array-literal initializer whose binding has an explicit single-dimension array
    // type (`let xs: Person[] = [...]`), this holds that element type so the emitted `new T[]{...}` matches
    // the declared type instead of a too-wide `new Object[]`. Null when there is no explicit array target.
    private String expectedArrayElementType = null;

    public AffogatoTranspiler(List<AffogatoDiagnostic> diagnostics, List<Path> classpath) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.javaResolver = new JavaResolver(classpath);
        this.flow = new FlowAnalyzer(diagnostics);
    }

    @Override
    public void close() {
        javaResolver.close();
    }

    public ParsedUnit parse(Path sourceFile, String source) {
        scanUnsupportedSourceEdges(sourceFile, source);
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
            return ParsedUnit.empty(sourceFile, source);
        }

        validateNumericLiterals(sourceFile, tokens);

        if (syntaxErrors.hadErrors()) {
            return ParsedUnit.empty(sourceFile, source);
        }

        try {
            CompilationUnit unit = buildCompilationUnit(sourceFile, source, tree);
            return new ParsedUnit(sourceFile, unit);
        } catch (StackOverflowError overflow) {
            diagnostics.add(error(sourceFile, 1, 1, "AFFOGATO_PARSE",
                    "Source is too deeply nested to compile; reduce expression or block nesting depth."));
            return ParsedUnit.empty(sourceFile, source);
        }
    }

    private void scanUnsupportedSourceEdges(Path sourceFile, String source) {
        scanUnsupportedToken(sourceFile, source, "?.", "AFFOGATO_UNSUPPORTED_SAFE_CALL", "Safe-call expressions are not in the production subset; use an explicit null check.");
        scanUnsupportedToken(sourceFile, source, "?:", "AFFOGATO_UNSUPPORTED_ELVIS", "Elvis expressions are not in the production subset; use a ternary expression.");
        scanUnsupportedToken(sourceFile, source, "!!", "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION", "Not-null assertion expressions are not in the production subset; use an explicit cast or null check.");
    }

    private void scanUnsupportedToken(Path sourceFile, String source, String token, String code, String message) {
        int index = 0;
        int length = source.length();
        while (index < length) {
            char c = source.charAt(index);
            // Skip string literals — handle \\ so "test\\" is not mistakenly left open.
            if (c == '"') {
                index++;
                while (index < length) {
                    char d = source.charAt(index);
                    if (d == '\\') {
                        index += 2;
                        continue;
                    }
                    if (d == '"') {
                        index++;
                        break;
                    }
                    index++;
                }
                continue;
            }
            // Skip line comments.
            if (c == '/' && index + 1 < length && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < length && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            // Skip block comments.
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
            if (source.startsWith(token, index)) {
                SourceLocation location = sourceLocation(source, index);
                diagnostics.add(error(sourceFile, location.line(), location.column(), token.length(), code, message));
                index += token.length();
                continue;
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
            } else if (token.getType() == AffogatoLexer.STR_ESCAPE) {
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
    private void validateStringEscapes(Path sourceFile, Token token) {
        String text = token.getText();
        if (!text.startsWith("\\u")) {
            return;
        }
        int line = token.getLine();
        int baseColumn = token.getCharPositionInLine() + 1;
        int cursor = 1;
        while (cursor < text.length() && text.charAt(cursor) == 'u') {
            cursor++;
        }
        if (cursor + 4 <= text.length()) {
            String hex = text.substring(cursor, cursor + 4);
            try {
                int codePoint = Integer.parseInt(hex, 16);
                if (codePoint == '"' || codePoint == '\\' || codePoint == '\n' || codePoint == '\r') {
                    diagnostics.add(error(sourceFile, line, baseColumn, text.length(),
                            "AFFOGATO_PARSE",
                            "Unicode escape '" + text.substring(0, cursor + 4) + "' decodes to a character "
                                    + "that is invalid in a string literal; use the direct escape "
                                    + "(\\\", \\\\, \\n, or \\r) instead."));
                }
            } catch (NumberFormatException ignored) {
                // Malformed \\u escapes are a lexer error already; nothing to add here.
            }
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

    public void registerSymbols(ParsedUnit parsedUnit) {
        CompilationUnit unit = parsedUnit.unit();
        for (ParsedClass clazz : unit.classes()) {
            registerClassSymbol(unit, clazz.name(), clazz.declarationLine(), clazz.declarationColumn(), () -> {
                String extendsType = clazz.superTypes().isEmpty() ? "" : clazz.superTypes().get(0);
                ClassSymbol symbol = new ClassSymbol(unit.packageName(), clazz.name(), extendsType, false,
                        clazz.typeParameters().stream().map(TypeParamDecl::name).toList());
                for (FieldDecl field : clazz.fields()) {
                    symbol.fields.put(field.name(), new FieldSymbol(field.name(), field.type(), field.mutable()));
                }
                for (MethodDecl method : clazz.methods()) {
                    symbol.methods.computeIfAbsent(method.name(), ignored -> new ArrayList<>())
                            .add(new MethodSymbol(method.name(), method.returnType(), method.parameters(), method.isStatic()));
                }
                for (ConstructorDecl constructor : clazz.constructors()) {
                    symbol.constructors.add(new ConstructorSymbol(constructor.parameters()));
                }
                if (!clazz.compactParameters().isEmpty()) {
                    symbol.constructors.add(new ConstructorSymbol(clazz.compactParameters()));
                }
                if (symbol.constructors.isEmpty()) {
                    symbol.constructors.add(new ConstructorSymbol(List.of()));
                }
                return symbol;
            });
        }
        for (ParsedEnum parsedEnum : unit.enums()) {
            registerClassSymbol(unit, parsedEnum.name(), parsedEnum.declarationLine(), parsedEnum.declarationColumn(), () -> {
                ClassSymbol symbol = new ClassSymbol(unit.packageName(), parsedEnum.name(), "", false, List.of());
                symbol.constructors.add(new ConstructorSymbol(List.of()));
                return symbol;
            });
        }
        for (ParsedInterface parsedInterface : unit.interfaces()) {
            registerClassSymbol(unit, parsedInterface.name(), parsedInterface.declarationLine(), parsedInterface.declarationColumn(), () -> {
                ClassSymbol symbol = new ClassSymbol(unit.packageName(), parsedInterface.name(), "", true,
                        parsedInterface.typeParameters().stream().map(TypeParamDecl::name).toList());
                for (InterfaceMethod method : parsedInterface.methods()) {
                    symbol.methods.computeIfAbsent(method.name(), ignored -> new ArrayList<>())
                            .add(new MethodSymbol(method.name(), method.returnType(), method.parameters(), false));
                }
                return symbol;
            });
        }
        for (ParsedRecord parsedRecord : unit.records()) {
            registerClassSymbol(unit, parsedRecord.name(), parsedRecord.declarationLine(), parsedRecord.declarationColumn(), () -> {
                ClassSymbol symbol = new ClassSymbol(unit.packageName(), parsedRecord.name(), "", false,
                        parsedRecord.typeParameters().stream().map(TypeParamDecl::name).toList());
                symbol.isRecord = true;
                for (ParamDecl component : parsedRecord.components()) {
                    symbol.fields.put(component.name(), new FieldSymbol(component.name(), component.type(), false));
                }
                for (MethodDecl method : parsedRecord.methods()) {
                    symbol.methods.computeIfAbsent(method.name(), ignored -> new ArrayList<>())
                            .add(new MethodSymbol(method.name(), method.returnType(), method.parameters(), method.isStatic()));
                }
                symbol.constructors.add(new ConstructorSymbol(parsedRecord.components()));
                return symbol;
            });
        }
        if (!unit.extensions().isEmpty()) {
            String holderSimpleName = extensionsHolderName(unit);
            ClassSymbol holderSymbol = new ClassSymbol(unit.packageName(), holderSimpleName, "", false, List.of());
            for (ExtensionFuncDecl extension : unit.extensions()) {
                String receiverKey = simpleTypeName(extension.receiverType().javaType());
                extensionSymbols.computeIfAbsent(receiverKey, ignored -> new ArrayList<>())
                        .add(new ExtensionSymbol(
                                unit.packageName(),
                                holderSimpleName,
                                extension.name(),
                                extension.receiverType(),
                                extension.returnType(),
                                extension.parameters()
                        ));
                List<ParamDecl> staticParameters = new ArrayList<>();
                staticParameters.add(new ParamDecl("$this", extension.receiverType(), PropertyKind.NONE, List.of()));
                staticParameters.addAll(extension.parameters());
                holderSymbol.methods.computeIfAbsent(extension.name(), ignored -> new ArrayList<>())
                        .add(new MethodSymbol(extension.name(), extension.returnType(), staticParameters, true));
            }
            classSymbols.register(unit.packageName(), holderSymbol);
        }
    }

    private void registerClassSymbol(
            CompilationUnit unit,
            String simpleName,
            int line,
            int column,
            java.util.function.Supplier<ClassSymbol> builder
    ) {
        String fqn = unit.packageName().isBlank() ? simpleName : unit.packageName() + "." + simpleName;
        if (classSymbols.containsFqn(fqn)) {
            int nameColumn = SourceLocations.columnOfIdentifier(unit.source(), line, simpleName, column);
            diagnostics.add(error(
                    unit.sourceFile(), line, nameColumn, simpleName.length(),
                    "AFFOGATO_DUPLICATE_CLASS",
                    "Duplicate type name '" + fqn + "' — a class, record, interface, or enum with this fully-qualified name is already defined."
            ));
            return;
        }
        classSymbols.register(unit.packageName(), builder.get());
    }

    public List<GeneratedJava> generate(ParsedUnit parsedUnit) {
        List<GeneratedJava> generatedFiles = new ArrayList<>();
        CompilationUnit unit = parsedUnit.unit();
        for (ParsedClass clazz : unit.classes()) {
            generatedFiles.add(generateClass(unit, clazz));
        }
        for (ParsedEnum parsedEnum : unit.enums()) {
            generatedFiles.add(generateEnum(unit, parsedEnum));
        }
        for (ParsedInterface parsedInterface : unit.interfaces()) {
            generatedFiles.add(generateInterface(unit, parsedInterface));
        }
        for (ParsedRecord parsedRecord : unit.records()) {
            generatedFiles.add(generateRecord(unit, parsedRecord));
        }
        if (!unit.extensions().isEmpty()) {
            generatedFiles.add(generateExtensionsHolder(unit));
        }
        return generatedFiles;
    }

    private CompilationUnit buildCompilationUnit(Path sourceFile, String source, AffogatoParser.CompilationUnitContext tree) {
        String packageName = tree.packageDecl() == null ? "" : tree.packageDecl().qualifiedName().getText();
        if (tree.packageDecl() != null) {
            validatePackageName(sourceFile, tree.packageDecl(), packageName);
        }
        List<String> imports = new ArrayList<>();
        Map<String, String> importedSimpleNames = new LinkedHashMap<>();
        for (AffogatoParser.ImportDeclContext importDecl : tree.importDecl()) {
            String importName = sourceText(source, importDecl)
                    .replaceFirst("^import\\s+", "")
                    .trim();
            String cleanedImport = stripTerminators(importName);
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

    private static String extensionsHolderName(CompilationUnit unit) {
        String fileName = unit.sourceFile().getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return base + "Extensions";
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
        for (AffogatoParser.ClassMemberContext member : classDecl.classBody().classMember()) {
            if (member.fieldDecl() != null) {
                fields.add(buildField(sourceFile, source, member.fieldDecl()));
            } else if (member.constructorDecl() != null) {
                constructors.add(buildConstructor(sourceFile, source, member.constructorDecl()));
            } else if (member.methodDecl() != null) {
                methods.add(buildMethod(sourceFile, source, member.methodDecl()));
            }
        }

        return new ParsedClass(access, name, buildTypeParams(classDecl.typeParamList()), superTypes, compactParameters, fields, constructors, methods, annotations(source, classDecl.annotation()), classDecl.getStart().getLine(), classDecl.getStart().getCharPositionInLine() + 1);
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
            returnType = TypeRef.unspecified("void");
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
        for (AffogatoParser.ClassMemberContext member : recordDecl.classBody().classMember()) {
            if (member.methodDecl() != null) {
                methods.add(buildMethod(sourceFile, source, member.methodDecl()));
            } else if (member.fieldDecl() != null || member.constructorDecl() != null) {
                diagnostics.add(error(
                        sourceFile,
                        member.getStart().getLine(),
                        member.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_RECORD_MEMBER",
                        "Records may only declare methods in their body; state comes from the header components."
                ));
            }
        }
        return new ParsedRecord(access, name, buildTypeParams(recordDecl.typeParamList()), components, superTypes, methods, annotations(source, recordDecl.annotation()), recordDecl.getStart().getLine(), recordDecl.getStart().getCharPositionInLine() + 1);
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
                returnType = TypeRef.unspecified("void");
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

    private GeneratedJava generateClass(CompilationUnit unit, ParsedClass clazz) {
        validateMainSignature(unit.sourceFile(), clazz);
        StringBuilder out = new StringBuilder();
        if (!unit.packageName().isBlank()) {
            out.append("package ").append(unit.packageName()).append(";").append(System.lineSeparator()).append(System.lineSeparator());
        }

        Set<String> imports = new LinkedHashSet<>(unit.imports());
        if (usesNullable(clazz)) {
            imports.add("dev.affogato.runtime.Nullable");
        }
        if (usesNotNull(clazz)) {
            imports.add("dev.affogato.runtime.NotNull");
        }
        if (usesObjects(clazz)) {
            imports.add("java.util.Objects");
        }
        for (String importName : imports) {
            out.append("import ").append(importName).append(";").append(System.lineSeparator());
        }
        if (!imports.isEmpty()) {
            out.append(System.lineSeparator());
        }

        writeAnnotations(out, clazz.annotations(), 0);
        out.append(clazz.access()).append(" class ").append(clazz.name());
        if (!clazz.typeParameters().isEmpty()) {
            out.append('<').append(clazz.typeParameters().stream()
                    .map(TypeParamDecl::declaration)
                    .collect(java.util.stream.Collectors.joining(", "))).append('>');
        }
        String extendsType = "";
        List<String> implementsTypes = new ArrayList<>();
        for (String superType : clazz.superTypes()) {
            if (isInterfaceType(superType, unit)) {
                implementsTypes.add(superType);
            } else if (extendsType.isBlank()) {
                extendsType = superType;
            }
        }
        if (!extendsType.isBlank()) {
            out.append(" extends ").append(extendsType);
        }
        if (!implementsTypes.isEmpty()) {
            out.append(" implements ").append(String.join(", ", implementsTypes));
        }
        out.append(" {").append(System.lineSeparator());

        Set<String> prevTypeParams = activeTypeParams;
        activeTypeParams = new HashSet<>(prevTypeParams);
        clazz.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));
        writeFields(out, unit, clazz);
        writeCompactConstructor(out, unit, clazz);
        writeConstructors(out, unit, clazz);
        writeAccessors(out, clazz);
        writeMethods(out, unit, clazz);
        activeTypeParams = prevTypeParams;

        out.append("}").append(System.lineSeparator());
        return new GeneratedJava(unit.packageName(), clazz.name(), out.toString());
    }

    private GeneratedJava generateExtensionsHolder(CompilationUnit unit) {
        String holderName = extensionsHolderName(unit);

        // Synthetic class shape (extensions rendered as static methods with the receiver as the first
        // parameter) so the existing import/null-check helpers can be reused unchanged.
        List<MethodDecl> shapeMethods = new ArrayList<>();
        for (ExtensionFuncDecl extension : unit.extensions()) {
            shapeMethods.add(new MethodDecl("public", true, false, false, List.of(), extension.returnType(), extension.name(),
                    holderParameters(extension), extension.body(), extension.line(), extension.annotations()));
        }
        ParsedClass shape = new ParsedClass("public", holderName, List.of(), List.of(), List.of(), List.of(), List.of(), shapeMethods, List.of(), 1, 1);

        StringBuilder out = new StringBuilder();
        if (!unit.packageName().isBlank()) {
            out.append("package ").append(unit.packageName()).append(";").append(System.lineSeparator()).append(System.lineSeparator());
        }

        Set<String> imports = new LinkedHashSet<>(unit.imports());
        if (usesNullable(shape)) {
            imports.add("dev.affogato.runtime.Nullable");
        }
        if (usesNotNull(shape)) {
            imports.add("dev.affogato.runtime.NotNull");
        }
        if (usesObjects(shape)) {
            imports.add("java.util.Objects");
        }
        for (String importName : imports) {
            out.append("import ").append(importName).append(";").append(System.lineSeparator());
        }
        if (!imports.isEmpty()) {
            out.append(System.lineSeparator());
        }

        out.append("public final class ").append(holderName).append(" {").append(System.lineSeparator());

        for (ExtensionFuncDecl extension : unit.extensions()) {
            List<ParamDecl> parameters = holderParameters(extension);
            MethodContext context = MethodContext.forExecutable(unit, shape, extension.name(), extension.returnType(), classSymbols, extensionSymbols, javaResolver);
            context.receiverType = extension.receiverType().javaType();
            for (ParamDecl parameter : parameters) {
                context.declareVariable(parameter.name(), parameter.type(), true);
            }

            writeAnnotations(out, extension.annotations(), 1);
            out.append("    public static ")
                    .append(extension.returnType().declaration())
                    .append(' ')
                    .append(extension.name())
                    .append('(')
                    .append(parameterList(parameters))
                    .append(") {")
                    .append(System.lineSeparator());
            for (ParamDecl parameter : parameters) {
                writeNullCheck(out, parameter.name(), parameter.type(), 2);
            }
            if (!extension.returnType().javaType().equals("void") && !blockExits(extension.body())) {
                diagnostics.add(error(
                        unit.sourceFile(),
                        extension.line(),
                        1,
                        "AFFOGATO_RETURN_FLOW",
                        "Extension function " + extension.name() + " must exit with a value on all paths."
                ));
            }
            writeBlockStatements(out, unit, extension.body(), context, 2);
            out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
        }

        out.append("}").append(System.lineSeparator());
        return new GeneratedJava(unit.packageName(), holderName, out.toString());
    }

    private List<ParamDecl> holderParameters(ExtensionFuncDecl extension) {
        List<ParamDecl> parameters = new ArrayList<>();
        parameters.add(new ParamDecl("$this", extension.receiverType(), PropertyKind.NONE, List.of()));
        parameters.addAll(extension.parameters());
        return parameters;
    }

    private GeneratedJava generateEnum(CompilationUnit unit, ParsedEnum parsedEnum) {
        StringBuilder out = new StringBuilder();
        if (!unit.packageName().isBlank()) {
            out.append("package ").append(unit.packageName()).append(";").append(System.lineSeparator()).append(System.lineSeparator());
        }
        Set<String> imports = new LinkedHashSet<>(unit.imports());
        for (String importName : imports) {
            out.append("import ").append(importName).append(";").append(System.lineSeparator());
        }
        if (!imports.isEmpty()) {
            out.append(System.lineSeparator());
        }
        writeAnnotations(out, parsedEnum.annotations(), 0);
        out.append(parsedEnum.access()).append(" enum ").append(parsedEnum.name()).append(" {").append(System.lineSeparator());
        out.append("    ").append(String.join(", ", parsedEnum.constants())).append(System.lineSeparator());
        out.append("}").append(System.lineSeparator());
        return new GeneratedJava(unit.packageName(), parsedEnum.name(), out.toString());
    }

    private GeneratedJava generateInterface(CompilationUnit unit, ParsedInterface parsedInterface) {
        StringBuilder out = new StringBuilder();
        if (!unit.packageName().isBlank()) {
            out.append("package ").append(unit.packageName()).append(";").append(System.lineSeparator()).append(System.lineSeparator());
        }
        Set<String> imports = new LinkedHashSet<>(unit.imports());
        for (String importName : imports) {
            out.append("import ").append(importName).append(";").append(System.lineSeparator());
        }
        if (!imports.isEmpty()) {
            out.append(System.lineSeparator());
        }
        writeAnnotations(out, parsedInterface.annotations(), 0);
        out.append(parsedInterface.access()).append(" interface ").append(parsedInterface.name());
        if (!parsedInterface.typeParameters().isEmpty()) {
            out.append('<').append(parsedInterface.typeParameters().stream()
                    .map(TypeParamDecl::declaration)
                    .collect(java.util.stream.Collectors.joining(", "))).append('>');
        }
        out.append(" {").append(System.lineSeparator());

        Set<String> prevTypeParams = activeTypeParams;
        activeTypeParams = new HashSet<>(prevTypeParams);
        parsedInterface.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));
        ParsedClass dummyClass = new ParsedClass(parsedInterface.access(), parsedInterface.name(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 1, 1);
        for (InterfaceMethod method : parsedInterface.methods()) {
            if (method.isDefault()) {
                out.append("    default ").append(method.returnType().declaration()).append(' ')
                        .append(method.name()).append('(').append(parameterList(method.parameters())).append(") {")
                        .append(System.lineSeparator());
                MethodContext context = MethodContext.forExecutable(unit, dummyClass, method.name(), method.returnType(), classSymbols, extensionSymbols, javaResolver);
                for (ParamDecl param : method.parameters()) {
                    context.declareVariable(param.name(), param.type(), true);
                }
                writeBlockStatements(out, unit, method.body(), context, 2);
                out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
            } else {
                out.append("    ").append(method.returnType().declaration()).append(' ')
                        .append(method.name()).append('(').append(parameterList(method.parameters())).append(");")
                        .append(System.lineSeparator());
            }
        }
        activeTypeParams = prevTypeParams;
        out.append("}").append(System.lineSeparator());
        return new GeneratedJava(unit.packageName(), parsedInterface.name(), out.toString());
    }

    private GeneratedJava generateRecord(CompilationUnit unit, ParsedRecord parsedRecord) {
        ParsedClass shape = new ParsedClass(parsedRecord.access(), parsedRecord.name(), parsedRecord.typeParameters(),
                parsedRecord.superTypes(), parsedRecord.components(), List.of(), List.of(), parsedRecord.methods(), List.of(),
                parsedRecord.declarationLine(), parsedRecord.declarationColumn());

        StringBuilder out = new StringBuilder();
        if (!unit.packageName().isBlank()) {
            out.append("package ").append(unit.packageName()).append(";").append(System.lineSeparator()).append(System.lineSeparator());
        }

        Set<String> imports = new LinkedHashSet<>(unit.imports());
        if (usesNullable(shape)) {
            imports.add("dev.affogato.runtime.Nullable");
        }
        if (usesNotNull(shape)) {
            imports.add("dev.affogato.runtime.NotNull");
        }
        if (usesObjects(shape)) {
            imports.add("java.util.Objects");
        }
        for (String importName : imports) {
            out.append("import ").append(importName).append(";").append(System.lineSeparator());
        }
        if (!imports.isEmpty()) {
            out.append(System.lineSeparator());
        }

        writeAnnotations(out, parsedRecord.annotations(), 0);
        out.append(parsedRecord.access()).append(" record ").append(parsedRecord.name());
        if (!parsedRecord.typeParameters().isEmpty()) {
            out.append('<').append(parsedRecord.typeParameters().stream()
                    .map(TypeParamDecl::declaration)
                    .collect(java.util.stream.Collectors.joining(", "))).append('>');
        }
        out.append('(').append(parameterList(parsedRecord.components())).append(')');
        if (!parsedRecord.superTypes().isEmpty()) {
            out.append(" implements ").append(String.join(", ", parsedRecord.superTypes()));
        }
        out.append(" {").append(System.lineSeparator());

        Set<String> prevTypeParams = activeTypeParams;
        activeTypeParams = new HashSet<>(prevTypeParams);
        parsedRecord.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));

        // Emit a compact canonical constructor only to enforce non-null components.
        boolean needsNullChecks = parsedRecord.components().stream().anyMatch(c -> c.type().requiresRuntimeCheck());
        if (needsNullChecks) {
            out.append("    public ").append(parsedRecord.name()).append(" {").append(System.lineSeparator());
            for (ParamDecl component : parsedRecord.components()) {
                writeNullCheck(out, component.name(), component.type(), 2);
            }
            out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
        }

        writeMethods(out, unit, shape);
        activeTypeParams = prevTypeParams;

        out.append("}").append(System.lineSeparator());
        return new GeneratedJava(unit.packageName(), parsedRecord.name(), out.toString());
    }

    private boolean isInterfaceType(String typeName, CompilationUnit unit) {
        ClassSymbol symbol = classSymbol(typeName, unit);
        if (symbol != null) {
            return symbol.isInterface();
        }
        return javaResolver.isInterface(typeName, unit);
    }

    private void writeFields(StringBuilder out, CompilationUnit unit, ParsedClass clazz) {
        MethodContext context = MethodContext.empty(unit, clazz, classSymbols, extensionSymbols, javaResolver);
        for (FieldDecl field : clazz.fields()) {
            validateTypeRef(field.type(), unit, field.line(), 1);
            writeAnnotations(out, field.annotations(), 1);
            out.append("    ")
                    .append(field.access())
                    .append(field.isStatic() ? " static" : "")
                    .append(field.mutable() ? "" : " final")
                    .append(' ')
                    .append(field.type().declaration())
                    .append(' ')
                    .append(field.name());
            if (!field.initializer().isBlank()) {
                validateAssignment(field.type(), field.initializer(), context, field.line(), 1, "AFFOGATO_FIELD_TYPE", "Field initializer type is not assignable to " + field.type().javaType() + ".");
                out.append(" = ").append(transformExpression(field.initializer(), context));
            }
            out.append(";").append(System.lineSeparator());
        }
        if (!clazz.fields().isEmpty()) {
            out.append(System.lineSeparator());
        }
    }

    private void writeCompactConstructor(StringBuilder out, CompilationUnit unit, ParsedClass clazz) {
        if (clazz.compactParameters().isEmpty()) {
            return;
        }
        MethodContext context = MethodContext.forExecutable(unit, clazz, clazz.name(), TypeRef.unspecified("void"), classSymbols, extensionSymbols, javaResolver);
        for (ParamDecl parameter : clazz.compactParameters()) {
            validateTypeRef(parameter.type(), unit, 1, 1);
            context.declareVariable(parameter.name(), parameter.type(), true);
        }

        out.append("    public ").append(clazz.name()).append('(').append(parameterList(clazz.compactParameters())).append(") {")
                .append(System.lineSeparator());
        for (ParamDecl parameter : clazz.compactParameters()) {
            writeNullCheck(out, parameter.name(), parameter.type(), 2);
        }
        for (ParamDecl parameter : clazz.compactParameters()) {
            if (parameter.propertyKind() != PropertyKind.NONE) {
                out.append("        this.").append(parameter.name()).append(" = ").append(parameter.name()).append(";")
                        .append(System.lineSeparator());
            }
        }
        out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
    }

    private void writeConstructors(StringBuilder out, CompilationUnit unit, ParsedClass clazz) {
        for (ConstructorDecl constructor : clazz.constructors()) {
            MethodContext context = MethodContext.forExecutable(unit, clazz, clazz.name(), TypeRef.unspecified("void"), classSymbols, extensionSymbols, javaResolver);
            for (ParamDecl parameter : constructor.parameters()) {
                validateTypeRef(parameter.type(), unit, constructor.line(), 1);
                context.declareVariable(parameter.name(), parameter.type(), true);
            }
            writeAnnotations(out, constructor.annotations(), 1);
            out.append("    ")
                    .append(constructor.access())
                    .append(' ')
                    .append(clazz.name())
                    .append('(')
                    .append(parameterList(constructor.parameters()))
                    .append(") {")
                    .append(System.lineSeparator());
            for (ParamDecl parameter : constructor.parameters()) {
                writeNullCheck(out, parameter.name(), parameter.type(), 2);
            }
            writeBlockStatements(out, unit, constructor.body(), context, 2);
            out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
        }
    }

    private void writeAccessors(StringBuilder out, ParsedClass clazz) {
        for (FieldDecl field : clazz.fields()) {
            String getter = getterName(field.name(), field.type());
            out.append("    public ")
                    .append(field.type().declaration())
                    .append(' ')
                    .append(getter)
                    .append("() {")
                    .append(System.lineSeparator())
                    .append("        return ")
                    .append(field.name())
                    .append(";")
                    .append(System.lineSeparator())
                    .append("    }")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());

            if (field.mutable()) {
                out.append("    public void ")
                        .append(setterName(field.name()))
                        .append('(')
                        .append(field.type().declaration())
                        .append(" value) {")
                        .append(System.lineSeparator());
                writeNullCheck(out, "value", field.type(), 2);
                out.append("        this.")
                        .append(field.name())
                        .append(" = value;")
                        .append(System.lineSeparator())
                        .append("    }")
                        .append(System.lineSeparator())
                        .append(System.lineSeparator());
            }
        }
    }

    private void writeMethods(StringBuilder out, CompilationUnit unit, ParsedClass clazz) {
        for (MethodDecl method : clazz.methods()) {
            Set<String> prevTypeParams = activeTypeParams;
            if (!method.typeParameters().isEmpty()) {
                activeTypeParams = new HashSet<>(prevTypeParams);
                method.typeParameters().forEach(tp -> activeTypeParams.add(tp.name()));
            }

            MethodContext context = MethodContext.forExecutable(unit, clazz, method.name(), method.returnType(), classSymbols, extensionSymbols, javaResolver);
            validateTypeRef(method.returnType(), unit, method.line(), 1);
            for (ParamDecl parameter : method.parameters()) {
                validateTypeRef(parameter.type(), unit, method.line(), 1);
                context.declareVariable(parameter.name(), parameter.type(), true);
            }

            writeAnnotations(out, method.annotations(), 1);
            if (method.isOverride()) {
                out.append("    @Override").append(System.lineSeparator());
            }
            out.append("    ")
                    .append(method.access());
            if (method.isAbstract()) {
                out.append(" abstract");
            }
            out.append(method.isStatic() ? " static " : " ");
            if (!method.typeParameters().isEmpty()) {
                out.append('<').append(method.typeParameters().stream()
                        .map(TypeParamDecl::declaration)
                        .collect(java.util.stream.Collectors.joining(", "))).append("> ");
            }
            out.append(method.returnType().declaration())
                    .append(' ')
                    .append(method.name())
                    .append('(')
                    .append(parameterList(method.parameters()))
                    .append(')');
            if (method.isAbstract() || method.body() == null) {
                out.append(";").append(System.lineSeparator()).append(System.lineSeparator());
            } else {
                out.append(" {").append(System.lineSeparator());
                for (ParamDecl parameter : method.parameters()) {
                    writeNullCheck(out, parameter.name(), parameter.type(), 2);
                }
                if (!method.returnType().javaType().equals("void") && !blockExits(method.body())) {
                    diagnostics.add(error(
                            unit.sourceFile(),
                            method.line(),
                            1,
                            "AFFOGATO_RETURN_FLOW",
                            "Method " + method.name() + " must exit with a value on all paths."
                    ));
                }
                writeBlockStatements(out, unit, method.body(), context, 2);
                out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
            }

            activeTypeParams = prevTypeParams;
        }
    }

    private void writeBlock(StringBuilder out, CompilationUnit unit, AffogatoParser.BlockContext block, MethodContext context, int indent) {
        out.append(indent(indent)).append("{").append(System.lineSeparator());
        MethodContext.ScopeSnapshot scope = context.snapshotScope();
        writeBlockStatements(out, unit, block, context, indent + 1);
        context.restoreScope(scope);
        out.append(indent(indent)).append("}").append(System.lineSeparator());
    }

    private void writeBlockStatements(StringBuilder out, CompilationUnit unit, AffogatoParser.BlockContext block, MethodContext context, int indent) {
        flow.checkUnreachable(unit.sourceFile(), block);
        context.pushBlockScope();
        try {
            List<AffogatoParser.StatementContext> statements = block.statement();
            for (int index = 0; index < statements.size(); index++) {
                Set<String> declaredLater = new LinkedHashSet<>();
                for (int later = index + 1; later < statements.size(); later++) {
                    declaredLater.addAll(localNamesDeclaredInStatement(statements.get(later)));
                }
                context.setLocalsDeclaredLaterInBlock(declaredLater);
                writeStatement(out, unit, statements.get(index), context, indent);
            }
            context.setLocalsDeclaredLaterInBlock(Set.of());
        } finally {
            context.popBlockScope();
        }
    }

    private Set<String> localNamesDeclaredInStatement(AffogatoParser.StatementContext statement) {
        if (statement.localVarDecl() != null) {
            return Set.of(statement.localVarDecl().Identifier().getText());
        }
        return Set.of();
    }

    private void writeStatement(StringBuilder out, CompilationUnit unit, AffogatoParser.StatementContext statement, MethodContext context, int indent) {
        context.currentLine = statement.getStart().getLine();
        context.currentColumn = statement.getStart().getCharPositionInLine() + 1;
        if (statement.block() != null) {
            writeBlock(out, unit, statement.block(), context, indent);
            return;
        }
        if (statement.guardStatement() != null) {
            writeGuard(out, unit, statement.guardStatement(), context, indent);
            return;
        }
        if (statement.ifStatement() != null) {
            writeIf(out, unit, statement.ifStatement(), context, indent);
            return;
        }
        if (statement.forStatement() != null) {
            writeFor(out, unit, statement.forStatement(), context, indent);
            return;
        }
        if (statement.whileStatement() != null) {
            writeWhile(out, unit, statement.whileStatement(), context, indent);
            return;
        }
        if (statement.tryStatement() != null) {
            writeTry(out, unit, statement.tryStatement(), context, indent);
            return;
        }
        if (statement.switchStatement() != null) {
            writeSwitch(out, unit, statement.switchStatement(), context, indent);
            return;
        }
        if (statement.returnStatement() != null) {
            if (statement.returnStatement().switchExpression() != null) {
                TypedExpression switchExpr = buildSwitchExpression(unit, statement.returnStatement().switchExpression(), context, indent,
                        context.returnType, "AFFOGATO_RETURN_TYPE", "Returned value is not assignable to " + context.returnType.javaType() + ".");
                out.append(indent(indent)).append("return ").append(switchExpr.javaSource()).append(";").append(System.lineSeparator());
                return;
            }
            String rawExpression = statement.returnStatement().expression() == null
                    ? ""
                    : mergeTrailingClosure(sourceText(unit.source(), statement.returnStatement().expression()),
                            unit.source(), statement.returnStatement().trailingClosure(), context);
            validateReturn(rawExpression, context, statement.returnStatement().getStart().getLine(), statement.returnStatement().getStart().getCharPositionInLine() + 1);
            if (rawExpression.isBlank()) {
                out.append(indent(indent)).append("return;").append(System.lineSeparator());
            } else {
                TypedExpression typedReturn = transformExpressionTyped(
                        rawExpression,
                        context,
                        statement.returnStatement().expression());
                out.append(indent(indent)).append("return ").append(typedReturn.javaSource()).append(";").append(System.lineSeparator());
            }
            return;
        }
        if (statement.throwStatement() != null) {
            TypedExpression expression = transformExpressionTyped(
                    sourceText(unit.source(), statement.throwStatement().expression()),
                    context,
                    statement.throwStatement().expression());
            validateThrowExpression(expression, context, statement.throwStatement().getStart().getLine(), statement.throwStatement().getStart().getCharPositionInLine() + 1);
            out.append(indent(indent)).append("throw ").append(expression.javaSource()).append(";").append(System.lineSeparator());
            return;
        }
        if (statement.assertStatement() != null) {
            AffogatoParser.AssertStatementContext assertCtx = statement.assertStatement();
            TypedExpression condition = transformExpressionTyped(
                    sourceText(unit.source(), assertCtx.expression(0)),
                    context,
                    assertCtx.expression(0));
            out.append(indent(indent)).append("assert ").append(condition.javaSource());
            if (assertCtx.expression().size() > 1) {
                TypedExpression message = transformExpressionTyped(
                        sourceText(unit.source(), assertCtx.expression(1)),
                        context,
                        assertCtx.expression(1));
                out.append(" : ").append(message.javaSource());
            }
            out.append(";").append(System.lineSeparator());
            return;
        }
        if (statement.breakStatement() != null) {
            out.append(indent(indent)).append("break;").append(System.lineSeparator());
            return;
        }
        if (statement.continueStatement() != null) {
            out.append(indent(indent)).append("continue;").append(System.lineSeparator());
            return;
        }
        if (statement.localVarDecl() != null) {
            out.append(indent(indent)).append(transformLocalDeclaration(unit, statement.localVarDecl(), context, indent)).append(System.lineSeparator());
            return;
        }
        if (statement.expressionStatement() != null) {
            String expression = mergeTrailingClosure(
                    sourceText(unit.source(), statement.expressionStatement().expression()),
                    unit.source(), statement.expressionStatement().trailingClosure(), context).trim();
            Matcher assignmentMatcher = PROPERTY_ASSIGNMENT.matcher(expression);
            String transformed = assignmentMatcher.matches()
                    ? transformPropertyAssignment(assignmentMatcher, context)
                    : null;
            if (transformed == null) {
                Matcher compoundMatcher = PROPERTY_COMPOUND_ASSIGNMENT.matcher(expression);
                if (compoundMatcher.matches()) {
                    transformed = transformPropertyCompoundAssignment(compoundMatcher, context);
                }
            }
            if (transformed == null) {
                Matcher postfix = PROPERTY_POSTFIX_INCDEC.matcher(expression);
                Matcher prefix = PROPERTY_PREFIX_INCDEC.matcher(expression);
                if (postfix.matches()) {
                    transformed = transformPropertyIncDec(postfix.group(1), postfix.group(2), postfix.group(3).equals("++"), context);
                } else if (prefix.matches()) {
                    transformed = transformPropertyIncDec(prefix.group(2), prefix.group(3), prefix.group(1).equals("++"), context);
                }
            }
            if (transformed == null) {
                // The single-identifier handlers above missed: try a write whose target is a property on a
                // complex receiver (`a.b.c = x`, `a.b.z += 1`, `a.b.z++`, `make().z = 5`).
                transformed = transformComplexPropertyWrite(expression, context);
            }
            if (transformed == null) {
                Matcher variableAssignment = VARIABLE_ASSIGNMENT.matcher(expression);
                if (variableAssignment.matches()) {
                    validateVariableAssignment(variableAssignment, context, statement.getStart().getLine(), statement.getStart().getCharPositionInLine() + 1);
                }
                transformed = transformExpression(expression, context);
                if (!transformed.endsWith(";")) {
                    transformed += ";";
                }
            }
            out.append(indent(indent)).append(transformed).append(System.lineSeparator());
        }
    }

    private void writeGuard(StringBuilder out, CompilationUnit unit, AffogatoParser.GuardStatementContext guard, MethodContext context, int indent) {
        if (!flow.blockStopsControl(guard.block())) {
            diagnostics.add(error(
                    unit.sourceFile(),
                    guard.getStart().getLine(),
                    guard.getStart().getCharPositionInLine() + 1,
                    "AFFOGATO_GUARD_FLOW",
                    "guard else blocks must exit with return, throw, break, or continue."
                ));
        }
        String rawCondition = sourceText(unit.source(), guard.condition());
        validateCondition(rawCondition, context, guard.getStart().getLine(), guard.getStart().getCharPositionInLine() + 1);
        String condition = transformExpressionTyped(rawCondition, context, guard.condition()).javaSource();
        out.append(indent(indent)).append("if (!(").append(stripOuterParens(condition)).append(")) {").append(System.lineSeparator());
        MethodContext.ScopeSnapshot guardScope = context.snapshotScope();
        writeBlockStatements(out, unit, guard.block(), context, indent + 1);
        context.restoreScope(guardScope);
        out.append(indent(indent)).append("}").append(System.lineSeparator());
    }

    private void writeIf(StringBuilder out, CompilationUnit unit, AffogatoParser.IfStatementContext ifStatement, MethodContext context, int indent) {
        String rawCondition = sourceText(unit.source(), ifStatement.condition());
        validateCondition(rawCondition, context, ifStatement.getStart().getLine(), ifStatement.getStart().getCharPositionInLine() + 1);
        String condition = transformExpressionTyped(rawCondition, context, ifStatement.condition()).javaSource();
        out.append(indent(indent)).append("if (").append(stripOuterParens(condition)).append(") {").append(System.lineSeparator());
        MethodContext.ScopeSnapshot thenScope = context.snapshotScope();
        writeBlockStatements(out, unit, ifStatement.block(0), context, indent + 1);
        context.restoreScope(thenScope);
        out.append(indent(indent)).append("}");
        if (ifStatement.ELSE() != null) {
            if (ifStatement.ifStatement() != null) {
                out.append(" else ");
                StringBuilder nested = new StringBuilder();
                writeIf(nested, unit, ifStatement.ifStatement(), context, 0);
                out.append(nested.toString().stripLeading());
            } else if (ifStatement.block().size() > 1) {
                out.append(" else {").append(System.lineSeparator());
                MethodContext.ScopeSnapshot elseScope = context.snapshotScope();
                writeBlockStatements(out, unit, ifStatement.block(1), context, indent + 1);
                context.restoreScope(elseScope);
                out.append(indent(indent)).append("}").append(System.lineSeparator());
            } else {
                out.append(System.lineSeparator());
            }
        } else {
            out.append(System.lineSeparator());
        }
    }

    private void writeFor(StringBuilder out, CompilationUnit unit, AffogatoParser.ForStatementContext forStatement, MethodContext context, int indent) {
        AffogatoParser.ForContentContext content = forStatement.forCondition().forContent();
        MethodContext.ScopeSnapshot loopScope = context.snapshotScope();
        if (content.IN() != null) {
            String variable = content.Identifier().getText();
            String rawIterable = sourceText(unit.source(), content.expression());
            TypedExpression typedIterable = transformExpressionTyped(rawIterable, context, content.expression());
            String iterable = typedIterable.javaSource();
            Optional<TypeGuess> elementType = elementType(typedIterable.resolvedType());
            if (elementType.isPresent()) {
                context.declareVariable(variable, TypeRef.unspecified(elementType.get().javaType()), true);
            } else if (typedIterable.resolvedType().isKnown() && !typedIterable.resolvedType().isNullLiteral()) {
                diagnostics.add(error(
                        unit.sourceFile(),
                        forStatement.getStart().getLine(),
                        forStatement.getStart().getCharPositionInLine() + 1,
                        "AFFOGATO_FOR_ITERABLE_TYPE",
                        "For-in loops require an array or Iterable expression."
                ));
            }
            context.mutableVariables.put(variable, true);
            out.append(indent(indent)).append("for (var ").append(variable).append(" : ").append(iterable).append(") {")
                    .append(System.lineSeparator());
        } else {
            String expression = transformExpression(sourceText(unit.source(), content.expression()), context);
            out.append(indent(indent)).append("for (").append(stripOuterParens(expression)).append(") {").append(System.lineSeparator());
        }
        writeBlockStatements(out, unit, forStatement.block(), context, indent + 1);
        context.restoreScope(loopScope);
        out.append(indent(indent)).append("}").append(System.lineSeparator());
    }

    private void writeWhile(StringBuilder out, CompilationUnit unit, AffogatoParser.WhileStatementContext whileStatement, MethodContext context, int indent) {
        String rawCondition = sourceText(unit.source(), whileStatement.condition());
        validateCondition(rawCondition, context, whileStatement.getStart().getLine(), whileStatement.getStart().getCharPositionInLine() + 1);
        String condition = transformExpressionTyped(rawCondition, context, whileStatement.condition()).javaSource();
        out.append(indent(indent)).append("while (").append(stripOuterParens(condition)).append(") {").append(System.lineSeparator());
        MethodContext.ScopeSnapshot whileScope = context.snapshotScope();
        writeBlockStatements(out, unit, whileStatement.block(), context, indent + 1);
        context.restoreScope(whileScope);
        out.append(indent(indent)).append("}").append(System.lineSeparator());
    }

    private void writeTry(StringBuilder out, CompilationUnit unit, AffogatoParser.TryStatementContext tryStatement, MethodContext context, int indent) {
        out.append(indent(indent)).append("try {").append(System.lineSeparator());
        MethodContext.ScopeSnapshot tryScope = context.snapshotScope();
        writeBlockStatements(out, unit, tryStatement.block(), context, indent + 1);
        context.restoreScope(tryScope);
        out.append(indent(indent)).append("}");
        for (AffogatoParser.CatchClauseContext catchClause : tryStatement.catchClause()) {
            List<TypeRef> caughtTypes = new ArrayList<>();
            for (AffogatoParser.TypeRefContext catchTypeContext : catchClause.catchType().typeRef()) {
                TypeRef catchType = typeRef(catchTypeContext);
                caughtTypes.add(catchType);
                validateTypeRef(catchType, unit, catchClause.getStart().getLine(), catchClause.getStart().getCharPositionInLine() + 1);
                if (!context.javaResolver.throwableCompatible(TypeGuess.of(catchType.javaType()), unit)) {
                    diagnostics.add(error(
                            unit.sourceFile(),
                            catchClause.getStart().getLine(),
                            catchClause.getStart().getCharPositionInLine() + 1,
                            "AFFOGATO_CATCH_TYPE",
                            "Catch types must be Throwable."
                    ));
                }
            }
            String catchTypes = caughtTypes.stream()
                    .map(TypeRef::javaType)
                    .collect(java.util.stream.Collectors.joining(" | "));
            String varName = catchClause.Identifier().getText();
            out.append(" catch (").append(catchTypes).append(" ").append(varName).append(") {").append(System.lineSeparator());
            MethodContext catchContext = MethodContext.forExecutable(unit, context.currentClass, context.executableName, context.returnType, classSymbols, extensionSymbols, javaResolver);
            catchContext.variableTypes.putAll(context.variableTypes);
            catchContext.mutableVariables.putAll(context.mutableVariables);
            catchContext.variableNullabilities.putAll(context.variableNullabilities);
            catchContext.declareVariable(varName, TypeRef.unspecified(caughtTypes.size() > 1 ? "Exception" : caughtTypes.get(0).javaType()), false);
            writeBlockStatements(out, unit, catchClause.block(), catchContext, indent + 1);
            out.append(indent(indent)).append("}");
        }
        if (tryStatement.finallyClause() != null) {
            out.append(" finally {").append(System.lineSeparator());
            MethodContext.ScopeSnapshot finallyScope = context.snapshotScope();
            writeBlockStatements(out, unit, tryStatement.finallyClause().block(), context, indent + 1);
            context.restoreScope(finallyScope);
            out.append(indent(indent)).append("}");
        }
        out.append(System.lineSeparator());
    }

    private void writeSwitch(StringBuilder out, CompilationUnit unit, AffogatoParser.SwitchStatementContext switchStatement, MethodContext context, int indent) {
        String rawCondition = sourceText(unit.source(), switchStatement.condition());
        TypedExpression typedCondition = transformExpressionTyped(rawCondition, context, switchStatement.condition());
        validateSwitchSelector(typedCondition.resolvedType(), unit, context);
        String condition = typedCondition.javaSource();
        out.append(indent(indent)).append("switch (").append(stripOuterParens(condition)).append(") {").append(System.lineSeparator());
        for (AffogatoParser.SwitchArmContext arm : switchStatement.switchBody().switchArm()) {
            context.currentLine = arm.getStart().getLine();
            context.currentColumn = arm.getStart().getCharPositionInLine() + 1;
            if (arm.DEFAULT() != null) {
                out.append(indent(indent + 1)).append("default -> ");
            } else {
                List<String> labels = new ArrayList<>();
                for (AffogatoParser.SwitchLabelContext label : arm.switchLabel()) {
                    TypedExpression typedLabel = transformExpressionTyped(sourceText(unit.source(), label.expression()), context);
                    validateSwitchLabel(typedCondition.resolvedType(), typedLabel.resolvedType(), unit, context);
                    labels.add(typedLabel.javaSource());
                }
                out.append(indent(indent + 1)).append("case ").append(String.join(", ", labels)).append(" -> ");
            }
            AffogatoParser.SwitchArmBodyContext body = arm.switchArmBody();
            if (body.block() != null) {
                out.append("{").append(System.lineSeparator());
                MethodContext.ScopeSnapshot armScope = context.snapshotScope();
                writeBlockStatements(out, unit, body.block(), context, indent + 2);
                context.restoreScope(armScope);
                out.append(indent(indent + 1)).append("}").append(System.lineSeparator());
            } else {
                String armExpr = transformExpression(sourceText(unit.source(), body.expression()), context);
                out.append(armExpr).append(";").append(System.lineSeparator());
            }
        }
        out.append(indent(indent)).append("}").append(System.lineSeparator());
    }

    // Builds a Java switch expression (arms produce values) for use in initializer or return position.
    // The returned text has no leading indent on the first line and no trailing semicolon; the caller
    // supplies both. Inner arms and the closing brace are indented relative to {@code indent}.
    private TypedExpression buildSwitchExpression(
            CompilationUnit unit,
            AffogatoParser.SwitchExpressionContext switchExpression,
            MethodContext context,
            int indent,
            TypeRef expectedType,
            String mismatchCode,
            String mismatchMessage
    ) {
        TypedExpression typedCondition = transformExpressionTyped(sourceText(unit.source(), switchExpression.condition()), context);
        validateSwitchSelector(typedCondition.resolvedType(), unit, context);
        String condition = typedCondition.javaSource();
        StringBuilder out = new StringBuilder();
        TypeGuess inferredType = TypeGuess.unknown();
        out.append("switch (").append(stripOuterParens(condition)).append(") {").append(System.lineSeparator());
        for (AffogatoParser.SwitchArmContext arm : switchExpression.switchBody().switchArm()) {
            context.currentLine = arm.getStart().getLine();
            context.currentColumn = arm.getStart().getCharPositionInLine() + 1;
            if (arm.DEFAULT() != null) {
                out.append(indent(indent + 1)).append("default -> ");
            } else {
                List<String> labels = new ArrayList<>();
                for (AffogatoParser.SwitchLabelContext label : arm.switchLabel()) {
                    TypedExpression typedLabel = transformExpressionTyped(sourceText(unit.source(), label.expression()), context);
                    validateSwitchLabel(typedCondition.resolvedType(), typedLabel.resolvedType(), unit, context);
                    labels.add(typedLabel.javaSource());
                }
                out.append(indent(indent + 1)).append("case ").append(String.join(", ", labels)).append(" -> ");
            }
            AffogatoParser.SwitchArmBodyContext body = arm.switchArmBody();
            if (body.block() != null) {
                diagnostics.add(error(unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_SWITCH_EXPR_BODY",
                        "Switch expression arms must produce a value with '-> expression'; block arms are not supported."));
                out.append("{}").append(System.lineSeparator());
            } else {
                String rawArmExpr = sourceText(unit.source(), body.expression());
                TypedExpression armExpr = transformExpressionTyped(rawArmExpr, context);
                if (expectedType != null && armExpr.resolvedType().isKnown() && !isAssignable(armExpr.resolvedType(), expectedType, context)) {
                    diagnostics.add(error(unit.sourceFile(), context.currentLine, context.currentColumn, mismatchCode, mismatchMessage));
                }
                inferredType = mergeSwitchArmType(inferredType, armExpr.resolvedType(), context);
                out.append(armExpr.javaSource()).append(";").append(System.lineSeparator());
            }
        }
        out.append(indent(indent)).append("}");
        return new TypedExpression(out.toString(), inferredType, new SwitchExpressionNode(out.toString(), inferredType));
    }

    private void validateSwitchLabel(TypeGuess selectorType, TypeGuess labelType, CompilationUnit unit, MethodContext context) {
        if (!selectorType.isKnown() || !labelType.isKnown() || selectorType.isNullLiteral() || labelType.isNullLiteral()) {
            return;
        }
        if (!context.javaResolver.assignmentCompatible(labelType, selectorType.javaType(), unit, InvocationPhase.LOOSE)) {
            diagnostics.add(error(
                    unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_SWITCH_LABEL_TYPE",
                    "Switch case label is not compatible with " + selectorType.javaType() + "."
            ));
        }
    }

    private void validateSwitchSelector(TypeGuess selectorType, CompilationUnit unit, MethodContext context) {
        if (!selectorType.isKnown() || selectorType.isNullLiteral()) {
            return;
        }
        if (!context.javaResolver.switchSelectorCompatible(selectorType, unit)) {
            diagnostics.add(error(
                    unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_SWITCH_SELECTOR_TYPE",
                    "Switch selectors must be String, enum, or an int-compatible type."
            ));
        }
    }

    private TypeGuess mergeSwitchArmType(TypeGuess current, TypeGuess next, MethodContext context) {
        if (!next.isKnown() || next.isNullLiteral()) {
            return current;
        }
        if (!current.isKnown() || current.isNullLiteral()) {
            return next;
        }
        if (current.javaType().equals(next.javaType())) {
            return current;
        }
        if (isNumericType(current) && isNumericType(next)) {
            return TypeGuess.of(promotedNumericType(current.javaType(), next.javaType()));
        }
        if (context.javaResolver.assignmentCompatible(next, current.javaType(), context.unit, InvocationPhase.LOOSE)) {
            return current;
        }
        if (context.javaResolver.assignmentCompatible(current, next.javaType(), context.unit, InvocationPhase.LOOSE)) {
            return next;
        }
        return TypeGuess.unknown();
    }

    private String transformLocalDeclaration(CompilationUnit unit, AffogatoParser.LocalVarDeclContext declaration, MethodContext context, int indent) {
        boolean immutable = declaration.variableKind().LET() != null;
        String name = declaration.Identifier().getText();
        TypeRef type = declaration.typeRef() == null ? null : typeRef(declaration.typeRef());
        int declLine = declaration.getStart().getLine();
        int declCol = declaration.getStart().getCharPositionInLine() + 1;
        validateDeclaredName(unit.sourceFile(), name, "local variable",
                declaration.Identifier().getSymbol().getLine(),
                declaration.Identifier().getSymbol().getCharPositionInLine() + 1);
        if (!context.declareBlockLocal(name)) {
            diagnostics.add(error(unit.sourceFile(), declLine, declCol, name.length(),
                    "AFFOGATO_DUPLICATE_LOCAL",
                    "Duplicate local variable '" + name + "' in the same block."));
        }
        if (type != null) {
            validateTypeRef(type, unit, declLine, declCol);
        }

        if (declaration.switchExpression() != null) {
            TypedExpression switchExpr = buildSwitchExpression(unit, declaration.switchExpression(), context, indent, type,
                    "AFFOGATO_ASSIGNMENT_TYPE", "Switch arm value is not assignable to " + (type == null ? "the inferred local type" : type.javaType()) + ".");
            TypeGuess switchType = switchExpr.resolvedType();
            if (type != null) {
                context.declareVariable(name, type, !immutable);
            } else if (switchType.isKnown() && !switchType.isNullLiteral()) {
                context.declareVariable(name, TypeRef.unspecified(switchType.javaType()), !immutable);
            }
            context.mutableVariables.put(name, !immutable);
            StringBuilder decl = new StringBuilder();
            if (immutable) {
                decl.append("final ");
            }
            decl.append(type == null ? "var" : type.declaration()).append(' ').append(name)
                    .append(" = ").append(switchExpr.javaSource()).append(';');
            return decl.toString();
        }

        String rawInitializer = declaration.expression() == null
                ? ""
                : mergeTrailingClosure(sourceText(unit.source(), declaration.expression()),
                        unit.source(), declaration.trailingClosure(), context);
        // Target-type an array-literal initializer to the declared single-dimension array element type, so
        // `let xs: Person[] = [...]` emits `new Person[]{...}` (matching the declared type) rather than the
        // too-wide `new Object[]` that the element-based inference would otherwise pick.
        String previousExpectedArrayElement = expectedArrayElementType;
        if (type != null && type.javaType().endsWith("[]")) {
            String element = type.javaType().substring(0, type.javaType().length() - 2);
            expectedArrayElementType = element.endsWith("[]") ? null : element;
        } else {
            expectedArrayElementType = null;
        }
        TypedExpression typedInit;
        try {
            typedInit = rawInitializer.isBlank()
                    ? null
                    : transformExpressionTyped(rawInitializer, context, declaration.expression());
        } finally {
            expectedArrayElementType = previousExpectedArrayElement;
        }
        String initializer = typedInit == null ? "" : typedInit.javaSource();

        if (type == null && typedInit != null) {
            TypeGuess inferred = typedInit.resolvedType();
            if (inferred.isLambda()) {
                diagnostics.add(error(unit.sourceFile(), declLine, declCol, "AFFOGATO_POLY_TARGET_TYPE",
                        "Lambda and method-reference expressions need an explicit target type."));
            } else {
                type = inferred.isKnown() && !inferred.isNullLiteral() ? TypeRef.unspecified(inferred.javaType()) : inferType(initializer);
            }
            if (type == null && inferred.isNullLiteral()) {
                diagnostics.add(error(unit.sourceFile(), declLine, declCol, "AFFOGATO_LOCAL_TYPE",
                        "Local variables initialized with null need an explicit type."));
            }
        } else if (type != null && !rawInitializer.isBlank()) {
            validateAssignment(type, rawInitializer, context, declLine, declCol,
                    "AFFOGATO_ASSIGNMENT_TYPE", "Initializer type is not assignable to " + type.javaType() + ".");
        }
        TypeRef bindingType = type;
        if (bindingType == null && typedInit != null && typedInit.resolvedType().isKnown() && !typedInit.resolvedType().isNullLiteral()) {
            bindingType = TypeRef.unspecified(typedInit.resolvedType().javaType());
        }
        if (bindingType == null && !rawInitializer.isBlank()) {
            bindingType = TypeRef.unspecified("java.lang.Object");
        }
        if (bindingType != null) {
            context.declareVariable(name, bindingType, !immutable);
        }

        StringBuilder out = new StringBuilder();
        if (immutable) {
            out.append("final ");
        }
        out.append(type == null ? (bindingType == null ? "var" : bindingType.declaration()) : type.declaration()).append(' ').append(name);
        if (!initializer.isBlank()) {
            out.append(" = ").append(initializer);
        }
        out.append(';');
        return out.toString();
    }

    private String transformPropertyAssignment(Matcher matcher, MethodContext context) {
        String owner = matcher.group(1);
        String property = matcher.group(2);
        String expression = transformExpression(matcher.group(3), context);
        FieldSymbol field = resolveField(owner, property, context);
        if (field != null) {
            if (!field.mutable()) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn, "AFFOGATO_LET_ASSIGN", "Cannot assign to let property " + property + "."));
                return owner + "." + property + " = " + expression + ";";
            }
            validateAssignment(field.type(), matcher.group(3), context, context.currentLine, context.currentColumn, "AFFOGATO_ASSIGNMENT_TYPE", "Assigned value is not assignable to " + field.type().javaType() + ".");
            return owner + "." + setterName(property) + "(" + expression + ");";
        }

        String ownerType = context.variableTypes.get(owner);
        if (ownerType != null && context.javaResolver.setterExists(ownerType, property, context.unit)) {
            context.javaResolver.setterParameterType(ownerType, property, context.unit)
                    .ifPresent(type -> validateAssignment(TypeRef.unspecified(type.javaType()), matcher.group(3), context, context.currentLine, context.currentColumn, "AFFOGATO_ASSIGNMENT_TYPE", "Assigned value is not assignable to " + type.javaType() + "."));
            return owner + "." + setterName(property) + "(" + expression + ");";
        }
        if (ownerType != null && context.javaResolver.fieldExists(ownerType, property, context.unit)) {
            if (!context.javaResolver.fieldMutable(ownerType, property, context.unit)) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn, "AFFOGATO_LET_ASSIGN", "Cannot assign to final Java field " + property + "."));
                return owner + "." + property + " = " + expression + ";";
            }
            context.javaResolver.fieldType(ownerType, property, context.unit)
                    .ifPresent(type -> validateAssignment(TypeRef.unspecified(type.javaType()), matcher.group(3), context, context.currentLine, context.currentColumn, "AFFOGATO_ASSIGNMENT_TYPE", "Assigned value is not assignable to " + type.javaType() + "."));
            return owner + "." + property + " = " + expression + ";";
        }
        return null;
    }

    // Compound assignment to a property (`c.n += x`). A property backed by a getter/setter cannot use
    // Java's `+=` (the left side would be a method call), so it is desugared to
    // `c.setN(c.getN() + (x))`. Directly-accessible mutable Java fields keep the native `+=` form.
    private String transformPropertyCompoundAssignment(Matcher matcher, MethodContext context) {
        String owner = matcher.group(1);
        String property = matcher.group(2);
        String operator = matcher.group(3);
        String value = transformExpression(matcher.group(4), context);

        FieldSymbol field = resolveField(owner, property, context);
        if (field != null) {
            if (!field.mutable()) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to let property " + property + "."));
                return owner + "." + property + " " + operator + "= " + value + ";";
            }
            String read = owner + "." + getterName(property, field.type()) + "()";
            return owner + "." + setterName(property) + "(" + read + " " + operator + " (" + value + "));";
        }

        String ownerType = context.variableTypes.get(owner);
        if (ownerType != null
                && context.javaResolver.setterExists(ownerType, property, context.unit)
                && context.javaResolver.getterExists(ownerType, property, context.unit)) {
            String getter = context.javaResolver.getterInvocationName(ownerType, property, context.unit)
                    .orElse(getterName(property, TypeRef.unspecified("Object")));
            String read = owner + "." + getter + "()";
            return owner + "." + setterName(property) + "(" + read + " " + operator + " (" + value + "));";
        }
        if (ownerType != null && context.javaResolver.fieldExists(ownerType, property, context.unit)) {
            if (!context.javaResolver.fieldMutable(ownerType, property, context.unit)) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to final Java field " + property + "."));
            }
            return owner + "." + property + " " + operator + "= " + value + ";";
        }
        return null;
    }

    // Increment/decrement of a property statement (`c.n++`, `++c.n`, `c.n--`). As a statement the result
    // is discarded so prefix and postfix are equivalent; a getter/setter-backed property is desugared to
    // `c.setN(c.getN() + 1)`. Directly-accessible mutable Java fields keep the native `++`/`--`.
    private String transformPropertyIncDec(String owner, String property, boolean increment, MethodContext context) {
        String operator = increment ? "+" : "-";
        String suffix = increment ? "++" : "--";

        FieldSymbol field = resolveField(owner, property, context);
        if (field != null) {
            if (!field.mutable()) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to let property " + property + "."));
                return owner + "." + property + suffix + ";";
            }
            String read = owner + "." + getterName(property, field.type()) + "()";
            return owner + "." + setterName(property) + "(" + read + " " + operator + " 1);";
        }

        String ownerType = context.variableTypes.get(owner);
        if (ownerType != null
                && context.javaResolver.setterExists(ownerType, property, context.unit)
                && context.javaResolver.getterExists(ownerType, property, context.unit)) {
            String getter = context.javaResolver.getterInvocationName(ownerType, property, context.unit)
                    .orElse(getterName(property, TypeRef.unspecified("Object")));
            String read = owner + "." + getter + "()";
            return owner + "." + setterName(property) + "(" + read + " " + operator + " 1);";
        }
        if (ownerType != null && context.javaResolver.fieldExists(ownerType, property, context.unit)) {
            if (!context.javaResolver.fieldMutable(ownerType, property, context.unit)) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to final Java field " + property + "."));
            }
            return owner + "." + property + suffix + ";";
        }
        return null;
    }

    // Handles a write whose target is `<receiver>.<property>` where the receiver is more than a bare
    // identifier (a property chain, call, cast or index): `a.b.c = x`, `a.b.z += 1`, `a.b.z++`,
    // `make().z = 5`. The receiver is lowered through the normal pipeline (so reads resolve to getters)
    // and the final property write becomes a setter call. Returns null for non-property targets (locals,
    // array elements) so they keep native Java assignment.
    private String transformComplexPropertyWrite(String expression, MethodContext context) {
        // Increment / decrement (statement position: prefix and postfix are equivalent).
        String incDecTarget = null;
        boolean increment = false;
        if (expression.endsWith("++") || expression.endsWith("--")) {
            incDecTarget = expression.substring(0, expression.length() - 2).trim();
            increment = expression.endsWith("++");
        } else if (expression.startsWith("++") || expression.startsWith("--")) {
            incDecTarget = expression.substring(2).trim();
            increment = expression.startsWith("++");
        }
        if (incDecTarget != null) {
            return lowerPropertyTarget(incDecTarget, increment ? "+" : "-", "1", true, context);
        }

        int operatorStart = topLevelAssignmentStart(expression);
        if (operatorStart < 0) {
            return null;
        }
        boolean compound = expression.charAt(operatorStart) != '=';
        String operator = compound ? String.valueOf(expression.charAt(operatorStart)) : "=";
        int valueStart = operatorStart + (compound ? 2 : 1);
        String target = expression.substring(0, operatorStart).trim();
        String value = expression.substring(valueStart).trim();
        return lowerPropertyTarget(target, operator, value, compound, context);
    }

    private String lowerPropertyTarget(String target, String operator, String rawValue, boolean readModify, MethodContext context) {
        int dot = lastTopLevelDot(target);
        if (dot <= 0) {
            return null; // not a property target (local, array element) — keep native assignment
        }
        String receiver = target.substring(0, dot).trim();
        String property = target.substring(dot + 1).trim();
        if (!property.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        TypeGuess receiverType = inferExpressionType(receiver, context);
        if (!receiverType.isKnown() || receiverType.isNullLiteral()) {
            return null;
        }
        String type = receiverType.javaType();
        String loweredReceiver = transformExpression(receiver, context);
        String value = transformExpression(rawValue, context);

        FieldSymbol field = fieldForOwnerType(type, property, context);
        if (field != null) {
            if (!field.mutable()) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to let property " + property + "."));
                return loweredReceiver + "." + property + " = " + value + ";";
            }
            String read = loweredReceiver + "." + getterName(property, field.type()) + "()";
            String setValue = readModify ? read + " " + operator + " (" + value + ")" : value;
            return loweredReceiver + "." + setterName(property) + "(" + setValue + ");";
        }
        if (context.javaResolver.setterExists(type, property, context.unit)) {
            String setValue = value;
            if (readModify) {
                if (!context.javaResolver.getterExists(type, property, context.unit)) {
                    return null;
                }
                String getter = context.javaResolver.getterInvocationName(type, property, context.unit)
                        .orElse(getterName(property, TypeRef.unspecified("Object")));
                setValue = loweredReceiver + "." + getter + "() " + operator + " (" + value + ")";
            }
            return loweredReceiver + "." + setterName(property) + "(" + setValue + ");";
        }
        if (context.javaResolver.fieldExists(type, property, context.unit)) {
            if (!context.javaResolver.fieldMutable(type, property, context.unit)) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to final Java field " + property + "."));
            }
            String assignOp = readModify ? " " + operator + "= " : " = ";
            return loweredReceiver + "." + property + assignOp + value + ";";
        }
        return null;
    }

    // Index of the start of a top-level assignment operator (`=` or `+= -= *= /= %=`), or -1. Skips
    // comparison operators (`== != <= >=`) and anything inside (), [], {} or string literals.
    private int topLevelAssignmentStart(String expression) {
        int paren = 0;
        int bracket = 0;
        int brace = 0;
        boolean inString = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            char previous = index > 0 ? expression.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (paren == 0 && bracket == 0 && brace == 0) {
                char next = index + 1 < expression.length() ? expression.charAt(index + 1) : '\0';
                char after = index + 2 < expression.length() ? expression.charAt(index + 2) : '\0';
                if ((current == '+' || current == '-' || current == '*' || current == '/' || current == '%')
                        && next == '=' && after != '=') {
                    return index;
                }
                if (current == '=' && next != '='
                        && previous != '=' && previous != '!' && previous != '<' && previous != '>'
                        && previous != '+' && previous != '-' && previous != '*' && previous != '/' && previous != '%') {
                    return index;
                }
            }
            switch (current) {
                case '(' -> paren++;
                case ')' -> paren = Math.max(0, paren - 1);
                case '[' -> bracket++;
                case ']' -> bracket = Math.max(0, bracket - 1);
                case '{' -> brace++;
                case '}' -> brace = Math.max(0, brace - 1);
                default -> { }
            }
        }
        return -1;
    }

    private int lastTopLevelDot(String text) {
        int paren = 0;
        int bracket = 0;
        int brace = 0;
        int angle = 0;
        boolean inString = false;
        int last = -1;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char previous = index > 0 ? text.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            switch (current) {
                case '(' -> paren++;
                case ')' -> paren = Math.max(0, paren - 1);
                case '[' -> bracket++;
                case ']' -> bracket = Math.max(0, bracket - 1);
                case '{' -> brace++;
                case '}' -> brace = Math.max(0, brace - 1);
                case '<' -> angle++;
                case '>' -> angle = Math.max(0, angle - 1);
                case '.' -> {
                    if (paren == 0 && bracket == 0 && brace == 0 && angle == 0) {
                        last = index;
                    }
                }
                default -> { }
            }
        }
        return last;
    }

    // ── TYPE CHECKING ────────────────────────────────────────────────────────────

    private void validateTypeRef(TypeRef type, CompilationUnit unit, int line, int column) {
        validateTypeName(type.javaType(), unit, line, column);
    }

    private void validateTypeName(String typeName, CompilationUnit unit, int line, int column) {
        String javaType = stripNullableSuffix(typeName.trim());
        if (javaType.equals("void") || PRIMITIVES.contains(javaType) || javaType.equals("?")) {
            return;
        }
        String raw = javaType;
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            int genericEnd = raw.lastIndexOf('>');
            if (genericEnd > generic) {
                for (String argument : splitTopLevel(raw.substring(generic + 1, genericEnd), ',')) {
                    validateTypeName(stripWildcardBound(argument), unit, line, column);
                }
            }
            raw = raw.substring(0, generic);
        }
        if (PRIMITIVES.contains(raw) || activeTypeParams.contains(raw) || classSymbol(raw, unit) != null || javaResolver.typeExists(raw, unit)) {
            return;
        }
        diagnostics.add(error(
                unit.sourceFile(),
                line,
                column,
                "AFFOGATO_TYPE_RESOLUTION",
                "Cannot resolve type " + raw + "."
        ));
    }

    private String stripWildcardBound(String typeName) {
        String type = typeName.trim();
        if (type.equals("?")) {
            return type;
        }
        if (type.startsWith("? extends ")) {
            return type.substring("? extends ".length()).trim();
        }
        if (type.startsWith("? super ")) {
            return type.substring("? super ".length()).trim();
        }
        return type;
    }

    private void validateReturn(String rawExpression, MethodContext context, int line, int column) {
        boolean returnsVoid = context.returnType.javaType().equals("void");
        if (rawExpression.isBlank()) {
            if (!returnsVoid) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        line,
                        column,
                        "AFFOGATO_RETURN_TYPE",
                        "Method " + context.executableName + " must return " + context.returnType.javaType() + "."
                ));
            }
            return;
        }
        if (returnsVoid) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_RETURN_TYPE",
                    "Void method " + context.executableName + " cannot return a value."
            ));
            return;
        }
        validateAssignment(
                context.returnType,
                rawExpression,
                context,
                line,
                column,
                "AFFOGATO_RETURN_TYPE",
                "Returned value is not assignable to " + context.returnType.javaType() + "."
        );
    }

    private void validateThrowExpression(TypedExpression expression, MethodContext context, int line, int column) {
        TypeGuess type = expression.resolvedType();
        if (type.isKnown() && !type.isNullLiteral() && !context.javaResolver.throwableCompatible(type, context.unit)) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_THROW_TYPE",
                    "Throw expressions must be Throwable."
            ));
        }
    }

    private void validateVariableAssignment(Matcher matcher, MethodContext context, int line, int column) {
        String name = matcher.group(1);
        String expectedType = context.variableTypes.get(name);
        if (expectedType == null) {
            return;
        }
        if (Boolean.FALSE.equals(context.mutableVariables.get(name))) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_LET_ASSIGN",
                    "Cannot assign to let local " + name + "."
            ));
            return;
        }
        validateAssignment(
                new TypeRef(expectedType, context.variableNullabilities.getOrDefault(name, Nullability.UNSPECIFIED)),
                matcher.group(2),
                context,
                line,
                column,
                "AFFOGATO_ASSIGNMENT_TYPE",
                "Assigned value is not assignable to " + expectedType + "."
        );
    }

    private void validateCondition(String rawExpression, MethodContext context, int line, int column) {
        AstExpression ast = expressionAst(rawExpression, context);
        TypeGuess type = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(rawExpression, context);
        if ((type.isKnown() && !isBooleanType(type)) || !isBooleanConditionAst(ast, context)) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    line,
                    column,
                    "AFFOGATO_CONDITION_TYPE",
                    "Conditions must be boolean."
            ));
        }
    }

    private void validateAssignment(
            TypeRef expected,
            String rawExpression,
            MethodContext context,
            int line,
            int column,
            String code,
            String message
    ) {
        AstExpression ast = expressionAst(rawExpression, context);
        if (!isAssignmentAstCompatible(ast, expected, context)) {
            diagnostics.add(error(context.unit.sourceFile(), line, column, code, message));
            return;
        }
        TypeGuess actual = inferExpressionType(rawExpression, context);
        if (!actual.isKnown()) {
            return;
        }
        if (!isAssignable(actual, expected, context)) {
            diagnostics.add(error(context.unit.sourceFile(), line, column, code, message));
        }
    }

    private boolean isAssignmentAstCompatible(AstExpression ast, TypeRef expected, MethodContext context) {
        if (ast instanceof TernaryExpression ternary) {
            return isBooleanConditionAst(ternary.condition(), context)
                    && isAssignmentAstCompatible(ternary.thenExpression(), expected, context)
                    && isAssignmentAstCompatible(ternary.elseExpression(), expected, context);
        }
        if (expected.nullability() == Nullability.NOT_NULL && expressionMayBeNullable(ast, context)) {
            return false;
        }
        TypeGuess actual = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(ast.source(), context);
        return !actual.isKnown() || isAssignable(actual, expected, context);
    }

    private boolean expressionMayBeNullable(AstExpression ast, MethodContext context) {
        if (ast instanceof LiteralExpression literal && literal.resolvedType().isNullLiteral()) {
            return true;
        }
        if (ast instanceof IdentifierExpression identifier) {
            return context.variableNullabilities.getOrDefault(identifier.name(), Nullability.UNSPECIFIED) == Nullability.NULLABLE;
        }
        if (ast instanceof TernaryExpression ternary) {
            return expressionMayBeNullable(ternary.thenExpression(), context)
                    || expressionMayBeNullable(ternary.elseExpression(), context);
        }
        if (ast instanceof CastExpression cast) {
            return expressionMayBeNullable(cast.expression(), context);
        }
        return false;
    }

    private boolean isBooleanConditionAst(AstExpression ast, MethodContext context) {
        if (ast instanceof TernaryExpression ternary) {
            return isBooleanConditionAst(ternary.condition(), context)
                    && isBooleanOperand(ternary.thenExpression(), context)
                    && isBooleanOperand(ternary.elseExpression(), context);
        }
        if (ast instanceof BinaryExpression binary) {
            return switch (binary.operator()) {
                case "||", "&&" -> isBooleanOperand(binary.left(), context) && isBooleanOperand(binary.right(), context);
                case "<", "<=", ">", ">=" -> isNumericOperand(binary.left(), context) && isNumericOperand(binary.right(), context);
                case "==", "!=" -> true;
                default -> {
                    TypeGuess type = inferExpressionType(binary.source(), context);
                    yield !type.isKnown() || isBooleanType(type);
                }
            };
        }
        if (ast instanceof UnaryExpression unary && unary.operator().equals("!")) {
            return isBooleanOperand(unary.expression(), context);
        }
        TypeGuess type = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(ast.source(), context);
        return !type.isKnown() || isBooleanType(type);
    }

    private boolean isBooleanOperand(AstExpression ast, MethodContext context) {
        return isBooleanConditionAst(ast, context);
    }

    private boolean isNumericOperand(AstExpression ast, MethodContext context) {
        TypeGuess type = ast.resolvedType().isKnown() ? ast.resolvedType() : inferExpressionType(ast.source(), context);
        return !type.isKnown() || isNumericType(type);
    }

    private boolean isPlusOperandCompatible(AstExpression left, AstExpression right, MethodContext context) {
        TypeGuess leftType = left.resolvedType().isKnown() ? left.resolvedType() : inferExpressionType(left.source(), context);
        TypeGuess rightType = right.resolvedType().isKnown() ? right.resolvedType() : inferExpressionType(right.source(), context);
        if (!leftType.isKnown() || !rightType.isKnown()) {
            return true;
        }
        return isStringType(leftType) || isStringType(rightType) || isNumericType(leftType) && isNumericType(rightType);
    }

    private boolean isEqualityCompatible(AstExpression left, AstExpression right, MethodContext context) {
        TypeGuess leftType = left.resolvedType().isKnown() ? left.resolvedType() : inferExpressionType(left.source(), context);
        TypeGuess rightType = right.resolvedType().isKnown() ? right.resolvedType() : inferExpressionType(right.source(), context);
        if (!leftType.isKnown() || !rightType.isKnown() || leftType.isNullLiteral() || rightType.isNullLiteral()) {
            return true;
        }
        if (isBooleanType(leftType) || isBooleanType(rightType)) {
            return isBooleanType(leftType) && isBooleanType(rightType);
        }
        if (isNumericType(leftType) || isNumericType(rightType)) {
            return isNumericType(leftType) && isNumericType(rightType);
        }
        return context.javaResolver.assignmentCompatible(leftType, rightType.javaType(), context.unit, InvocationPhase.LOOSE)
                || context.javaResolver.assignmentCompatible(rightType, leftType.javaType(), context.unit, InvocationPhase.LOOSE);
    }

    private boolean ternaryBranchesCompatible(AstExpression thenExpression, AstExpression elseExpression, MethodContext context) {
        TypeGuess thenType = thenExpression.resolvedType().isKnown() ? thenExpression.resolvedType() : inferExpressionType(thenExpression.source(), context);
        TypeGuess elseType = elseExpression.resolvedType().isKnown() ? elseExpression.resolvedType() : inferExpressionType(elseExpression.source(), context);
        if (!thenType.isKnown() || !elseType.isKnown() || thenType.isNullLiteral() || elseType.isNullLiteral()) {
            return true;
        }
        if (thenType.javaType().equals(elseType.javaType())) {
            return true;
        }
        if (isNumericType(thenType) && isNumericType(elseType)) {
            return true;
        }
        return context.javaResolver.assignmentCompatible(thenType, elseType.javaType(), context.unit, InvocationPhase.LOOSE)
                || context.javaResolver.assignmentCompatible(elseType, thenType.javaType(), context.unit, InvocationPhase.LOOSE);
    }

    private boolean isBooleanType(TypeGuess type) {
        return type.javaType().equals("boolean") || type.javaType().equals("java.lang.Boolean");
    }

    private boolean isArrayIndexType(TypeGuess type) {
        return switch (primitiveNumericType(type.javaType())) {
            case "byte", "short", "char", "int" -> true;
            default -> false;
        };
    }

    private boolean isAssignable(TypeGuess actual, TypeRef expected, MethodContext context) {
        if (actual.isNullLiteral() && expected.nullability() == Nullability.NOT_NULL) {
            return false;
        }
        if (!actual.isKnown()) {
            return true;
        }
        return context.javaResolver.assignmentCompatible(actual, expected.javaType(), context.unit, InvocationPhase.LOOSE);
    }

    // ── CODE TRANSFORMATION ──────────────────────────────────────────────────────

    private String transformExpression(String expression, MethodContext context) {
        return transformExpressionTyped(expression, context).javaSource();
    }

    private TypedExpression transformExpressionTyped(String expression, MethodContext context) {
        return transformExpressionTyped(expression, context, null);
    }

    private TypedExpression transformExpressionTyped(String expression, MethodContext context, ParserRuleContext expressionAnchor) {
        int savedExpressionLine = context.expressionLine;
        int savedExpressionColumn = context.expressionColumn;
        if (expressionAnchor != null && expressionAnchor.getStart() != null) {
            context.expressionLine = expressionAnchor.getStart().getLine();
            context.expressionColumn = expressionAnchor.getStart().getCharPositionInLine() + 1;
        } else {
            context.expressionLine = context.currentLine;
            context.expressionColumn = context.currentColumn;
        }
        try {
            return transformExpressionTypedInSpan(expression, context, expressionAnchor);
        } finally {
            context.expressionLine = savedExpressionLine;
            context.expressionColumn = savedExpressionColumn;
        }
    }

    private TypedExpression transformExpressionTypedInSpan(String expression, MethodContext context, ParserRuleContext expressionAnchor) {
        AstExpression ast = expressionAst(expression, context);
        validateExpressionSubset(ast, context, expression);
        validateExpressionSemantics(ast, context, expression);
        String result = expression.trim();
        result = transformInterpolatedStringsFromParseTree(result, context, expressionAnchor);
        result = transformStringInterpolation(result, context);
        result = transformReceiverThis(result, context);
        result = transformImplicitReceiver(result, context);
        result = transformTypedLambda(result);
        result = transformExtensionCalls(result, context);
        result = transformNamedArguments(result, context);
        result = transformArrayConstruction(result);
        validateExplicitConstructorCalls(result, context);
        validateMethodCalls(result, context);
        result = transformNot(result);
        result = transformInstanceof(result);
        validateCasts(result, context);
        result = transformCast(result);
        result = transformTypeConstruction(result, context);
        if (!context.hasCurrentMethod("println") && !context.variableTypes.containsKey("println")) {
            result = result.replaceAll("(?<![A-Za-z0-9_.$])println\\s*\\(", "System.out.println(");
        }
        result = transformArrayLiteral(result, context);
        result = transformPropertyReads(result, context);
        TypeGuess resolvedType = ast.resolvedType().isKnown() && astTypeCanShortCircuitInference(ast)
                ? ast.resolvedType()
                : inferExpressionType(expression.trim(), context);
        return new TypedExpression(result, resolvedType, ast);
    }

    private void expressionSemanticError(MethodContext context, String rawExpression, AstExpression at, String code, String message) {
        int line = context.expressionLine > 0 ? context.expressionLine : context.currentLine;
        int baseColumn = expressionBaseColumn(context, rawExpression);
        int offset = AstSpans.startOffset(at);
        int length = AstSpans.spanLength(at, offset);
        diagnostics.add(error(context.unit.sourceFile(), line, baseColumn + offset, length, code, message));
    }

    private static int expressionBaseColumn(MethodContext context, String rawExpression) {
        int base = context.expressionColumn > 0 ? context.expressionColumn : context.currentColumn;
        int index = 0;
        while (index < rawExpression.length() && Character.isWhitespace(rawExpression.charAt(index))) {
            index++;
        }
        return base + index;
    }

    private void validateExpressionSubset(AstExpression ast, MethodContext context, String rawExpression) {
        if (ast instanceof UnsupportedExpression unsupported) {
            expressionSemanticError(context, rawExpression, unsupported, unsupported.code(), unsupported.message());
        }
    }

    private void validateExpressionSemantics(AstExpression ast, MethodContext context, String rawExpression) {
        if (ast instanceof BinaryExpression binary) {
            validateExpressionSemantics(binary.left(), context, rawExpression);
            validateExpressionSemantics(binary.right(), context, rawExpression);
            if ((binary.operator().equals("||") || binary.operator().equals("&&"))
                    && (!isBooleanOperand(binary.left(), context) || !isBooleanOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_CONDITION_TYPE",
                        "Boolean operators require boolean operands.");
            } else if (List.of("<", "<=", ">", ">=").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_CONDITION_TYPE",
                        "Relational operators require numeric operands.");
            } else if (List.of("-", "*", "/", "%").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Arithmetic operators require numeric operands.");
            } else if (binary.operator().equals("+") && !isPlusOperandCompatible(binary.left(), binary.right(), context)) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Plus operands must be numeric or include a String operand.");
            } else if ((binary.operator().equals("==") || binary.operator().equals("!="))
                    && !isEqualityCompatible(binary.left(), binary.right(), context)) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Equality operands are not comparable.");
            } else if (List.of("&", "|", "^").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Bitwise operators require numeric operands.");
            } else if (List.of("<<", ">>", ">>>").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                expressionSemanticError(context, rawExpression, binary, "AFFOGATO_OPERATOR_TYPE",
                        "Shift operators require numeric operands.");
            }
            return;
        }
        if (ast instanceof UnaryExpression unary) {
            validateExpressionSemantics(unary.expression(), context, rawExpression);
            if (unary.operator().equals("!") && !isBooleanOperand(unary.expression(), context)) {
                expressionSemanticError(context, rawExpression, unary, "AFFOGATO_CONDITION_TYPE",
                        "Boolean negation requires a boolean operand.");
            } else if (unary.operator().equals("~") && !isNumericOperand(unary.expression(), context)) {
                expressionSemanticError(context, rawExpression, unary, "AFFOGATO_OPERATOR_TYPE",
                        "Bitwise complement requires a numeric operand.");
            } else if ((unary.operator().equals("++") || unary.operator().equals("--"))
                    && !isNumericOperand(unary.expression(), context)) {
                expressionSemanticError(context, rawExpression, unary, "AFFOGATO_OPERATOR_TYPE",
                        "Increment and decrement require a numeric operand.");
            } else if ((unary.operator().equals("++") || unary.operator().equals("--"))
                    && unary.expression() instanceof PropertyAccessExpression property
                    && isGetterSetterBackedPropertyAccess(property, context)) {
                expressionSemanticError(context, rawExpression, property, "AFFOGATO_PROPERTY_MUTATION_EXPR",
                        "Mutating property `" + property.source() + "` with `++`/`--`/`+=` is not supported inside an expression; do it in a separate statement.");
            }
            return;
        }
        if (ast instanceof TernaryExpression ternary) {
            validateExpressionSemantics(ternary.condition(), context, rawExpression);
            validateExpressionSemantics(ternary.thenExpression(), context, rawExpression);
            validateExpressionSemantics(ternary.elseExpression(), context, rawExpression);
            if (!isBooleanConditionAst(ternary.condition(), context)) {
                expressionSemanticError(context, rawExpression, ternary.condition(), "AFFOGATO_CONDITION_TYPE",
                        "Ternary conditions must be boolean.");
            }
            if (!ternaryBranchesCompatible(ternary.thenExpression(), ternary.elseExpression(), context)) {
                expressionSemanticError(context, rawExpression, ternary, "AFFOGATO_TERNARY_TYPE",
                        "Ternary branches must have compatible types.");
            }
            return;
        }
        if (ast instanceof InstanceOfExpression instanceOf) {
            validateExpressionSemantics(instanceOf.expression(), context, rawExpression);
            TypeGuess source = instanceOf.expression().resolvedType().isKnown()
                    ? instanceOf.expression().resolvedType()
                    : inferExpressionType(instanceOf.expression().source(), context);
            if (classSymbol(instanceOf.targetType(), context.unit) == null
                    && !context.javaResolver.typeExists(instanceOf.targetType(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + instanceOf.targetType() + "."
                ));
            }
            if (source.isKnown() && PRIMITIVES.contains(primitiveNumericType(source.javaType()))) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_INSTANCEOF_TYPE",
                        "Instance-of source must be a reference type."
                ));
            }
            return;
        }
        if (ast instanceof ClassLiteralExpression classLiteral) {
            String typeName = stripNullableSuffix(classLiteral.typeName());
            if (activeTypeParams.contains(typeName)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CLASS_LITERAL_TYPE",
                        "Class literals cannot use erased type parameter " + typeName + "."
                ));
            } else if (classSymbol(typeName, context.unit) == null
                    && !context.javaResolver.typeExists(typeName, context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + typeName + "."
                ));
            }
            return;
        }
        if (ast instanceof CallExpression call) {
            call.arguments().forEach(argument -> validateExpressionSemantics(argument, context, rawExpression));
            validateExpressionSemantics(call.receiver(), context, rawExpression);
            return;
        }
        if (ast instanceof ConstructorExpression constructor) {
            constructor.arguments().forEach(argument -> validateExpressionSemantics(argument, context, rawExpression));
            return;
        }
        if (ast instanceof AssignmentExpression assignment) {
            validateExpressionSemantics(assignment.target(), context, rawExpression);
            validateExpressionSemantics(assignment.value(), context, rawExpression);
            if (isCompoundAssignmentSource(assignment.source())
                    && assignment.target() instanceof PropertyAccessExpression property
                    && isGetterSetterBackedPropertyAccess(property, context)) {
                expressionSemanticError(context, rawExpression, property, "AFFOGATO_PROPERTY_MUTATION_EXPR",
                        "Mutating property `" + property.source() + "` with `++`/`--`/`+=` is not supported inside an expression; do it in a separate statement.");
            }
            return;
        }
        if (ast instanceof CastExpression cast) {
            validateExpressionSemantics(cast.expression(), context, rawExpression);
            TypeGuess source = cast.expression().resolvedType().isKnown()
                    ? cast.expression().resolvedType()
                    : inferExpressionType(cast.expression().source(), context);
            if (classSymbol(cast.targetType(), context.unit) == null
                    && !context.javaResolver.typeExists(cast.targetType(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + cast.targetType() + "."
                ));
            }
            if (source.isKnown()
                    && !source.isNullLiteral()
                    && !source.isLambda()
                    && !context.javaResolver.castPossible(source, cast.targetType(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CAST_TYPE",
                        "Cannot cast " + source.javaType() + " to " + cast.targetType() + "."
                ));
            }
            return;
        }
        if (ast instanceof PropertyAccessExpression property) {
            validateExpressionSemantics(property.receiver(), context, rawExpression);
            TypeGuess receiverType = property.receiver().resolvedType().isKnown()
                    ? property.receiver().resolvedType()
                    : inferExpressionType(property.receiver().source(), context);
            // Resolve on the receiver type from the AST receiver, so a call/cast/paren receiver
            // (`make().name`, `(o as T).name`) is checked instead of being rejected because the flat
            // source contains parentheses.
            TypeGuess resolved = propertyType(receiverType, property.property(), context);
            if (receiverType.isKnown() && !receiverType.isNullLiteral() && !resolved.isKnown()) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_PROPERTY_RESOLUTION",
                        "Cannot resolve property " + property.property() + " on " + receiverType.javaType() + "."
                ));
            }
            return;
        }
        if (ast instanceof ArrayLiteralExpression arrayLiteral) {
            arrayLiteral.elements().forEach(element -> validateExpressionSemantics(element, context, rawExpression));
            return;
        }
        if (ast instanceof ArrayAccessExpression arrayAccess) {
            validateExpressionSemantics(arrayAccess.receiver(), context, rawExpression);
            validateExpressionSemantics(arrayAccess.index(), context, rawExpression);
            TypeGuess receiverType = arrayAccess.receiver().resolvedType().isKnown()
                    ? arrayAccess.receiver().resolvedType()
                    : inferExpressionType(arrayAccess.receiver().source(), context);
            TypeGuess indexType = arrayAccess.index().resolvedType().isKnown()
                    ? arrayAccess.index().resolvedType()
                    : inferExpressionType(arrayAccess.index().source(), context);
            if (receiverType.isKnown() && !receiverType.isNullLiteral() && !receiverType.javaType().endsWith("[]")) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_ARRAY_ACCESS_TYPE",
                        "Array access requires an array receiver."
                ));
            }
            if (indexType.isKnown() && !isArrayIndexType(indexType)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_ARRAY_INDEX_TYPE",
                        "Array indexes must be int-compatible."
                ));
            }
            return;
        }
        if (ast instanceof IdentifierExpression identifier && !identifier.resolvedType().isKnown()) {
            if (!identifier.name().equals("this")
                    && !identifier.name().equals("super")
                    && context.isLocalDeclaredLaterInBlock(identifier.name())) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        SourceLocations.columnOfIdentifier(
                                context.unit.source(),
                                context.currentLine,
                                identifier.name(),
                                context.currentColumn),
                        identifier.name().length(),
                        "AFFOGATO_USE_BEFORE_INIT",
                        "Variable '" + identifier.name() + "' is used before it is declared."
                ));
            } else if (!identifier.name().equals("this")
                    && !identifier.name().equals("super")
                    && !context.identifierResolvesAsMember(identifier.name())
                    && classSymbol(identifier.name(), context.unit) == null
                    && !context.javaResolver.typeExists(identifier.name(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        SourceLocations.columnOfIdentifier(
                                context.unit.source(),
                                context.currentLine,
                                identifier.name(),
                                context.currentColumn),
                        identifier.name().length(),
                        "AFFOGATO_IDENTIFIER_RESOLUTION",
                        "Cannot resolve identifier " + identifier.name() + "."
                ));
            }
        }
    }

    // Within an extension function body, the receiver is referenced as `this`. The generated holder method
    // takes the receiver as a synthetic first parameter named `$this`, so every standalone `this` token is
    // rewritten to `$this`. String literals are skipped so embedded text is untouched.
    private String transformReceiverThis(String expression, MethodContext context) {
        if (context.receiverType == null || expression.indexOf("this") < 0) {
            return expression;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '"') {
                int end = stringLiteralEnd(expression, i);
                out.append(expression, i, end);
                i = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = readIdentifierEnd(expression, i);
                String word = expression.substring(i, end);
                out.append(word.equals("this") ? "$this" : word);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // Inside an extension function body, bare identifiers that name a member of the receiver type resolve to
    // the receiver (Kotlin implicit `this`). Such a bare field read or method call `member`/`member(...)` is
    // rewritten to `$this.member`. Locals/parameters (in scope), assignment targets and named-argument labels
    // (identifier immediately followed by `=`) and member accesses (preceded by `.`) are left untouched.
    private String transformImplicitReceiver(String expression, MethodContext context) {
        if (context.receiverType == null) {
            return expression;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '"') {
                int end = stringLiteralEnd(expression, i);
                out.append(expression, i, end);
                i = end;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = readIdentifierEnd(expression, i);
                String word = expression.substring(i, end);
                int prev = i - 1;
                while (prev >= 0 && Character.isWhitespace(expression.charAt(prev))) {
                    prev--;
                }
                boolean memberAccess = prev >= 0 && expression.charAt(prev) == '.';
                int next = end;
                while (next < n && Character.isWhitespace(expression.charAt(next))) {
                    next++;
                }
                char nextChar = next < n ? expression.charAt(next) : '\0';
                boolean isCall = nextChar == '(';
                boolean isAssignOrNamed = nextChar == '=' && (next + 1 >= n || expression.charAt(next + 1) != '=');
                if (!memberAccess && !isAssignOrNamed && !context.variableTypes.containsKey(word) && !word.equals("$this")) {
                    boolean rewrite = isCall ? context.receiverHasMethod(word) : context.receiverHasField(word);
                    if (rewrite) {
                        out.append("$this.").append(word);
                        i = end;
                        continue;
                    }
                }
                out.append(word);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // Rewrites Kotlin-style extension calls `receiver.method(args)` into a static call on the generated
    // holder class `Holder.method(receiver, args)`. Extension functions are dispatched statically and only
    // when no instance method (Affogato or Java) resolves for the receiver's static type. The receiver and
    // arguments are spliced in raw so the remaining transformExpression passes process them normally.
    private String transformExtensionCalls(String expression, MethodContext context) {
        if (extensionSymbols.isEmpty()) {
            return expression;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '"') {
                int end = stringLiteralEnd(expression, i);
                out.append(expression, i, end);
                i = end;
                continue;
            }
            if (c == '.') {
                int nameStart = i + 1;
                while (nameStart < n && Character.isWhitespace(expression.charAt(nameStart))) {
                    nameStart++;
                }
                if (nameStart < n && Character.isJavaIdentifierStart(expression.charAt(nameStart))) {
                    int nameEnd = readIdentifierEnd(expression, nameStart);
                    int parenIndex = nameEnd;
                    while (parenIndex < n && Character.isWhitespace(expression.charAt(parenIndex))) {
                        parenIndex++;
                    }
                    if (parenIndex < n && expression.charAt(parenIndex) == '(') {
                        int close = findMatching(expression, parenIndex, '(', ')');
                        int recvStart = receiverStartInBuffer(out);
                        if (close >= 0 && recvStart >= 0) {
                            String method = expression.substring(nameStart, nameEnd);
                            String receiver = out.substring(recvStart);
                            String receiverType = simpleTypeName(inferExpressionType(receiver, context).javaType());
                            if (!receiverType.isBlank() && !receiver.equals("super") && !receiver.equals("this")) {
                                String args = expression.substring(parenIndex + 1, close);
                                List<TypedArgument> typedArgs = typedArgumentsForInference(args, context);
                                Optional<ExtensionMatch> match = context.dispatchExtension(receiverType, method, typedArgs);
                                if (match.isPresent()) {
                                    out.delete(recvStart, out.length());
                                    out.append(match.get().symbol().holderJavaName())
                                            .append('.').append(method).append('(').append(receiver);
                                    if (!args.isBlank()) {
                                        out.append(", ").append(args);
                                    }
                                    out.append(')');
                                    i = close + 1;
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // Finds the start index of the trailing receiver expression already emitted into `buffer`, walking back
    // over identifier/dot chains, balanced (...) / [...] groups and string literals. Returns -1 when there is
    // no receiver (e.g. a leading method-style call with nothing before the dot). A preceding `new` keyword is
    // included so `new Foo().ext()` keeps its constructor.
    private int receiverStartInBuffer(StringBuilder buffer) {
        int i = buffer.length() - 1;
        while (i >= 0 && Character.isWhitespace(buffer.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return -1;
        }
        char last = buffer.charAt(i);
        if (!(Character.isJavaIdentifierPart(last) || last == ')' || last == ']' || last == '"')) {
            return -1;
        }
        int end = i;
        while (i >= 0) {
            char c = buffer.charAt(i);
            if (c == ')' || c == ']') {
                int open = matchBackward(buffer, i, c == ')' ? '(' : '[', c);
                if (open < 0) {
                    return -1;
                }
                i = open - 1;
            } else if (c == '"') {
                int open = stringStartBackward(buffer, i);
                if (open < 0) {
                    return -1;
                }
                i = open - 1;
            } else if (Character.isJavaIdentifierPart(c) || c == '.') {
                i--;
            } else {
                break;
            }
        }
        int start = i + 1;
        while (start <= end && Character.isWhitespace(buffer.charAt(start))) {
            start++;
        }
        // Pull in a preceding `new` keyword so constructor receivers stay intact.
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(buffer.charAt(before))) {
            before--;
        }
        int wordStart = before;
        while (wordStart >= 0 && Character.isJavaIdentifierPart(buffer.charAt(wordStart))) {
            wordStart--;
        }
        if (buffer.substring(wordStart + 1, before + 1).equals("new")) {
            start = wordStart + 1;
        }
        return start <= end ? start : -1;
    }

    private int matchBackward(StringBuilder buffer, int closeIndex, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        for (int index = closeIndex; index >= 0; index--) {
            char current = buffer.charAt(index);
            if (current == '"' && (index == 0 || buffer.charAt(index - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == closeChar) {
                depth++;
            } else if (current == openChar) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private int stringStartBackward(StringBuilder buffer, int closeQuoteIndex) {
        for (int index = closeQuoteIndex - 1; index >= 0; index--) {
            if (buffer.charAt(index) == '"' && (index == 0 || buffer.charAt(index - 1) != '\\')) {
                return index;
            }
        }
        return -1;
    }

    // Returns the index of the first '(' that is not inside a string literal, or -1.
    private int nextUnquotedOpenParen(String expression, int from) {
        int index = from;
        int length = expression.length();
        while (index < length) {
            char c = expression.charAt(index);
            if (c == '"') {
                index = stringLiteralEnd(expression, index);
            } else if (c == '(') {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    // Returns the index just past the closing quote of the string literal that opens at `openQuoteIndex`.
    private int stringLiteralEnd(String expression, int openQuoteIndex) {
        int index = openQuoteIndex + 1;
        while (index < expression.length()) {
            char c = expression.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (c == '"') {
                return index + 1;
            }
            index++;
        }
        return expression.length();
    }

  // Lowers interpolated string parse-tree nodes to Java concatenation, mirroring the legacy
    // transformStringInterpolation output so existing goldens stay byte-identical.
    private String transformInterpolatedStringsFromParseTree(String expression, MethodContext context, ParserRuleContext anchor) {
        AffogatoParser.ExpressionContext expressionTree = parseExpressionTree(expression);
        if (expressionTree == null) {
            return expression;
        }
        List<AffogatoParser.InterpolatedStringContext> strings = new ArrayList<>();
        collectInterpolatedStrings(expressionTree, strings);
        strings.removeIf(this::nestedInInterpolationExpression);
        if (strings.isEmpty()) {
            return expression;
        }
        strings.sort(Comparator.comparingInt((AffogatoParser.InterpolatedStringContext ctx) -> ctx.getStart().getStartIndex()).reversed());
        String result = expression;
        for (AffogatoParser.InterpolatedStringContext stringContext : strings) {
            int start = stringContext.getStart().getStartIndex();
            int end = stringContext.getStop().getStopIndex() + 1;
            String lowered = lowerInterpolatedString(stringContext, expression, context);
            result = result.substring(0, start) + lowered + result.substring(end);
        }
        return result;
    }

    private AffogatoParser.ExpressionContext parseExpressionTree(String expression) {
        try {
            AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(expression));
            SyntaxFlag flag = new SyntaxFlag();
            lexer.removeErrorListeners();
            lexer.addErrorListener(flag);
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            AffogatoParser parser = new AffogatoParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(flag);
            AffogatoParser.ExpressionContext tree = parser.expression();
            if (flag.errors || parser.getCurrentToken().getType() != Token.EOF) {
                return null;
            }
            return tree;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private void collectInterpolatedStrings(ParseTree tree, List<AffogatoParser.InterpolatedStringContext> out) {
        if (tree instanceof AffogatoParser.InterpolatedStringContext stringContext) {
            out.add(stringContext);
        }
        for (int index = 0; index < tree.getChildCount(); index++) {
            ParseTree child = tree.getChild(index);
            if (child != null) {
                collectInterpolatedStrings(child, out);
            }
        }
    }

    private boolean nestedInInterpolationExpression(AffogatoParser.InterpolatedStringContext stringContext) {
        for (ParserRuleContext parent = stringContext.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof AffogatoParser.StringPartContext) {
                return true;
            }
        }
        return false;
    }

    private String lowerInterpolatedString(AffogatoParser.InterpolatedStringContext ctx, String whole, MethodContext context) {
        StringBuilder segment = new StringBuilder();
        List<String> parts = new ArrayList<>();
        boolean interpolated = false;
        for (AffogatoParser.StringPartContext part : ctx.stringPart()) {
            if (part.STR_TEXT() != null) {
                segment.append(part.STR_TEXT().getText());
            } else if (part.STR_ESCAPE() != null) {
                segment.append(part.STR_ESCAPE().getText());
            } else if (part.STR_DOLLAR() != null) {
                segment.append('$');
            } else if (part.STR_SIMPLE_INTERP() != null) {
                interpolated = true;
                parts.add('"' + segment.toString() + '"');
                segment.setLength(0);
                parts.add('(' + part.STR_SIMPLE_INTERP().getText().substring(1) + ')');
            } else if (part.expression() != null) {
                interpolated = true;
                parts.add('"' + segment.toString() + '"');
                segment.setLength(0);
                String exprText = whole.substring(
                        part.expression().getStart().getStartIndex(),
                        part.expression().getStop().getStopIndex() + 1);
                if (exprText.isBlank()) {
                    if (context != null) {
                        diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                                "AFFOGATO_PARSE", "Empty interpolation '${}' has no expression."));
                    }
                } else {
                    String nested = transformInterpolatedStringsFromParseTree(exprText, context, null);
                    parts.add('(' + nested + ')');
                }
            }
        }
        if (!interpolated) {
            return '"' + segment.toString() + '"';
        }
        parts.add('"' + segment.toString() + '"');
        List<String> rendered = new ArrayList<>();
        for (String part : parts) {
            if (!part.equals("\"\"")) {
                rendered.add(part);
            }
        }
        if (rendered.isEmpty() || rendered.get(0).startsWith("(")) {
            rendered.add(0, "\"\"");
        }
        return String.join(" + ", rendered);
    }

    private static final class SyntaxFlag extends BaseErrorListener {
        private boolean errors;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String message, RecognitionException exception) {
            errors = true;
        }
    }

    // Rewrites interpolated string literals into Java string concatenation.
    // "Hi ${user.name}!" becomes "Hi " + (user.name) + "!"; the embedded expression text is
    // left raw so the rest of the transformExpression pipeline (property reads, etc.) processes it.
    // Supports ${expression} for arbitrary expressions and $identifier as shorthand; \$ is a literal dollar.
    private String transformStringInterpolation(String expression, MethodContext context) {
        if (expression.indexOf('$') < 0 || expression.indexOf('"') < 0) {
            return expression;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c != '"') {
                out.append(c);
                i++;
                continue;
            }

            int j = i + 1;
            StringBuilder segment = new StringBuilder();
            List<String> parts = new ArrayList<>();
            boolean interpolated = false;
            boolean closed = false;
            while (j < n) {
                char d = expression.charAt(j);
                if (d == '\\') {
                    if (j + 1 < n && expression.charAt(j + 1) == '$') {
                        segment.append('$');
                        j += 2;
                        continue;
                    }
                    segment.append(d);
                    if (j + 1 < n) {
                        segment.append(expression.charAt(j + 1));
                        j += 2;
                    } else {
                        j++;
                    }
                    continue;
                }
                if (d == '"') {
                    closed = true;
                    j++;
                    break;
                }
                boolean braceForm = d == '$' && j + 1 < n && expression.charAt(j + 1) == '{';
                boolean idForm = d == '$' && j + 1 < n && Character.isJavaIdentifierStart(expression.charAt(j + 1));
                String exprText = null;
                int nextIndex = j;
                if (braceForm) {
                    int braceEnd = findMatchingBraceSkippingStrings(expression, j + 1);
                    if (braceEnd >= 0) {
                        exprText = expression.substring(j + 2, braceEnd);
                        nextIndex = braceEnd + 1;
                    }
                } else if (idForm) {
                    // '$' is a legal Java identifier part, so without this guard "$a$b" would read the
                    // whole "a$b" as one name. The simple form stops at the next '$' so adjacent
                    // interpolations split correctly; a name containing '$' must use the ${ } form.
                    int idEnd = j + 1;
                    while (idEnd < n
                            && expression.charAt(idEnd) != '$'
                            && Character.isJavaIdentifierPart(expression.charAt(idEnd))) {
                        idEnd++;
                    }
                    exprText = expression.substring(j + 1, idEnd);
                    nextIndex = idEnd;
                }
                if (exprText != null && exprText.isBlank()) {
                    // Empty "${}" has no expression; emitting it would produce invalid Java ("" + ()).
                    if (context != null) {
                        diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                                "AFFOGATO_PARSE", "Empty interpolation '${}' has no expression."));
                    }
                    segment.append(expression, j, nextIndex);
                    j = nextIndex;
                    continue;
                }
                if (exprText != null) {
                    interpolated = true;
                    parts.add('"' + segment.toString() + '"');
                    segment.setLength(0);
                    parts.add('(' + exprText + ')');
                    j = nextIndex;
                    continue;
                }
                segment.append(d);
                j++;
            }

            if (!closed) {
                out.append(expression, i, j);
                i = j;
                continue;
            }
            if (!interpolated) {
                out.append('"').append(segment).append('"');
                i = j;
                continue;
            }
            parts.add('"' + segment.toString() + '"');

            List<String> rendered = new ArrayList<>();
            for (String part : parts) {
                if (!part.equals("\"\"")) {
                    rendered.add(part);
                }
            }
            if (rendered.isEmpty() || rendered.get(0).startsWith("(")) {
                rendered.add(0, "\"\"");
            }
            out.append(String.join(" + ", rendered));
            i = j;
        }
        return out.toString();
    }

    private int findMatchingBraceSkippingStrings(String s, int openIndex) {
        int depth = 0;
        int i = openIndex;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                while (i < s.length()) {
                    if (s.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (s.charAt(i) == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /**
     * Rewrites sized-array allocations {@code new T[](size)} into Java {@code new T[size]}. The size
     * expression is transformed recursively so nested allocations resolve too. Array literals
     * ({@code new T[]{...}}) are produced later by {@link #transformArrayLiteral} and are untouched.
     */
    private String transformArrayConstruction(String expression) {
        Matcher matcher = Pattern.compile("new\\s+([A-Za-z_$][\\w$.]*)\\s*\\[\\]\\s*\\(").matcher(expression);
        StringBuilder out = new StringBuilder();
        int last = 0;
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            int openParen = matcher.end() - 1;
            int close = findMatching(expression, openParen, '(', ')');
            if (close < 0) {
                searchFrom = matcher.end();
                continue;
            }
            String size = transformArrayConstruction(expression.substring(openParen + 1, close));
            out.append(expression, last, matcher.start());
            out.append("new ").append(matcher.group(1)).append('[').append(size).append(']');
            last = close + 1;
            searchFrom = close + 1;
        }
        out.append(expression.substring(last));
        return out.toString();
    }

    private String transformArrayLiteral(String expression, MethodContext context) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current != '[') {
                out.append(current);
                index++;
                continue;
            }
            boolean isAccess = index > 0 && (Character.isJavaIdentifierPart(expression.charAt(index - 1))
                    || expression.charAt(index - 1) == ')'
                    || expression.charAt(index - 1) == ']');
            if (isAccess) {
                out.append(current);
                index++;
                continue;
            }
            int close = findMatching(expression, index, '[', ']');
            if (close < 0) {
                out.append(current);
                index++;
                continue;
            }
            String contents = expression.substring(index + 1, close);
            List<String> elements = splitTopLevel(contents, ',').stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            String elementType = expectedArrayElementType != null
                    ? expectedArrayElementType
                    : inferArrayElementType(elements, context);
            // Recurse so nested literals are lowered too: `[[1, 2], [3, 4]]` becomes
            // `new int[][]{new int[]{1, 2}, new int[]{3, 4}}` rather than leaking raw inner `[...]`. The
            // outer binding's target type applies only to this literal, so it is cleared for the recursion
            // (a nested literal — including one inside an element like `Person([1, 2])` — is inferred).
            String savedExpected = expectedArrayElementType;
            expectedArrayElementType = null;
            String loweredContents = transformArrayLiteral(contents, context);
            expectedArrayElementType = savedExpected;
            out.append("new ").append(elementType).append("[]{").append(loweredContents).append("}");
            index = close + 1;
        }
        return out.toString();
    }

    private String inferArrayElementType(List<String> elements, MethodContext context) {
        if (elements.isEmpty()) {
            return "Object";
        }
        // All elements must classify to the same numeric literal type (via the shared classifier);
        // otherwise fall back to a uniform String/boolean array, or Object for anything mixed.
        String firstNumeric = numericLiteralType(elements.get(0));
        if (firstNumeric != null && elements.stream().allMatch(e -> firstNumeric.equals(numericLiteralType(e)))) {
            return firstNumeric;
        }
        boolean allString = elements.stream().allMatch(e -> e.startsWith("\""));
        if (allString) {
            return "String";
        }
        boolean allBoolean = elements.stream().allMatch(e -> e.equals("true") || e.equals("false"));
        if (allBoolean) {
            return "boolean";
        }
        // Object elements (e.g. constructor calls): if every element infers to the same known type, use
        // it so `[Person(...), Person(...)]` becomes `Person[]` rather than a too-wide `Object[]`.
        if (context != null) {
            TypeGuess first = inferExpressionType(elements.get(0), context);
            if (first.isKnown() && !first.isNullLiteral()) {
                boolean uniform = elements.stream().allMatch(e -> {
                    TypeGuess elementType = inferExpressionType(e, context);
                    return elementType.isKnown() && elementType.javaType().equals(first.javaType());
                });
                if (uniform) {
                    return first.javaType();
                }
            }
        }
        return "Object";
    }

    private void validateMethodCalls(String expression, MethodContext context) {
        int index = 0;
        while (index < expression.length()) {
            int open = nextUnquotedOpenParen(expression, index);
            if (open < 0) {
                return;
            }
            int close = findMatching(expression, open, '(', ')');
            if (close < 0) {
                return;
            }
            String callName = callNameBefore(expression, open);
            List<TypedArgument> arguments = typedArgumentsForInference(expression.substring(open + 1, close), context);
            String receiver = receiverBeforeMethod(expression, open);
            if (!receiver.isBlank()) {
                    String methodName = callName.substring(callName.lastIndexOf('.') + 1);
                    TypeGuess receiverType = inferExpressionType(receiver, context);
                    if (receiverType.isKnown()) {
                        TypeGuess returnType = context.returnTypeForReceiverType(receiverType.javaType(), methodName, arguments);
                        if (!returnType.isKnown()) {
                            diagnostics.add(error(
                                    context.unit.sourceFile(),
                                context.currentLine,
                                context.currentColumn,
                                "AFFOGATO_CALL_RESOLUTION",
                                "Cannot resolve call " + methodName + " on " + receiverType.javaType() + "."
                        ));
                    }
                    index = close + 1;
                    continue;
                }
            }
            if (shouldValidateCall(callName, expression, open, context)) {
                TypeGuess returnType = context.returnType(callName, arguments);
                if (!returnType.isKnown()) {
                    diagnostics.add(error(
                            context.unit.sourceFile(),
                            context.currentLine,
                            context.currentColumn,
                            "AFFOGATO_CALL_RESOLUTION",
                            "Cannot resolve call " + callName + "."
                    ));
                }
            }
            index = close + 1;
        }
    }

    private boolean shouldValidateCall(String callName, String expression, int openIndex, MethodContext context) {
        if (callName.isBlank() || callName.equals("not") || callName.equals("super") || callName.equals("this")) {
            return false;
        }
        // 'println' is the magic built-in unless the current class or scope defines it.
        if (callName.equals("println")
                && !context.hasCurrentMethod("println")
                && !context.variableTypes.containsKey("println")) {
            return false;
        }
        if (isPrecededByNew(expression, openIndex - callName.length())) {
            return false;
        }
        String simpleName = callName;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0) {
            String owner = callName.substring(0, dot);
            if (context.variableTypes.containsKey(owner)) {
                return true;
            }
            String firstOwnerPart = owner.contains(".") ? owner.substring(0, owner.indexOf('.')) : owner;
            return !firstOwnerPart.isBlank() && Character.isUpperCase(firstOwnerPart.charAt(0));
        }
        return context.hasCurrentMethod(callName)
                || context.hasStaticImport(callName)
                || Character.isLowerCase(callName.charAt(0));
    }

    private boolean isPrecededByNew(String expression, int startIndex) {
        int previous = startIndex - 1;
        while (previous >= 0 && Character.isWhitespace(expression.charAt(previous))) {
            previous--;
        }
        int end = previous + 1;
        while (previous >= 0 && Character.isJavaIdentifierPart(expression.charAt(previous))) {
            previous--;
        }
        return expression.substring(previous + 1, end).equals("new");
    }

    private void validateExplicitConstructorCalls(String expression, MethodContext context) {
        int index = 0;
        while (index < expression.length()) {
            int newIndex = expression.indexOf("new", index);
            if (newIndex < 0) {
                return;
            }
            if (!isWordAt(expression, newIndex, "new")) {
                index = newIndex + 3;
                continue;
            }
            int typeStart = newIndex + 3;
            while (typeStart < expression.length() && Character.isWhitespace(expression.charAt(typeStart))) {
                typeStart++;
            }
            if (typeStart >= expression.length() || !Character.isJavaIdentifierStart(expression.charAt(typeStart))) {
                index = newIndex + 3;
                continue;
            }
            int typeEnd = readExplicitConstructorTypeEnd(expression, typeStart);
            if (typeEnd <= typeStart || typeEnd >= expression.length() || expression.charAt(typeEnd) != '(') {
                index = typeEnd <= typeStart ? newIndex + 3 : typeEnd;
                continue;
            }
            int close = findMatching(expression, typeEnd, '(', ')');
            if (close < 0) {
                return;
            }
            String typeName = expression.substring(typeStart, typeEnd).trim();
            validateConstructorCall(typeName, typeName, expression.substring(typeEnd + 1, close), context);
            index = close + 1;
        }
    }

    private boolean isWordAt(String expression, int index, String word) {
        if (!expression.startsWith(word, index)) {
            return false;
        }
        int before = index - 1;
        int after = index + word.length();
        return (before < 0 || !Character.isJavaIdentifierPart(expression.charAt(before)))
                && (after >= expression.length() || !Character.isJavaIdentifierPart(expression.charAt(after)));
    }

    private void validateCasts(String expression, MethodContext context) {
        Matcher matcher = AS_CAST.matcher(expression);
        while (matcher.find()) {
            TypeGuess source = inferExpressionType(matcher.group(1), context);
            String targetType = stripNullableSuffix(matcher.group(2));
            if (source.isKnown()
                    && !source.isNullLiteral()
                    && !source.isLambda()
                    && !context.javaResolver.castPossible(source, targetType, context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CAST_TYPE",
                        "Cannot cast " + source.javaType() + " to " + targetType + "."
                ));
            }
        }
    }

    private String transformTypedLambda(String expression) {
        Matcher matcher = SIMPLE_TYPED_LAMBDA.matcher(expression.trim());
        if (!matcher.matches()) {
            return expression;
        }
        return "(" + matcher.group(2).trim() + " " + matcher.group(1).trim() + ") -> " + matcher.group(3).trim();
    }

    private String transformNamedArguments(String expression, MethodContext context) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current != '(') {
                out.append(current);
                index++;
                continue;
            }

            int close = findMatching(expression, index, '(', ')');
            if (close < 0) {
                out.append(current);
                index++;
                continue;
            }

            String callName = callNameBefore(expression, index);
            String args = expression.substring(index + 1, close);
            if (!callName.isBlank() && hasNamedArguments(args)) {
                out.append('(').append(reorderNamedArguments(callName, args, context)).append(')');
            } else {
                out.append('(').append(transformNamedArguments(args, context)).append(')');
            }
            index = close + 1;
        }
        return out.toString();
    }

    private String reorderNamedArguments(String callName, String args, MethodContext context) {
        List<String> parts = splitTopLevel(args, ',');
        List<TypedArgument> arguments = new ArrayList<>();
        for (String part : parts) {
            int equals = namedArgumentEquals(part);
            if (equals > 0) {
                String name = part.substring(0, equals).trim();
                String value = part.substring(equals + 1).trim();
                String transformed = transformExpression(value, context);
                arguments.add(new TypedArgument(name, transformed, inferExpressionType(value, context), expressionAst(value, context)));
            } else if (!part.trim().isBlank()) {
                String value = part.trim();
                String transformed = transformExpression(value, context);
                arguments.add(new TypedArgument("", transformed, inferExpressionType(value, context), expressionAst(value, context)));
            }
        }

        Optional<ResolvedArguments> resolved = context.resolveArguments(callName, arguments);
        if (resolved.isPresent()) {
            return String.join(", ", resolved.get().expressions());
        } else {
            String failure = context.resolutionFailure();
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_NAMED_ARGS",
                    failure.isBlank()
                            ? "Cannot resolve named arguments for call " + callName + ". Compile Java dependencies with -parameters or use a Affogato declaration."
                            : failure
            ));
            List<String> values = new ArrayList<>();
            arguments.stream()
                    .filter(argument -> argument.name().isBlank())
                    .map(TypedArgument::expression)
                    .forEach(values::add);
            arguments.stream()
                    .filter(argument -> !argument.name().isBlank())
                    .map(TypedArgument::expression)
                    .forEach(values::add);
            return String.join(", ", values);
        }
    }

    private boolean hasNamedArguments(String args) {
        return splitTopLevel(args, ',').stream().anyMatch(part -> namedArgumentEquals(part) > 0);
    }

    private int namedArgumentEquals(String part) {
        int angle = 0;
        int paren = 0;
        int brace = 0;
        for (int index = 0; index < part.length(); index++) {
            char current = part.charAt(index);
            if (current == '<') {
                angle++;
            } else if (current == '>') {
                angle = Math.max(0, angle - 1);
            } else if (current == '(') {
                paren++;
            } else if (current == ')') {
                paren = Math.max(0, paren - 1);
            } else if (current == '{') {
                brace++;
            } else if (current == '}') {
                brace = Math.max(0, brace - 1);
            } else if (current == '=' && angle == 0 && paren == 0 && brace == 0) {
                char previous = index > 0 ? part.charAt(index - 1) : '\0';
                char next = index + 1 < part.length() ? part.charAt(index + 1) : '\0';
                if (previous != '=' && previous != '!' && previous != '<' && previous != '>' && next != '=') {
                    return index;
                }
            }
        }
        return -1;
    }

    private String transformNot(String expression) {
        return expression.replaceAll("\\bnot\\s*\\(", "!(");
    }

    // `x is T` → `x instanceof T`. Java's instanceof shares Affogato's relational precedence, so the
    // keyword is replaced in place and only the trailing type needs erasing — the operand can be any
    // expression (call, member chain, array access), unlike the old identifier-only regex.
    private String transformInstanceof(String expression) {
        return transformIsAs(expression, "is", false);
    }

    // `x as T` → `((T) x)`. A Java cast binds tighter than its operand, so the left operand is recovered
    // from the already-emitted output via receiverStartInBuffer (handling calls, chains, arrays, parens),
    // not assumed to be a single identifier.
    private String transformCast(String expression) {
        return transformIsAs(expression, "as", true);
    }

    private String transformIsAs(String expression, String keyword, boolean cast) {
        if (!expression.contains(keyword)) {
            return expression;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '"') {
                int end = stringLiteralEnd(expression, i);
                out.append(expression, i, end);
                i = end;
                continue;
            }
            if (isWordAt(expression, i, keyword) && lastNonWhitespace(out) != '.') {
                int typeStart = i + keyword.length();
                while (typeStart < n && Character.isWhitespace(expression.charAt(typeStart))) {
                    typeStart++;
                }
                int typeEnd = readReferenceTypeEnd(expression, typeStart);
                boolean typeOk = typeEnd > typeStart
                        && Character.isJavaIdentifierStart(expression.charAt(typeStart));
                int recvStart = cast ? receiverStartInBuffer(out) : 0;
                if (typeOk && recvStart >= 0) {
                    String type = expression.substring(typeStart, typeEnd).trim();
                    if (type.endsWith("?")) {
                        type = type.substring(0, type.length() - 1);
                    }
                    if (cast) {
                        String operand = out.substring(recvStart).trim();
                        out.delete(recvStart, out.length());
                        out.append("((").append(type).append(") ").append(operand).append(')');
                    } else {
                        if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                            out.append(' ');
                        }
                        out.append("instanceof ").append(eraseTypeArguments(type));
                    }
                    i = typeEnd;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private String eraseTypeArguments(String type) {
        int generic = type.indexOf('<');
        return generic < 0 ? type : type.substring(0, generic);
    }

    // Reads a reference type starting at `start`: qualified name, optional balanced <...> generics,
    // trailing [] array suffixes and a nullable `?`. Used to bound the type after `is`/`as`.
    private int readReferenceTypeEnd(String expression, int start) {
        int i = start;
        int n = expression.length();
        while (i < n && (Character.isJavaIdentifierPart(expression.charAt(i)) || expression.charAt(i) == '.')) {
            i++;
        }
        if (i < n && expression.charAt(i) == '<') {
            int angle = 0;
            do {
                char c = expression.charAt(i);
                if (c == '<') {
                    angle++;
                } else if (c == '>') {
                    angle--;
                }
                i++;
            } while (i < n && angle > 0);
        }
        while (i + 1 < n && expression.charAt(i) == '[' && expression.charAt(i + 1) == ']') {
            i += 2;
        }
        if (i < n && expression.charAt(i) == '?') {
            i++;
        }
        return i;
    }

    private char lastNonWhitespace(StringBuilder buffer) {
        int i = buffer.length() - 1;
        while (i >= 0 && Character.isWhitespace(buffer.charAt(i))) {
            i--;
        }
        return i >= 0 ? buffer.charAt(i) : '\0';
    }

    private String transformTypeConstruction(String expression, MethodContext context) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < expression.length()) {
            if (!isUppercaseIdentifierStart(expression, index) || isPrecededByNewOrDot(expression, index)) {
                out.append(expression.charAt(index));
                index++;
                continue;
            }

            int nameEnd = readTypeExpressionEnd(expression, index);
            if (nameEnd <= index || nameEnd >= expression.length() || expression.charAt(nameEnd) != '(') {
                out.append(expression.charAt(index));
                index++;
                continue;
            }
            String typeName = expression.substring(index, nameEnd);
            String implementation = constructorImplementation(typeName);
            int close = findMatching(expression, nameEnd, '(', ')');
            if (classSymbol(typeName, context.unit) == null && !context.javaResolver.typeExists(implementation, context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TYPE_RESOLUTION",
                        "Cannot resolve type " + typeName + "."
                ));
            } else if (close >= 0) {
                validateConstructorCall(typeName, implementation, expression.substring(nameEnd + 1, close), context);
            }
            out.append("new ").append(implementation).append('(');
            index = nameEnd + 1;
        }
        return out.toString();
    }

    private void validateConstructorCall(
            String displayType,
            String resolutionType,
            String args,
            MethodContext context
    ) {
        if (classSymbol(displayType, context.unit) == null && !context.javaResolver.typeExists(resolutionType, context.unit)) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    "AFFOGATO_TYPE_RESOLUTION",
                    "Cannot resolve type " + displayType + "."
            ));
            return;
        }

        ClassSymbol affogatoTarget = classSymbol(displayType, context.unit);
        List<TypedArgument> arguments = typedArgumentsForInference(args, context);
        if (affogatoTarget != null) {
            Optional<ResolvedArguments> resolved = context.resolveArguments(displayType, arguments);
            if (resolved.isPresent()) {
                return;
            }
        } else {
            Optional<ResolvedArguments> resolved = context.javaResolver.resolveConstructorArguments(resolutionType, arguments, context.unit);
            if (resolved.isPresent()) {
                return;
            }
            if (context.javaResolver.lastResolutionAmbiguous()) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CONSTRUCTOR_RESOLUTION",
                        "Ambiguous overload for constructor " + displayType + "."
                ));
                return;
            }
        }
        String failure = context.resolutionFailure();
        diagnostics.add(error(
                context.unit.sourceFile(),
                context.currentLine,
                context.currentColumn,
                "AFFOGATO_CONSTRUCTOR_RESOLUTION",
                failure.isBlank()
                        ? "Cannot resolve constructor " + displayType + "."
                        : failure.replace("call " + resolutionType, "constructor " + displayType)
        ));
    }

    private String constructorImplementation(String typeName) {
        if (typeName.startsWith("Map<")) {
            return "java.util.HashMap" + typeName.substring("Map".length());
        }
        if (typeName.startsWith("List<")) {
            return "java.util.ArrayList" + typeName.substring("List".length());
        }
        if (typeName.startsWith("Set<")) {
            return "java.util.HashSet" + typeName.substring("Set".length());
        }
        return typeName;
    }

    private String transformPropertyReads(String expression, MethodContext context) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < expression.length()) {
            if (!Character.isJavaIdentifierStart(expression.charAt(index))) {
                // A `.member` whose receiver is not a bare identifier (a call/cast/index/string result
                // already emitted into `out`) is lowered here, since the identifier branch only handles
                // `variable.member`. The receiver's type is inferred from the emitted text.
                if (expression.charAt(index) == '.') {
                    int lowered = lowerBufferReceiverProperty(out, expression, index, context);
                    if (lowered >= 0) {
                        index = lowered;
                        continue;
                    }
                }
                out.append(expression.charAt(index));
                index++;
                continue;
            }
            int ownerStart = index;
            int ownerEnd = readIdentifierEnd(expression, ownerStart);
            if (ownerEnd >= expression.length() || expression.charAt(ownerEnd) != '.') {
                out.append(expression, ownerStart, ownerEnd);
                index = ownerEnd;
                continue;
            }
            int propertyStart = ownerEnd + 1;
            if (propertyStart >= expression.length() || !Character.isJavaIdentifierStart(expression.charAt(propertyStart))) {
                out.append(expression, ownerStart, ownerEnd + 1);
                index = ownerEnd + 1;
                continue;
            }
            int propertyEnd = readIdentifierEnd(expression, propertyStart);
            boolean methodCall = propertyEnd < expression.length() && expression.charAt(propertyEnd) == '(';
            String owner = expression.substring(ownerStart, ownerEnd);
            String property = expression.substring(propertyStart, propertyEnd);
            String ownerType = context.variableTypes.get(owner);
            PropertyHop hop = (methodCall || ownerType == null)
                    ? null
                    : resolvePropertyHopOnType(ownerType, property, context);
            if (hop == null) {
                if (!methodCall && ownerType != null) {
                    diagnostics.add(error(
                            context.unit.sourceFile(),
                            context.currentLine,
                            context.currentColumn,
                            "AFFOGATO_PROPERTY_RESOLUTION",
                            "Cannot resolve property " + property + " on " + ownerType + "."
                    ));
                }
                out.append(expression, ownerStart, propertyEnd);
                index = propertyEnd;
                continue;
            }
            // First hop resolved; emit it and keep lowering deeper `.member` hops on the resulting type so
            // a chain like `a.b.c` becomes `a.getB().getC()` instead of leaking a raw field access.
            out.append(owner).append('.').append(hop.accessor());
            if (hop.call()) {
                out.append("()");
            }
            String currentType = hop.resultType().isKnown() ? hop.resultType().javaType() : null;
            index = lowerPropertyChain(out, expression, propertyEnd, currentType, context);
        }
        return out.toString();
    }

    // Lowers a `.member` read whose receiver is the call/cast/index/string expression already emitted into
    // `out` (e.g. `make().name`, `((T) o).name`, `arr[0].name`). Returns the index just past the lowered
    // chain, or -1 when this `.` is not a lowerable property read (method call, unknown receiver type, or
    // unresolved member) so the caller emits it verbatim.
    private int lowerBufferReceiverProperty(StringBuilder out, String expression, int dotIndex, MethodContext context) {
        int propertyStart = dotIndex + 1;
        if (propertyStart >= expression.length() || !Character.isJavaIdentifierStart(expression.charAt(propertyStart))) {
            return -1;
        }
        int propertyEnd = readIdentifierEnd(expression, propertyStart);
        if (propertyEnd < expression.length() && expression.charAt(propertyEnd) == '(') {
            return -1; // method call
        }
        int receiverStart = receiverStartInBuffer(out);
        if (receiverStart < 0) {
            return -1;
        }
        TypeGuess receiverType = inferExpressionType(out.substring(receiverStart), context);
        if (!receiverType.isKnown() || receiverType.isNullLiteral()) {
            return -1;
        }
        PropertyHop hop = resolvePropertyHopOnType(receiverType.javaType(), expression.substring(propertyStart, propertyEnd), context);
        if (hop == null) {
            return -1;
        }
        out.append('.').append(hop.accessor());
        if (hop.call()) {
            out.append("()");
        }
        String currentType = hop.resultType().isKnown() ? hop.resultType().javaType() : null;
        return lowerPropertyChain(out, expression, propertyEnd, currentType, context);
    }

    // Lowers a run of `.member` field reads starting at `cursor`, tracking `currentType` and appending each
    // accessor to `out`. Stops at a method call, a non-field member, or an unknown receiver type. Returns
    // the index just past the last lowered hop.
    private int lowerPropertyChain(StringBuilder out, String expression, int cursor, String currentType, MethodContext context) {
        while (currentType != null && cursor < expression.length() && expression.charAt(cursor) == '.') {
            int nextStart = cursor + 1;
            if (nextStart >= expression.length() || !Character.isJavaIdentifierStart(expression.charAt(nextStart))) {
                break;
            }
            int nextEnd = readIdentifierEnd(expression, nextStart);
            if (nextEnd < expression.length() && expression.charAt(nextEnd) == '(') {
                break; // method call — leave it for normal call handling
            }
            PropertyHop nextHop = resolvePropertyHopOnType(currentType, expression.substring(nextStart, nextEnd), context);
            if (nextHop == null) {
                break;
            }
            out.append('.').append(nextHop.accessor());
            if (nextHop.call()) {
                out.append("()");
            }
            currentType = nextHop.resultType().isKnown() ? nextHop.resultType().javaType() : null;
            cursor = nextEnd;
        }
        return cursor;
    }

    private record PropertyHop(String accessor, boolean call, TypeGuess resultType) {
    }

    // Resolves a single `.property` read on a known owner type to its Java accessor, mirroring the
    // four-path order used for the first hop: Affogato field (getter, or direct for records), array
    // `length`, Java getter, then a directly-accessible Java field. Returns null when unresolvable.
    private PropertyHop resolvePropertyHopOnType(String ownerType, String property, MethodContext context) {
        FieldSymbol field = fieldForOwnerType(ownerType, property, context);
        if (field != null) {
            ClassSymbol ownerSymbol = classSymbol(ownerType, context.unit);
            String accessor = ownerSymbol != null && ownerSymbol.isRecord() ? property : getterName(property, field.type());
            return new PropertyHop(accessor, true, TypeGuess.of(field.type().javaType()));
        }
        if (isArrayLengthAccess(ownerType, property)) {
            return new PropertyHop(property, false, TypeGuess.of("int"));
        }
        if (context.javaResolver.getterExists(ownerType, property, context.unit)) {
            String getter = context.javaResolver.getterInvocationName(ownerType, property, context.unit)
                    .orElse(getterName(property, TypeRef.unspecified("Object")));
            TypeGuess resultType = context.javaResolver.getterReturnType(ownerType, property, context.unit)
                    .orElse(TypeGuess.unknown());
            return new PropertyHop(getter, true, resultType);
        }
        if (context.javaResolver.fieldExists(ownerType, property, context.unit)) {
            TypeGuess resultType = context.javaResolver.fieldType(ownerType, property, context.unit)
                    .orElse(TypeGuess.unknown());
            return new PropertyHop(property, false, resultType);
        }
        return null;
    }

    private boolean isArrayLengthAccess(String ownerType, String property) {
        return ownerType.endsWith("[]") && property.equals("length");
    }

    private FieldSymbol resolveField(String owner, String property, MethodContext context) {
        String type = context.variableTypes.get(owner);
        if (type == null) {
            return null;
        }
        ClassSymbol symbol = classSymbol(type, context.unit);
        if (symbol == null) {
            return null;
        }
        return symbol.fields.get(property);
    }

    private ClassSymbol classSymbol(String type, CompilationUnit unit) {
        return classSymbols.lookup(type, unit);
    }

    // ── FLOW ANALYSIS ────────────────────────────────────────────────────────────

    private boolean blockExits(AffogatoParser.BlockContext block) {
        return flow.blockExits(block);
    }

    private static final class FlowAnalyzer {
        private final List<AffogatoDiagnostic> diagnostics;

        FlowAnalyzer(List<AffogatoDiagnostic> diagnostics) {
            this.diagnostics = diagnostics;
        }

        boolean blockExits(AffogatoParser.BlockContext block) {
            List<AffogatoParser.StatementContext> statements = block.statement();
            for (int index = statements.size() - 1; index >= 0; index--) {
                AffogatoParser.StatementContext statement = statements.get(index);
                if (isPureSeparator(statement)) {
                    continue;
                }
                return statementExits(statement);
            }
            return false;
        }

        /**
         * A {@code statement} matches the bare {@code separators} alternative (a blank line or lone semicolon)
         * only when it has no real child. Real statements such as {@code block}, {@code tryStatement} and
         * {@code switchStatement} can carry a trailing {@code separators?}, so checking {@code separators() != null}
         * alone wrongly skips them when they are the last statement of a block.
         */
        private boolean isPureSeparator(AffogatoParser.StatementContext statement) {
            return statement.separators() != null
                    && statement.block() == null
                    && statement.guardStatement() == null
                    && statement.ifStatement() == null
                    && statement.forStatement() == null
                    && statement.whileStatement() == null
                    && statement.tryStatement() == null
                    && statement.switchStatement() == null
                    && statement.returnStatement() == null
                    && statement.throwStatement() == null
                    && statement.breakStatement() == null
                    && statement.continueStatement() == null
                    && statement.localVarDecl() == null
                    && statement.expressionStatement() == null;
        }

        boolean blockStopsControl(AffogatoParser.BlockContext block) {
            List<AffogatoParser.StatementContext> statements = block.statement();
            for (int index = statements.size() - 1; index >= 0; index--) {
                AffogatoParser.StatementContext statement = statements.get(index);
                if (isPureSeparator(statement)) {
                    continue;
                }
                return statementStopsControl(statement);
            }
            return false;
        }

        boolean statementExits(AffogatoParser.StatementContext statement) {
            if (statement.returnStatement() != null || statement.throwStatement() != null) {
                return true;
            }
            if (statement.block() != null) {
                return blockExits(statement.block());
            }
            if (statement.ifStatement() != null) {
                return ifExits(statement.ifStatement());
            }
            if (statement.tryStatement() != null) {
                return tryExits(statement.tryStatement());
            }
            if (statement.whileStatement() != null) {
                return whileExits(statement.whileStatement());
            }
            return false;
        }

        private boolean statementStopsControl(AffogatoParser.StatementContext statement) {
            if (statement.returnStatement() != null
                    || statement.throwStatement() != null
                    || statement.breakStatement() != null
                    || statement.continueStatement() != null) {
                return true;
            }
            if (statement.block() != null) {
                return blockStopsControl(statement.block());
            }
            if (statement.ifStatement() != null) {
                return ifStopsControl(statement.ifStatement());
            }
            if (statement.tryStatement() != null) {
                return tryStopsControl(statement.tryStatement());
            }
            return false;
        }

        private boolean whileExits(AffogatoParser.WhileStatementContext whileStatement) {
            String condText = whileStatement.condition().getText().trim()
                    .replaceAll("[()\\s]", "");
            return condText.equals("true") && blockExits(whileStatement.block());
        }

        private boolean tryExits(AffogatoParser.TryStatementContext tryStatement) {
            if (tryStatement.finallyClause() != null && blockExits(tryStatement.finallyClause().block())) {
                return true;
            }
            if (!blockExits(tryStatement.block())) {
                return false;
            }
            return tryStatement.catchClause().stream().allMatch(clause -> blockExits(clause.block()));
        }

        private boolean ifExits(AffogatoParser.IfStatementContext ifStatement) {
            if (ifStatement.ELSE() == null || ifStatement.block().isEmpty()) {
                return false;
            }
            boolean thenExits = blockExits(ifStatement.block(0));
            boolean elseExits;
            if (ifStatement.ifStatement() != null) {
                elseExits = ifExits(ifStatement.ifStatement());
            } else if (ifStatement.block().size() > 1) {
                elseExits = blockExits(ifStatement.block(1));
            } else {
                elseExits = false;
            }
            return thenExits && elseExits;
        }

        private boolean tryStopsControl(AffogatoParser.TryStatementContext tryStatement) {
            if (tryStatement.finallyClause() != null && blockStopsControl(tryStatement.finallyClause().block())) {
                return true;
            }
            if (!blockStopsControl(tryStatement.block())) {
                return false;
            }
            return tryStatement.catchClause().stream().allMatch(clause -> blockStopsControl(clause.block()));
        }

        private boolean ifStopsControl(AffogatoParser.IfStatementContext ifStatement) {
            if (ifStatement.ELSE() == null || ifStatement.block().isEmpty()) {
                return false;
            }
            boolean thenStops = blockStopsControl(ifStatement.block(0));
            boolean elseStops;
            if (ifStatement.ifStatement() != null) {
                elseStops = ifStopsControl(ifStatement.ifStatement());
            } else if (ifStatement.block().size() > 1) {
                elseStops = blockStopsControl(ifStatement.block(1));
            } else {
                elseStops = false;
            }
            return thenStops && elseStops;
        }

        void checkUnreachable(Path sourceFile, AffogatoParser.BlockContext block) {
            boolean exited = false;
            for (AffogatoParser.StatementContext stmt : block.statement()) {
                if (isPureSeparator(stmt)) {
                    continue;
                }
                if (exited) {
                    int line = stmt.getStart().getLine();
                    int column = stmt.getStart().getCharPositionInLine() + 1;
                    int length = statementHighlightLength(stmt);
                    diagnostics.add(new AffogatoDiagnostic(
                            AffogatoDiagnostic.Severity.WARNING,
                            "AFFOGATO_UNREACHABLE",
                            "Unreachable statement.",
                            sourceFile,
                            line,
                            column,
                            length
                    ));
                }
                if (statementStopsControl(stmt)) {
                    exited = true;
                }
            }
        }

        private static int statementHighlightLength(AffogatoParser.StatementContext stmt) {
            if (stmt.getStart() == null || stmt.getStop() == null) {
                return 1;
            }
            if (stmt.getStart().getLine() == stmt.getStop().getLine()) {
                return Math.max(1, stmt.getStop().getCharPositionInLine() - stmt.getStart().getCharPositionInLine() + 1);
            }
            return Math.max(1, stmt.getStart().getText().length());
        }
    }

    private TypeGuess inferExpressionType(String expression, MethodContext context) {
        if (expression == null || expression.isBlank()) {
            return TypeGuess.unknown();
        }
        AstExpression ast = expressionAst(expression, context);
        if (ast.resolvedType().isKnown() && astTypeCanShortCircuitInference(ast)) {
            return ast.resolvedType();
        }
        String value = stripOuterParens(expression.trim());
        if (value.isBlank()) {
            return TypeGuess.unknown();
        }
        int arrowIndex = topLevelOperatorIndex(value, List.of("->"));
        if (arrowIndex >= 0) {
            return TypeGuess.lambda(lambdaParameterArity(value.substring(0, arrowIndex)));
        }
        if (containsTopLevelMethodReference(value)) {
            return TypeGuess.lambda();
        }
        if (value.equals("null")) {
            return TypeGuess.nullLiteral();
        }
        if (value.startsWith("\"") && stringLiteralEnd(value, 0) == value.length()) {
            return TypeGuess.of("String");
        }
        if (value.equals("true") || value.equals("false")) {
            return TypeGuess.of("boolean");
        }
        String numericType = numericLiteralType(value);
        if (numericType != null) {
            return TypeGuess.of(numericType);
        }

        // Array literal `[e1, e2, ...]` — infer `ElementType[]` using the same element-type rule the
        // codegen uses for `new T[]{...}`, so the declared local type matches the emitted array. Without
        // this the local falls back to Object and `.length`, indexing and for-in all fail to resolve.
        if (value.startsWith("[") && value.endsWith("]") && matchingBracket(value, 0) == value.length() - 1) {
            String inner = value.substring(1, value.length() - 1).trim();
            if (!inner.isBlank()) {
                List<String> elements = splitTopLevel(inner, ',').stream().map(String::trim).toList();
                return TypeGuess.of(inferArrayElementType(elements, context) + "[]");
            }
        }

        // Array/list subscript `receiver[index]` — infer the element type of the receiver so a property
        // read on an element (`ps[0].name`) resolves its accessor instead of leaking a raw field read.
        if (value.endsWith("]")) {
            int open = matchBackward(new StringBuilder(value), value.length() - 1, '[', ']');
            if (open > 0) {
                Optional<TypeGuess> element = elementType(inferExpressionType(value.substring(0, open), context));
                if (element.isPresent()) {
                    return element.get();
                }
            }
        }

        Matcher classLiteral = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?(?:\\[\\])*)\\.class$").matcher(value);
        if (classLiteral.matches()) {
            return TypeGuess.of("java.lang.Class");
        }

        Matcher affogatoCast = Pattern.compile("^.+\\s+as\\s+([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?\\??)$").matcher(value);
        if (affogatoCast.matches()) {
            return TypeGuess.of(stripNullableSuffix(affogatoCast.group(1)));
        }
        Matcher javaCast = Pattern.compile("^\\(\\(([^)]+)\\)\\s+.+\\)$").matcher(value);
        if (javaCast.matches()) {
            return TypeGuess.of(stripNullableSuffix(javaCast.group(1).trim()));
        }

        String knownVariableType = context.variableTypes.get(value);
        if (knownVariableType != null) {
            return TypeGuess.of(knownVariableType);
        }

        Matcher newExpression = Pattern.compile("^new\\s+([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?(?:\\[\\])*)\\s*\\(").matcher(value);
        if (newExpression.find()) {
            return TypeGuess.of(newExpression.group(1));
        }

        Matcher constructor = Pattern.compile("^([A-Za-z_$][A-Za-z0-9_.$]*(?:<[^>]+>)?)\\s*\\(.*\\)$").matcher(value);
        if (constructor.matches()) {
            String typeName = constructor.group(1);
            String simpleName = simpleTypeName(typeName);
            if (!simpleName.isBlank() && Character.isUpperCase(simpleName.charAt(0))) {
                return TypeGuess.of(constructorImplementation(typeName));
            }
        }

        int ternaryQ = topLevelOperatorIndex(value, List.of("?"));
        if (ternaryQ > 0 && !Character.isJavaIdentifierPart(value.charAt(ternaryQ - 1))) {
            String rest = value.substring(ternaryQ + 1).trim();
            int colonIdx = topLevelOperatorIndex(rest, List.of(":"));
            if (colonIdx >= 0) {
                TypeGuess thenType = inferExpressionType(rest.substring(0, colonIdx).trim(), context);
                TypeGuess elseType = inferExpressionType(rest.substring(colonIdx + 1).trim(), context);
                if (thenType.isKnown() && !thenType.isNullLiteral()) {
                    return thenType;
                }
                if (elseType.isKnown() && !elseType.isNullLiteral()) {
                    return elseType;
                }
                return TypeGuess.unknown();
            }
        }

        if (startsWithBooleanNegation(value)) {
            return TypeGuess.of("boolean");
        }
        if (topLevelOperatorIndex(value, List.of("||", "&&", "==", "!=", "<=", ">=", "<", ">")) >= 0
                || INSTANCEOF_ALIAS.matcher(value).find()) {
            return TypeGuess.of("boolean");
        }
        Optional<TypeGuess> numericExpression = inferNumericExpressionType(value, context);
        if (numericExpression.isPresent()) {
            return numericExpression.get();
        }

        int callOpen = callOpenParen(value);
        if (callOpen > 0) {
            String callName = callNameBefore(value, callOpen);
            if (!callName.isBlank()) {
                List<TypedArgument> arguments = typedArgumentsForInference(value.substring(callOpen + 1, value.length() - 1), context);
                TypeGuess returnType = context.returnType(callName, arguments);
                if (returnType.isKnown()) {
                    return returnType;
                }
                // callNameBefore stops at the first non-identifier character, so a non-identifier receiver
                // (string literal, parenthesised expression, call result) is lost. Recover it directly so e.g.
                // "x".ext() or foo().ext() infers its return type.
                String receiver = receiverBeforeMethod(value, callOpen);
                if (!receiver.isBlank()) {
                    String method = callName.substring(callName.lastIndexOf('.') + 1);
                    TypeGuess receiverType = inferExpressionType(receiver, context);
                    if (receiverType.isKnown()) {
                        TypeGuess received = context.returnTypeForReceiverType(receiverType.javaType(), method, arguments);
                        if (received.isKnown()) {
                            return received;
                        }
                    }
                }
            }
        }

        TypeGuess propertyType = propertyType(value, context);
        if (propertyType.isKnown()) {
            return propertyType;
        }

        List<String> additiveParts = splitTopLevel(value, '+');
        if (additiveParts.size() > 1) {
            boolean hasString = additiveParts.stream()
                    .map(part -> inferExpressionType(part, context))
                    .anyMatch(type -> type.javaType().equals("String") || type.javaType().equals("java.lang.String"));
            if (hasString) {
                return TypeGuess.of("String");
            }
        }

        return TypeGuess.unknown();
    }

    private AstExpression expressionAst(String expression, MethodContext context) {
        return new ExpressionSemanticChecker(new TranspilerExpressionSupport(context)).parse(expression);
    }

    private boolean astTypeCanShortCircuitInference(AstExpression ast) {
        // The ANTLR-backed AST resolves these node types reliably, including cases the regex inference
        // below mishandles. Constructors in particular carry the correct implementation type even when
        // the type arguments nest generics (e.g. Map<String, List<Integer>>()), which the regex path
        // misreads as a boolean comparison on the top-level '<' / '>'.
        return ast instanceof LambdaExpression
                || ast instanceof MethodReferenceExpression
                || (ast instanceof ConstructorExpression && ast.resolvedType().isKnown());
    }

    private final class TranspilerExpressionSupport implements ExpressionSemanticChecker.Support {
        private final MethodContext context;

        private TranspilerExpressionSupport(MethodContext context) {
            this.context = context;
        }

        @Override
        public String stripOuterParens(String text) {
            return AffogatoTranspiler.this.stripOuterParens(text);
        }

        @Override
        public boolean containsTopLevelMethodReference(String value) {
            return AffogatoTranspiler.this.containsTopLevelMethodReference(value);
        }

        @Override
        public int topLevelOperatorIndex(String value, List<String> operators) {
            return AffogatoTranspiler.this.topLevelOperatorIndex(value, operators);
        }

        @Override
        public int lambdaParameterArity(String header) {
            return AffogatoTranspiler.this.lambdaParameterArity(header);
        }

        @Override
        public int stringLiteralEnd(String expression, int openQuoteIndex) {
            return AffogatoTranspiler.this.stringLiteralEnd(expression, openQuoteIndex);
        }

        @Override
        public String stripNullableSuffix(String typeName) {
            return AffogatoTranspiler.this.stripNullableSuffix(typeName);
        }

        @Override
        public int namedArgumentEquals(String expression) {
            return AffogatoTranspiler.this.namedArgumentEquals(expression);
        }

        @Override
        public int callOpenParen(String value) {
            return AffogatoTranspiler.this.callOpenParen(value);
        }

        @Override
        public String callNameBefore(String expression, int openIndex) {
            return AffogatoTranspiler.this.callNameBefore(expression, openIndex);
        }

        @Override
        public String simpleTypeName(String type) {
            return AffogatoTranspiler.this.simpleTypeName(type);
        }

        @Override
        public String constructorImplementation(String typeName) {
            return AffogatoTranspiler.this.constructorImplementation(typeName);
        }

        @Override
        public List<String> splitTopLevel(String text, char delimiter) {
            return AffogatoTranspiler.this.splitTopLevel(text, delimiter);
        }

        @Override
        public boolean startsWithBooleanNegation(String value) {
            return AffogatoTranspiler.this.startsWithBooleanNegation(value);
        }

        @Override
        public boolean isStringType(TypeGuess type) {
            return AffogatoTranspiler.this.isStringType(type);
        }

        @Override
        public boolean isNumericType(TypeGuess type) {
            return AffogatoTranspiler.this.isNumericType(type);
        }

        @Override
        public String promotedNumericType(String left, String right) {
            return AffogatoTranspiler.this.promotedNumericType(left, right);
        }

        @Override
        public String variableType(String name) {
            return context.identifierType(name).orElse(null);
        }
    }

    private boolean startsWithBooleanNegation(String value) {
        return value.startsWith("not(") || value.startsWith("!(") || value.startsWith("!");
    }

    private Optional<TypeGuess> inferNumericExpressionType(String value, MethodContext context) {
        for (String operator : List.of("+", "-", "*", "/", "%")) {
            int operatorIndex = topLevelOperatorIndex(value, List.of(operator));
            if (operatorIndex <= 0) {
                continue;
            }
            TypeGuess left = inferExpressionType(value.substring(0, operatorIndex), context);
            TypeGuess right = inferExpressionType(value.substring(operatorIndex + operator.length()), context);
            if (operator.equals("+") && (isStringType(left) || isStringType(right))) {
                return Optional.of(TypeGuess.of("String"));
            }
            if (isNumericType(left) && isNumericType(right)) {
                return Optional.of(TypeGuess.of(promotedNumericType(left.javaType(), right.javaType())));
            }
        }
        return Optional.empty();
    }

    private boolean isStringType(TypeGuess type) {
        return type.javaType().equals("String") || type.javaType().equals("java.lang.String");
    }

    private boolean isNumericType(TypeGuess type) {
        return switch (type.javaType()) {
            case "byte", "short", "int", "long", "float", "double", "char",
                 "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                 "java.lang.Float", "java.lang.Double", "java.lang.Character" -> true;
            default -> false;
        };
    }

    private String promotedNumericType(String left, String right) {
        String normalizedLeft = primitiveNumericType(left);
        String normalizedRight = primitiveNumericType(right);
        if (normalizedLeft.equals("double") || normalizedRight.equals("double")) {
            return "double";
        }
        if (normalizedLeft.equals("float") || normalizedRight.equals("float")) {
            return "float";
        }
        if (normalizedLeft.equals("long") || normalizedRight.equals("long")) {
            return "long";
        }
        return "int";
    }

    private String primitiveNumericType(String type) {
        return switch (type) {
            case "java.lang.Byte" -> "byte";
            case "java.lang.Short" -> "short";
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Float" -> "float";
            case "java.lang.Double" -> "double";
            case "java.lang.Character" -> "char";
            default -> type;
        };
    }

    private TypeGuess propertyType(String expression, MethodContext context) {
        int dot = expression.lastIndexOf('.');
        if (dot <= 0 || dot == expression.length() - 1 || expression.indexOf('(') >= 0) {
            return TypeGuess.unknown();
        }
        String owner = expression.substring(0, dot);
        String property = expression.substring(dot + 1);
        return propertyType(inferExpressionType(owner, context), property, context);
    }

    private TypeGuess propertyType(TypeGuess ownerType, String property, MethodContext context) {
        if (!ownerType.isKnown() || ownerType.isNullLiteral()) {
            return TypeGuess.unknown();
        }
        PropertyHop hop = resolvePropertyHopOnType(ownerType.javaType(), property, context);
        return hop == null ? TypeGuess.unknown() : hop.resultType();
    }

    private boolean isGetterSetterBackedPropertyAccess(PropertyAccessExpression property, MethodContext context) {
        TypeGuess receiverType = property.receiver().resolvedType().isKnown()
                ? property.receiver().resolvedType()
                : inferExpressionType(property.receiver().source(), context);
        if (!receiverType.isKnown() || receiverType.isNullLiteral()) {
            return false;
        }
        PropertyHop hop = resolvePropertyHopOnType(receiverType.javaType(), property.property(), context);
        return hop != null && hop.call();
    }

    private boolean isCompoundAssignmentSource(String source) {
        int operatorStart = topLevelAssignmentStart(source.trim());
        if (operatorStart < 0) {
            return false;
        }
        char operator = source.charAt(operatorStart);
        return operator == '+' || operator == '-' || operator == '*' || operator == '/' || operator == '%';
    }

    private FieldSymbol fieldForOwnerType(String ownerType, String property, MethodContext context) {
        ClassSymbol symbol = classSymbol(ownerType, context.unit);
        if (symbol == null) {
            return null;
        }
        return symbol.fields.get(property);
    }

    private Optional<TypeGuess> elementType(TypeGuess iterableType) {
        if (!iterableType.isKnown() || iterableType.isNullLiteral()) {
            return Optional.empty();
        }
        String type = iterableType.javaType();
        int genericStart = type.indexOf('<');
        int genericEnd = type.lastIndexOf('>');
        if (genericStart >= 0 && genericEnd > genericStart) {
            String firstArgument = splitTopLevel(type.substring(genericStart + 1, genericEnd), ',').get(0).trim();
            if (!firstArgument.isBlank()) {
                return Optional.of(TypeGuess.of(firstArgument));
            }
        }
        if (type.endsWith("[]")) {
            return Optional.of(TypeGuess.of(type.substring(0, type.length() - 2)));
        }
        return Optional.empty();
    }

    private List<TypedArgument> typedArgumentsForInference(String args, MethodContext context) {
        List<TypedArgument> arguments = new ArrayList<>();
        if (args.isBlank()) {
            return arguments;
        }
        for (String part : splitTopLevel(args, ',')) {
            int equals = namedArgumentEquals(part);
            if (equals > 0) {
                String name = part.substring(0, equals).trim();
                String value = part.substring(equals + 1).trim();
                arguments.add(new TypedArgument(name, value, inferExpressionType(value, context), expressionAst(value, context)));
            } else if (!part.trim().isBlank()) {
                String value = part.trim();
                arguments.add(new TypedArgument("", value, inferExpressionType(value, context), expressionAst(value, context)));
            }
        }
        return arguments;
    }

    private int callOpenParen(String value) {
        if (!value.endsWith(")")) {
            return -1;
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '(' && findMatching(value, index, '(', ')') == value.length() - 1) {
                return index;
            }
        }
        return -1;
    }

    private boolean containsTopLevelArrow(String value) {
        return containsTopLevelOperator(value, "->");
    }

    private boolean containsTopLevelMethodReference(String value) {
        return containsTopLevelOperator(value, "::");
    }

    /**
     * Counts the parameters declared on the left of a lambda's {@code ->}. A parenthesized list
     * {@code (a, b)} counts its top-level commas; an empty list {@code ()} is zero; a bare {@code x}
     * is one. Returns {@link #UNKNOWN_ARITY} when the shape is unrecognizable.
     */
    private int lambdaParameterArity(String header) {
        String params = header.trim();
        if (params.startsWith("(") && params.endsWith(")")) {
            String inner = params.substring(1, params.length() - 1).trim();
            if (inner.isEmpty()) {
                return 0;
            }
            return splitTopLevel(inner, ',').size();
        }
        return params.isEmpty() ? UNKNOWN_ARITY : 1;
    }

    private boolean containsTopLevelOperator(String value, String operator) {
        return topLevelOperatorIndex(value, List.of(operator)) >= 0;
    }

    private int topLevelOperatorIndex(String value, List<String> operators) {
        int angle = 0;
        int paren = 0;
        int bracket = 0;
        boolean inString = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            char previous = index > 0 ? value.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (angle == 0 && paren == 0 && bracket == 0) {
                for (String operator : operators) {
                    if (value.startsWith(operator, index)) {
                        return index;
                    }
                }
            }
            if (current == '<') {
                angle++;
            } else if (current == '>') {
                angle = Math.max(0, angle - 1);
            } else if (current == '(') {
                paren++;
            } else if (current == ')') {
                paren = Math.max(0, paren - 1);
            } else if (current == '[') {
                bracket++;
            } else if (current == ']') {
                bracket = Math.max(0, bracket - 1);
            }
        }
        return -1;
    }

    private String stripNullableSuffix(String typeName) {
        String type = stripTypeUseAnnotations(typeName.trim());
        if (type.endsWith("?") || type.endsWith("!")) {
            return type.substring(0, type.length() - 1);
        }
        return type;
    }

    private String stripTypeUseAnnotations(String typeName) {
        return typeName.replaceAll("@(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*\\s+", "");
    }

    private TypeRef inferType(String initializer) {
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
    private static String numericLiteralType(String literal) {
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

    private TypeRef typeRef(AffogatoParser.TypeRefContext context) {
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

    private int matchingBracket(String text, int open) {
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

    private String parameterList(List<ParamDecl> parameters) {
        List<String> rendered = new ArrayList<>();
        for (ParamDecl parameter : parameters) {
            String prefix = parameter.annotations().isEmpty() ? "" : String.join(" ", parameter.annotations()) + " ";
            rendered.add(prefix + parameter.type().declaration() + " " + parameter.name());
        }
        return String.join(", ", rendered);
    }

    private void writeAnnotations(StringBuilder out, List<String> annotations, int indent) {
        for (String annotation : annotations) {
            out.append(indent(indent)).append(annotation).append(System.lineSeparator());
        }
    }

    private void writeNullCheck(StringBuilder out, String name, TypeRef type, int indent) {
        if (type.requiresRuntimeCheck()) {
            out.append(indent(indent)).append("Objects.requireNonNull(").append(name).append(", \"").append(name).append("\");")
                    .append(System.lineSeparator());
        }
    }

    private boolean usesNullable(ParsedClass clazz) {
        return allTypes(clazz).stream()
                .anyMatch(type -> type.nullability() == Nullability.NULLABLE || type.javaType().contains("@Nullable"));
    }

    private boolean usesNotNull(ParsedClass clazz) {
        return allTypes(clazz).stream()
                .anyMatch(type -> type.nullability() == Nullability.NOT_NULL || type.javaType().contains("@NotNull"));
    }

    private boolean usesObjects(ParsedClass clazz) {
        return allTypes(clazz).stream().anyMatch(TypeRef::requiresRuntimeCheck);
    }

    private List<TypeRef> allTypes(ParsedClass clazz) {
        List<TypeRef> types = new ArrayList<>();
        clazz.fields().forEach(field -> types.add(field.type()));
        clazz.compactParameters().forEach(parameter -> types.add(parameter.type()));
        clazz.constructors().forEach(constructor -> constructor.parameters().forEach(parameter -> types.add(parameter.type())));
        clazz.methods().forEach(method -> {
            types.add(method.returnType());
            method.parameters().forEach(parameter -> types.add(parameter.type()));
        });
        return types;
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

    /**
     * Normalizes a Swift-style trailing closure into the parenthesized lambda form that the
     * rest of the pipeline already understands. {@code call(args) { p -> body }} becomes
     * {@code call(args, p -> body)} and {@code receiver.method { p -> body }} becomes
     * {@code receiver.method(p -> body)}. Returns {@code exprText} untouched when there is no
     * trailing closure, preserving full backward compatibility.
     */
    private String mergeTrailingClosure(String exprText, String source,
                                        AffogatoParser.TrailingClosureContext closure,
                                        MethodContext context) {
        if (closure == null) {
            return exprText;
        }
        boolean hasParams = closure.lambdaParameters() != null;
        String params = hasParams
                ? sourceText(source, closure.lambdaParameters()).trim()
                : "()";
        AffogatoParser.ClosureBodyContext body = closure.closureBody();

        // Type-directed shaping: when the target's last parameter is a Supplier of a list and the
        // closure has no explicit parameters, each child expression is collected into a list. This is
        // the SwiftUI/Compose-style result builder used by DSLs such as `Panel { Label(...) ... }`.
        String elementType = hasParams
                ? null
                : supplierListElementType(lastParameterType(trailingCallName(exprText), context));

        String lambda;
        if (elementType != null && body != null && hasClosureStatements(body)) {
            lambda = "() -> " + buildListBuilderBody(body, elementType, source, context);
        } else {
            lambda = params + " -> " + closureBodyText(body, source, context);
        }
        return appendClosureArgument(exprText, lambda);
    }

    /** Merges {@code lambda} into {@code exprText} as a trailing argument, mirroring Swift trailing-closure calls. */
    private String appendClosureArgument(String exprText, String lambda) {
        String trimmed = exprText.stripTrailing();
        if (trimmed.endsWith(")")) {
            int open = matchingOpenIndex(trimmed);
            if (open > 0) {
                String prefix = trimmed.substring(0, open);
                String args = trimmed.substring(open + 1, trimmed.length() - 1).trim();
                String merged = args.isEmpty() ? lambda : args + ", " + lambda;
                return prefix + "(" + merged + ")";
            }
        }
        return trimmed + "(" + lambda + ")";
    }

    /** The call/type name a trailing closure attaches to, e.g. {@code Panel} or {@code Button} from {@code Button(text = ...)}. */
    private String trailingCallName(String exprText) {
        String trimmed = exprText.stripTrailing();
        if (trimmed.endsWith(")")) {
            int open = matchingOpenIndex(trimmed);
            return open > 0 ? trimmed.substring(0, open).trim() : "";
        }
        return trimmed.trim();
    }

    /** The declared type of the last parameter of {@code callName}'s Affogato constructor, or {@code null}. */
    private String lastParameterType(String callName, MethodContext context) {
        if (callName == null || callName.isBlank()) {
            return null;
        }
        ClassSymbol symbol = classSymbol(callName, context.unit);
        if (symbol == null) {
            return null;
        }
        for (ConstructorSymbol constructor : symbol.constructors) {
            List<ParamDecl> parameters = constructor.parameters();
            if (!parameters.isEmpty()) {
                return parameters.get(parameters.size() - 1).type().javaType();
            }
        }
        return null;
    }

    /** Given a {@code Supplier<List<E>>}-shaped type, returns {@code E}; otherwise {@code null}. */
    private String supplierListElementType(String typeName) {
        if (typeName == null) {
            return null;
        }
        String type = typeName.replaceAll("\\s+", "");
        String supplierPrefix = type.startsWith("Supplier<") ? "Supplier<"
                : type.startsWith("java.util.function.Supplier<") ? "java.util.function.Supplier<"
                : null;
        if (supplierPrefix == null || !type.endsWith(">")) {
            return null;
        }
        String inner = type.substring(supplierPrefix.length(), type.length() - 1);
        for (String listPrefix : List.of("List<", "java.util.List<", "Collection<", "java.util.Collection<")) {
            if (inner.startsWith(listPrefix) && inner.endsWith(">")) {
                return inner.substring(listPrefix.length(), inner.length() - 1);
            }
        }
        return null;
    }

    private boolean hasClosureStatements(AffogatoParser.ClosureBodyContext body) {
        if (body.lambdaBody() != null) {
            return false;
        }
        return body.statement().stream().anyMatch(statement -> statement.separators() == null);
    }

    /** Renders a closure body as a Java lambda body: a single expression/block, or a generated statement block. */
    private String closureBodyText(AffogatoParser.ClosureBodyContext body, String source, MethodContext context) {
        if (body == null) {
            return "{}";
        }
        if (body.lambdaBody() != null) {
            return sourceText(source, body.lambdaBody()).trim();
        }
        if (!hasClosureStatements(body)) {
            return "{}";
        }
        StringBuilder block = new StringBuilder("{").append(System.lineSeparator());
        for (AffogatoParser.StatementContext statement : body.statement()) {
            writeStatement(block, context.unit, statement, context, 0);
        }
        block.append("}");
        return block.toString();
    }

    /** Builds the {@code () -> { ... return $children; }} list-builder body for a result-builder closure. */
    private String buildListBuilderBody(AffogatoParser.ClosureBodyContext body, String elementType,
                                        String source, MethodContext context) {
        StringBuilder block = new StringBuilder("{").append(System.lineSeparator());
        block.append("java.util.List<").append(elementType).append("> $children = new java.util.ArrayList<>();")
                .append(System.lineSeparator());
        for (AffogatoParser.StatementContext statement : body.statement()) {
            if (statement.separators() != null) {
                continue;
            }
            if (statement.expressionStatement() != null) {
                String childExpr = mergeTrailingClosure(
                        sourceText(source, statement.expressionStatement().expression()),
                        source,
                        statement.expressionStatement().trailingClosure(),
                        context).trim();
                block.append("$children.add(").append(childExpr).append(");").append(System.lineSeparator());
            } else {
                writeStatement(block, context.unit, statement, context, 0);
            }
        }
        block.append("return $children;").append(System.lineSeparator());
        block.append("}");
        return block.toString();
    }

    /** Index of the '(' opening the final top-level group of {@code text} (which ends in ')'). */
    private int matchingOpenIndex(String text) {
        int depth = 0;
        int lastTopLevelOpen = -1;
        boolean inString = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char previous = index > 0 ? text.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == '(') {
                if (depth == 0) {
                    lastTopLevelOpen = index;
                }
                depth++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
            }
        }
        return lastTopLevelOpen;
    }

    private String sourceText(String source, ParserRuleContext context) {
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

    private List<String> splitTopLevel(String text, char delimiter) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int angle = 0;
        int paren = 0;
        int bracket = 0;
        int brace = 0;
        boolean inString = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char previous = index > 0 ? text.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == '<') {
                angle++;
            } else if (current == '>') {
                angle = Math.max(0, angle - 1);
            } else if (current == '(') {
                paren++;
            } else if (current == ')') {
                paren = Math.max(0, paren - 1);
            } else if (current == '[') {
                bracket++;
            } else if (current == ']') {
                bracket = Math.max(0, bracket - 1);
            } else if (current == '{') {
                brace++;
            } else if (current == '}') {
                brace = Math.max(0, brace - 1);
            } else if (current == delimiter && angle == 0 && paren == 0 && bracket == 0 && brace == 0) {
                result.add(text.substring(start, index));
                start = index + 1;
            }
        }
        result.add(text.substring(start));
        return result;
    }

    private int findMatching(String text, int openIndex, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            char previous = index > 0 ? text.charAt(index - 1) : '\0';
            if (current == '"' && previous != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (current == openChar) {
                depth++;
            } else if (current == closeChar) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private String callNameBefore(String expression, int openIndex) {
        int end = openIndex - 1;
        while (end >= 0 && Character.isWhitespace(expression.charAt(end))) {
            end--;
        }
        int start = end;
        while (start >= 0) {
            char current = expression.charAt(start);
            if (Character.isJavaIdentifierPart(current) || current == '.') {
                start--;
            } else {
                break;
            }
        }
        return expression.substring(start + 1, end + 1);
    }

    // For a call whose `(` is at `callOpen`, returns the receiver expression text preceding `.method`, or
    // an empty string when the method is not invoked on a receiver (no preceding dot).
    private String receiverBeforeMethod(String value, int callOpen) {
        int nameEnd = callOpen;
        while (nameEnd > 0 && Character.isWhitespace(value.charAt(nameEnd - 1))) {
            nameEnd--;
        }
        int nameStart = nameEnd;
        while (nameStart > 0 && Character.isJavaIdentifierPart(value.charAt(nameStart - 1))) {
            nameStart--;
        }
        int dot = nameStart - 1;
        while (dot >= 0 && Character.isWhitespace(value.charAt(dot))) {
            dot--;
        }
        if (dot >= 0 && value.charAt(dot) == '.') {
            return value.substring(0, dot).trim();
        }
        return "";
    }

    private boolean isUppercaseIdentifierStart(String expression, int index) {
        char current = expression.charAt(index);
        return Character.isUpperCase(current) && (index == 0 || !Character.isJavaIdentifierPart(expression.charAt(index - 1)));
    }

    private boolean isPrecededByNewOrDot(String expression, int index) {
        int previous = index - 1;
        while (previous >= 0 && Character.isWhitespace(expression.charAt(previous))) {
            previous--;
        }
        if (previous >= 0 && expression.charAt(previous) == '.') {
            return true;
        }
        String prefix = expression.substring(0, index).trim();
        return prefix.endsWith("new");
    }

    private int readExplicitConstructorTypeEnd(String expression, int index) {
        int cursor = index;
        boolean readPart = false;
        while (cursor < expression.length()) {
            if (!Character.isJavaIdentifierStart(expression.charAt(cursor))) {
                break;
            }
            readPart = true;
            cursor++;
            while (cursor < expression.length() && Character.isJavaIdentifierPart(expression.charAt(cursor))) {
                cursor++;
            }
            if (cursor < expression.length() && expression.charAt(cursor) == '.') {
                cursor++;
                continue;
            }
            break;
        }
        if (!readPart) {
            return index;
        }
        if (cursor < expression.length() && expression.charAt(cursor) == '<') {
            int angle = 1;
            cursor++;
            while (cursor < expression.length() && angle > 0) {
                char current = expression.charAt(cursor);
                if (current == '<') {
                    angle++;
                } else if (current == '>') {
                    angle--;
                }
                cursor++;
            }
        }
        while (cursor + 1 < expression.length() && expression.charAt(cursor) == '[' && expression.charAt(cursor + 1) == ']') {
            cursor += 2;
        }
        while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int readTypeExpressionEnd(String expression, int index) {
        int cursor = index;
        while (cursor < expression.length() && Character.isJavaIdentifierPart(expression.charAt(cursor))) {
            cursor++;
        }
        if (cursor < expression.length() && expression.charAt(cursor) == '<') {
            int angle = 1;
            cursor++;
            while (cursor < expression.length() && angle > 0) {
                char current = expression.charAt(cursor);
                if (current == '<') {
                    angle++;
                } else if (current == '>') {
                    angle--;
                }
                cursor++;
            }
        }
        while (cursor < expression.length() && Character.isWhitespace(expression.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int readIdentifierEnd(String expression, int start) {
        int cursor = start;
        while (cursor < expression.length() && Character.isJavaIdentifierPart(expression.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private String stripOuterParens(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("(") && trimmed.endsWith(")") && findMatching(trimmed, 0, '(', ')') == trimmed.length() - 1) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String simpleTypeName(String type) {
        String cleaned = type;
        int generic = cleaned.indexOf('<');
        if (generic >= 0) {
            cleaned = cleaned.substring(0, generic);
        }
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }

    private String getterName(String fieldName, TypeRef type) {
        String prefix = type.javaType().equals("boolean") || type.javaType().equals("Boolean") ? "is" : "get";
        return prefix + capitalize(fieldName);
    }

    private String setterName(String fieldName) {
        return "set" + capitalize(fieldName);
    }

    private String capitalize(String value) {
        if (value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private String indent(int level) {
        return "    ".repeat(Math.max(0, level));
    }

    // Rejects declaration names that are Java reserved words; emitting them verbatim would produce
    // Java that javac rejects ("<identifier> expected"). `kind` names the declaration in the message.
    private void validateDeclaredName(Path sourceFile, String name, String kind, int line, int column) {
        if (JAVA_RESERVED_WORDS.contains(name)) {
            diagnostics.add(error(sourceFile, line, column, name.length(),
                    "AFFOGATO_RESERVED_IDENTIFIER",
                    "The " + kind + " name '" + name + "' is a Java reserved word and cannot be used as an identifier."));
        }
    }

    // Warns when a static `main` is not the runnable entry point `main(args: String[])`. Such a method
    // (e.g. zero-arg `main()`) compiles to a non-entry `void main()` that `java` cannot launch, which is
    // an easy silent trap. A warning (not an error) keeps non-entry helper methods named `main` working.
    private void validateMainSignature(Path sourceFile, ParsedClass clazz) {
        for (MethodDecl method : clazz.methods()) {
            if (!method.isStatic() || !method.name().equals("main")) {
                continue;
            }
            boolean validEntry = method.parameters().size() == 1
                    && isStringArrayType(method.parameters().get(0).type().javaType());
            if (!validEntry) {
                diagnostics.add(warning(sourceFile, method.line(), 1, "AFFOGATO_MAIN_SIGNATURE",
                        "Static method 'main' is not a valid Java entry point; declare it as "
                                + "'main(args: String[])' to be runnable with 'java'."));
            }
        }
    }

    private boolean isStringArrayType(String javaType) {
        String type = stripNullableSuffix(javaType).trim();
        return type.equals("String[]") || type.equals("java.lang.String[]");
    }

    private AffogatoDiagnostic error(Path sourceFile, int line, int column, String code, String message) {
        return error(sourceFile, line, column, 1, code, message);
    }

    private AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length);
    }

    private AffogatoDiagnostic warning(Path sourceFile, int line, int column, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.WARNING, code, message, sourceFile, line, column, 1);
    }

    public record ParsedUnit(Path sourceFile, CompilationUnit unit) {
        static ParsedUnit empty(Path sourceFile, String source) {
            return new ParsedUnit(sourceFile, new CompilationUnit(sourceFile, source, "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        }
    }

    public record GeneratedJava(String packageName, String className, String source) {
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
            diagnostics.add(error(sourceFile, line, charPositionInLine + 1, length, "AFFOGATO_PARSE", message));
        }

        private boolean hadErrors() {
            return hadErrors;
        }
    }

}
