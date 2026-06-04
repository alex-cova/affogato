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
import org.antlr.v4.runtime.tree.TerminalNode;

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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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

public final class AffogatoTranspiler {
    private static final Pattern LOCAL_DECLARATION = Pattern.compile(
            "^(var|let)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([^=]+?))?\\s*(?:=\\s*(.+))?$"
    );
    private static final Pattern PROPERTY_ASSIGNMENT = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$"
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
    /** Arity sentinel for poly expressions whose parameter count is not statically known (e.g. method references). */
    private static final int UNKNOWN_ARITY = TypeGuess.UNKNOWN_ARITY;
    private static final Set<String> PRIMITIVES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char"
    );
    private static final Map<String, Class<?>> PRIMITIVE_CLASSES = Map.of(
            "byte", byte.class,
            "short", short.class,
            "int", int.class,
            "long", long.class,
            "float", float.class,
            "double", double.class,
            "boolean", boolean.class,
            "char", char.class
    );
    private static final Map<Class<?>, Class<?>> BOXED_PRIMITIVES = Map.of(
            byte.class, Byte.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            boolean.class, Boolean.class,
            char.class, Character.class
    );
    private static final Map<Class<?>, Class<?>> UNBOXED_PRIMITIVES = Map.of(
            Byte.class, byte.class,
            Short.class, short.class,
            Integer.class, int.class,
            Long.class, long.class,
            Float.class, float.class,
            Double.class, double.class,
            Boolean.class, boolean.class,
            Character.class, char.class
    );
    private static final Map<Class<?>, List<Class<?>>> PRIMITIVE_WIDENING = Map.of(
            byte.class, List.of(short.class, int.class, long.class, float.class, double.class),
            short.class, List.of(int.class, long.class, float.class, double.class),
            char.class, List.of(int.class, long.class, float.class, double.class),
            int.class, List.of(long.class, float.class, double.class),
            long.class, List.of(float.class, double.class),
            float.class, List.of(double.class)
    );
    private static final int NO_CONVERSION = 1_000_000;

    private final List<AffogatoDiagnostic> diagnostics;
    private final JavaResolver javaResolver;
    private final FlowAnalyzer flow;
    private final Map<String, ClassSymbol> classSymbols = new LinkedHashMap<>();
    private final Map<String, List<ExtensionSymbol>> extensionSymbols = new LinkedHashMap<>();
    private Set<String> activeTypeParams = new HashSet<>();

    public AffogatoTranspiler(List<AffogatoDiagnostic> diagnostics, List<Path> classpath) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.javaResolver = new JavaResolver(classpath);
        this.flow = new FlowAnalyzer(diagnostics);
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
        AffogatoParser.CompilationUnitContext tree = parser.compilationUnit();

        if (syntaxErrors.hadErrors()) {
            return ParsedUnit.empty(sourceFile, source);
        }

        CompilationUnit unit = buildCompilationUnit(sourceFile, source, tree);
        return new ParsedUnit(sourceFile, unit);
    }

    private void scanUnsupportedSourceEdges(Path sourceFile, String source) {
        scanUnsupportedToken(sourceFile, source, "?.", "AFFOGATO_UNSUPPORTED_SAFE_CALL", "Safe-call expressions are not in the production subset; use an explicit null check.");
        scanUnsupportedToken(sourceFile, source, "?:", "AFFOGATO_UNSUPPORTED_ELVIS", "Elvis expressions are not in the production subset; use a ternary expression.");
        scanUnsupportedToken(sourceFile, source, "!!", "AFFOGATO_UNSUPPORTED_NOT_NULL_ASSERTION", "Not-null assertion expressions are not in the production subset; use an explicit cast or null check.");
    }

    private void scanUnsupportedToken(Path sourceFile, String source, String token, String code, String message) {
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            SourceLocation location = sourceLocation(source, index);
            diagnostics.add(error(sourceFile, location.line(), location.column(), code, message));
            index += token.length();
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

    public void registerSymbols(ParsedUnit parsedUnit) {
        CompilationUnit unit = parsedUnit.unit();
        for (ParsedClass clazz : unit.classes()) {
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
            classSymbols.put(symbol.name(), symbol);
            if (!unit.packageName().isBlank()) {
                classSymbols.put(unit.packageName() + "." + symbol.name(), symbol);
            }
        }
        for (ParsedEnum parsedEnum : unit.enums()) {
            ClassSymbol symbol = new ClassSymbol(unit.packageName(), parsedEnum.name(), "", false, List.of());
            symbol.constructors.add(new ConstructorSymbol(List.of()));
            classSymbols.put(symbol.name(), symbol);
            if (!unit.packageName().isBlank()) {
                classSymbols.put(unit.packageName() + "." + symbol.name(), symbol);
            }
        }
        for (ParsedInterface parsedInterface : unit.interfaces()) {
            ClassSymbol symbol = new ClassSymbol(unit.packageName(), parsedInterface.name(), "", true,
                    parsedInterface.typeParameters().stream().map(TypeParamDecl::name).toList());
            for (InterfaceMethod method : parsedInterface.methods()) {
                symbol.methods.computeIfAbsent(method.name(), ignored -> new ArrayList<>())
                        .add(new MethodSymbol(method.name(), method.returnType(), method.parameters(), false));
            }
            classSymbols.put(symbol.name(), symbol);
            if (!unit.packageName().isBlank()) {
                classSymbols.put(unit.packageName() + "." + symbol.name(), symbol);
            }
        }
        for (ParsedRecord parsedRecord : unit.records()) {
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
            classSymbols.put(symbol.name(), symbol);
            if (!unit.packageName().isBlank()) {
                classSymbols.put(unit.packageName() + "." + symbol.name(), symbol);
            }
        }
        if (!unit.extensions().isEmpty()) {
            String holderSimpleName = extensionsHolderName(unit);
            // The generated holder is also exposed as a synthetic class symbol holding the extensions as
            // static methods (receiver as the first parameter). This lets the rewritten call
            // Holder.method(receiver, args) resolve through the regular static-method machinery.
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
            classSymbols.put(holderSymbol.name(), holderSymbol);
            if (!unit.packageName().isBlank()) {
                classSymbols.put(unit.packageName() + "." + holderSymbol.name(), holderSymbol);
            }
        }
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
        TypeRef returnType = extensionDecl.typeRef() == null ? TypeRef.unspecified("void") : typeRef(extensionDecl.typeRef());
        List<ParamDecl> parameters = extensionDecl.parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, extensionDecl.parameterList(), false);
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

        return new ParsedClass(access, name, buildTypeParams(classDecl.typeParamList()), superTypes, compactParameters, fields, constructors, methods, annotations(source, classDecl.annotation()));
    }

    private FieldDecl buildField(Path sourceFile, String source, AffogatoParser.FieldDeclContext fieldDecl) {
        Modifiers modifiers = modifiers(fieldDecl.memberModifier());
        boolean mutable = fieldDecl.variableKind().VAR() != null;
        String name = fieldDecl.Identifier().getText();
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
        List<ParamDecl> parameters = signature.parameterList() == null
                ? List.of()
                : buildParameters(sourceFile, source, signature.parameterList(), false);
        return new MethodDecl(
                modifiers.access(),
                modifiers.isStatic(),
                modifiers.isOverride(),
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
        List<String> constants = enumDecl.enumBody().enumConstant().stream()
                .map(c -> c.Identifier().getText())
                .toList();
        return new ParsedEnum(access, name, constants, annotations(source, enumDecl.annotation()));
    }

    private ParsedRecord buildRecord(Path sourceFile, String source, AffogatoParser.RecordDeclContext recordDecl) {
        String access = accessFromClassModifiers(recordDecl.classModifier());
        String name = recordDecl.Identifier().getText();
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
        return new ParsedRecord(access, name, buildTypeParams(recordDecl.typeParamList()), components, superTypes, methods, annotations(source, recordDecl.annotation()));
    }

    private ParsedInterface buildInterface(Path sourceFile, String source, AffogatoParser.InterfaceDeclContext interfaceDecl) {
        String access = accessFromClassModifiers(interfaceDecl.classModifier());
        String name = interfaceDecl.Identifier().getText();
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
        return new ParsedInterface(access, name, buildTypeParams(interfaceDecl.typeParamList()), methods, annotations(source, interfaceDecl.annotation()));
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
            shapeMethods.add(new MethodDecl("public", true, false, List.of(), extension.returnType(), extension.name(),
                    holderParameters(extension), extension.body(), extension.line(), extension.annotations()));
        }
        ParsedClass shape = new ParsedClass("public", holderName, List.of(), List.of(), List.of(), List.of(), List.of(), shapeMethods, List.of());

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
        ParsedClass dummyClass = new ParsedClass(parsedInterface.access(), parsedInterface.name(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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
                parsedRecord.superTypes(), parsedRecord.components(), List.of(), List.of(), parsedRecord.methods(), List.of());

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
        String raw = typeName;
        int generic = raw.indexOf('<');
        if (generic >= 0) {
            raw = raw.substring(0, generic);
        }
        raw = raw.trim();
        ClassSymbol symbol = classSymbols.get(raw);
        if (symbol != null) {
            return symbol.isInterface();
        }
        if (!unit.packageName().isBlank()) {
            symbol = classSymbols.get(unit.packageName() + "." + raw);
            if (symbol != null) {
                return symbol.isInterface();
            }
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
                    .append(method.access())
                    .append(method.isStatic() ? " static " : " ");
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
                    .append(") {")
                    .append(System.lineSeparator());
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
        for (AffogatoParser.StatementContext statement : block.statement()) {
            writeStatement(out, unit, statement, context, indent);
        }
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
            String expression = rawExpression.isBlank()
                    ? ""
                    : " " + transformExpression(rawExpression, context);
            out.append(indent(indent)).append("return").append(expression).append(";").append(System.lineSeparator());
            return;
        }
        if (statement.throwStatement() != null) {
            TypedExpression expression = transformExpressionTyped(sourceText(unit.source(), statement.throwStatement().expression()), context);
            validateThrowExpression(expression, context, statement.throwStatement().getStart().getLine(), statement.throwStatement().getStart().getCharPositionInLine() + 1);
            out.append(indent(indent)).append("throw ").append(expression.javaSource()).append(";").append(System.lineSeparator());
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
        String condition = transformExpression(rawCondition, context);
        out.append(indent(indent)).append("if (!(").append(stripOuterParens(condition)).append(")) {").append(System.lineSeparator());
        MethodContext.ScopeSnapshot guardScope = context.snapshotScope();
        writeBlockStatements(out, unit, guard.block(), context, indent + 1);
        context.restoreScope(guardScope);
        out.append(indent(indent)).append("}").append(System.lineSeparator());
    }

    private void writeIf(StringBuilder out, CompilationUnit unit, AffogatoParser.IfStatementContext ifStatement, MethodContext context, int indent) {
        String rawCondition = sourceText(unit.source(), ifStatement.condition());
        validateCondition(rawCondition, context, ifStatement.getStart().getLine(), ifStatement.getStart().getCharPositionInLine() + 1);
        String condition = transformExpression(rawCondition, context);
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
            TypedExpression typedIterable = transformExpressionTyped(rawIterable, context);
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
        String condition = transformExpression(rawCondition, context);
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
        TypedExpression typedCondition = transformExpressionTyped(rawCondition, context);
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
        TypedExpression typedInit = rawInitializer.isBlank() ? null : transformExpressionTyped(rawInitializer, context);
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
        if (type != null) {
            context.declareVariable(name, type, !immutable);
        }

        StringBuilder out = new StringBuilder();
        if (immutable) {
            out.append("final ");
        }
        out.append(type == null ? "var" : type.declaration()).append(' ').append(name);
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
        AstExpression ast = expressionAst(expression, context);
        validateExpressionSubset(ast, context);
        validateExpressionSemantics(ast, context);
        String result = expression.trim();
        result = transformStringInterpolation(result);
        result = transformReceiverThis(result, context);
        result = transformImplicitReceiver(result, context);
        result = transformTypedLambda(result);
        result = transformExtensionCalls(result, context);
        result = transformNamedArguments(result, context);
        validateExplicitConstructorCalls(result, context);
        validateMethodCalls(result, context);
        result = transformNot(result);
        result = transformInstanceof(result);
        validateCasts(result, context);
        result = transformCast(result);
        result = transformTypeConstruction(result, context);
        result = result.replaceAll("(?<![A-Za-z0-9_.$])println\\s*\\(", "System.out.println(");
        result = transformArrayLiteral(result);
        result = transformPropertyReads(result, context);
        TypeGuess resolvedType = ast.resolvedType().isKnown() && astTypeCanShortCircuitInference(ast)
                ? ast.resolvedType()
                : inferExpressionType(expression.trim(), context);
        return new TypedExpression(result, resolvedType, ast);
    }

    private void validateExpressionSubset(AstExpression ast, MethodContext context) {
        if (ast instanceof UnsupportedExpression unsupported) {
            diagnostics.add(error(
                    context.unit.sourceFile(),
                    context.currentLine,
                    context.currentColumn,
                    unsupported.code(),
                    unsupported.message()
            ));
        }
    }

    private void validateExpressionSemantics(AstExpression ast, MethodContext context) {
        if (ast instanceof BinaryExpression binary) {
            validateExpressionSemantics(binary.left(), context);
            validateExpressionSemantics(binary.right(), context);
            if ((binary.operator().equals("||") || binary.operator().equals("&&"))
                    && (!isBooleanOperand(binary.left(), context) || !isBooleanOperand(binary.right(), context))) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CONDITION_TYPE",
                        "Boolean operators require boolean operands."
                ));
            } else if (List.of("<", "<=", ">", ">=").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CONDITION_TYPE",
                        "Relational operators require numeric operands."
                ));
            } else if (List.of("-", "*", "/", "%").contains(binary.operator())
                    && (!isNumericOperand(binary.left(), context) || !isNumericOperand(binary.right(), context))) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_OPERATOR_TYPE",
                        "Arithmetic operators require numeric operands."
                ));
            } else if (binary.operator().equals("+") && !isPlusOperandCompatible(binary.left(), binary.right(), context)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_OPERATOR_TYPE",
                        "Plus operands must be numeric or include a String operand."
                ));
            } else if ((binary.operator().equals("==") || binary.operator().equals("!="))
                    && !isEqualityCompatible(binary.left(), binary.right(), context)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_OPERATOR_TYPE",
                        "Equality operands are not comparable."
                ));
            }
            return;
        }
        if (ast instanceof UnaryExpression unary) {
            validateExpressionSemantics(unary.expression(), context);
            if (unary.operator().equals("!") && !isBooleanOperand(unary.expression(), context)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CONDITION_TYPE",
                        "Boolean negation requires a boolean operand."
                ));
            }
            return;
        }
        if (ast instanceof TernaryExpression ternary) {
            validateExpressionSemantics(ternary.condition(), context);
            validateExpressionSemantics(ternary.thenExpression(), context);
            validateExpressionSemantics(ternary.elseExpression(), context);
            if (!isBooleanConditionAst(ternary.condition(), context)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_CONDITION_TYPE",
                        "Ternary conditions must be boolean."
                ));
            }
            if (!ternaryBranchesCompatible(ternary.thenExpression(), ternary.elseExpression(), context)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
                        "AFFOGATO_TERNARY_TYPE",
                        "Ternary branches must have compatible types."
                ));
            }
            return;
        }
        if (ast instanceof InstanceOfExpression instanceOf) {
            validateExpressionSemantics(instanceOf.expression(), context);
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
            call.arguments().forEach(argument -> validateExpressionSemantics(argument, context));
            validateExpressionSemantics(call.receiver(), context);
            return;
        }
        if (ast instanceof ConstructorExpression constructor) {
            constructor.arguments().forEach(argument -> validateExpressionSemantics(argument, context));
            return;
        }
        if (ast instanceof AssignmentExpression assignment) {
            validateExpressionSemantics(assignment.target(), context);
            validateExpressionSemantics(assignment.value(), context);
            return;
        }
        if (ast instanceof CastExpression cast) {
            validateExpressionSemantics(cast.expression(), context);
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
            validateExpressionSemantics(property.receiver(), context);
            TypeGuess receiverType = property.receiver().resolvedType().isKnown()
                    ? property.receiver().resolvedType()
                    : inferExpressionType(property.receiver().source(), context);
            TypeGuess resolved = propertyType(property.source(), context);
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
            arrayLiteral.elements().forEach(element -> validateExpressionSemantics(element, context));
            return;
        }
        if (ast instanceof ArrayAccessExpression arrayAccess) {
            validateExpressionSemantics(arrayAccess.receiver(), context);
            validateExpressionSemantics(arrayAccess.index(), context);
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
                    && !context.identifierResolvesAsMember(identifier.name())
                    && classSymbol(identifier.name(), context.unit) == null
                    && !context.javaResolver.typeExists(identifier.name(), context.unit)) {
                diagnostics.add(error(
                        context.unit.sourceFile(),
                        context.currentLine,
                        context.currentColumn,
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

    // Rewrites interpolated string literals into Java string concatenation.
    // "Hi ${user.name}!" becomes "Hi " + (user.name) + "!"; the embedded expression text is
    // left raw so the rest of the transformExpression pipeline (property reads, etc.) processes it.
    // Supports ${expression} for arbitrary expressions and $identifier as shorthand; \$ is a literal dollar.
    private String transformStringInterpolation(String expression) {
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
                    int idEnd = j + 1;
                    while (idEnd < n && Character.isJavaIdentifierPart(expression.charAt(idEnd))) {
                        idEnd++;
                    }
                    exprText = expression.substring(j + 1, idEnd);
                    nextIndex = idEnd;
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

    private String transformArrayLiteral(String expression) {
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
            String elementType = inferArrayElementType(elements);
            out.append("new ").append(elementType).append("[]{").append(contents).append("}");
            index = close + 1;
        }
        return out.toString();
    }

    private String inferArrayElementType(List<String> elements) {
        if (elements.isEmpty()) {
            return "Object";
        }
        boolean allInt = elements.stream().allMatch(e -> e.matches("-?\\d+"));
        if (allInt) {
            return "int";
        }
        boolean allLong = elements.stream().allMatch(e -> e.matches("-?\\d+[lL]"));
        if (allLong) {
            return "long";
        }
        boolean allDouble = elements.stream().allMatch(e -> e.matches("-?\\d+\\.\\d+[dD]?"));
        if (allDouble) {
            return "double";
        }
        boolean allString = elements.stream().allMatch(e -> e.startsWith("\""));
        if (allString) {
            return "String";
        }
        boolean allBoolean = elements.stream().allMatch(e -> e.equals("true") || e.equals("false"));
        if (allBoolean) {
            return "boolean";
        }
        return "Object";
    }

    private void validateMethodCalls(String expression, MethodContext context) {
        int index = 0;
        while (index < expression.length()) {
            int open = expression.indexOf('(', index);
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
        if (callName.isBlank() || callName.equals("println") || callName.equals("not") || callName.equals("super") || callName.equals("this")) {
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

    private String transformInstanceof(String expression) {
        Matcher matcher = INSTANCEOF_ALIAS.matcher(expression);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + " instanceof " + eraseTypeArguments(matcher.group(2))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String eraseTypeArguments(String type) {
        int generic = type.indexOf('<');
        return generic < 0 ? type : type.substring(0, generic);
    }

    private String transformCast(String expression) {
        Matcher matcher = AS_CAST.matcher(expression);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String type = matcher.group(2);
            if (type.endsWith("?")) {
                type = type.substring(0, type.length() - 1);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("((" + type + ") " + matcher.group(1) + ")"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
            FieldSymbol field = methodCall ? null : resolveField(owner, property, context);
            if (field != null) {
                ClassSymbol ownerSymbol = ownerType == null ? null : classSymbol(ownerType, context.unit);
                String accessor = ownerSymbol != null && ownerSymbol.isRecord() ? property : getterName(property, field.type());
                out.append(owner).append('.').append(accessor).append("()");
            } else if (!methodCall && ownerType != null && isArrayLengthAccess(ownerType, property)) {
                out.append(expression, ownerStart, propertyEnd);
            } else if (!methodCall && ownerType != null && context.javaResolver.getterExists(ownerType, property, context.unit)) {
                String getter = context.javaResolver.getterInvocationName(ownerType, property, context.unit)
                        .orElse(getterName(property, TypeRef.unspecified("Object")));
                out.append(owner).append('.').append(getter).append("()");
            } else if (!methodCall && ownerType != null && context.javaResolver.fieldExists(ownerType, property, context.unit)) {
                out.append(expression, ownerStart, propertyEnd);
            } else {
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
            }
            index = propertyEnd;
        }
        return out.toString();
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
        String simple = simpleTypeName(type);
        ClassSymbol direct = classSymbols.get(simple);
        if (direct != null) {
            return direct;
        }
        if (!unit.packageName().isBlank()) {
            direct = classSymbols.get(unit.packageName() + "." + simple);
            if (direct != null) {
                return direct;
            }
        }
        return classSymbols.get(type);
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
                    diagnostics.add(new AffogatoDiagnostic(
                            AffogatoDiagnostic.Severity.WARNING,
                            "AFFOGATO_UNREACHABLE",
                            "Unreachable statement.",
                            sourceFile,
                            stmt.getStart().getLine(),
                            stmt.getStart().getCharPositionInLine() + 1
                    ));
                }
                if (statementStopsControl(stmt)) {
                    exited = true;
                }
            }
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
        if (value.matches("-?\\d+[lL]")) {
            return TypeGuess.of("long");
        }
        if (value.matches("-?\\d+")) {
            return TypeGuess.of("int");
        }
        if (value.matches("-?\\d+\\.\\d+[fF]")) {
            return TypeGuess.of("float");
        }
        if (value.matches("-?\\d+\\.\\d+[dD]?")) {
            return TypeGuess.of("double");
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
        TypeGuess ownerType = inferExpressionType(owner, context);
        if (!ownerType.isKnown() || ownerType.isNullLiteral()) {
            return TypeGuess.unknown();
        }
        if (isArrayLengthAccess(ownerType.javaType(), property)) {
            return TypeGuess.of("int");
        }
        FieldSymbol field = fieldForOwnerType(ownerType.javaType(), property, context);
        if (field != null) {
            return TypeGuess.of(field.type().javaType());
        }
        return context.javaResolver.getterReturnType(ownerType.javaType(), property, context.unit)
                .or(() -> context.javaResolver.fieldType(ownerType.javaType(), property, context.unit))
                .orElse(TypeGuess.unknown());
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
            String type = value.substring("new ".length(), value.indexOf('(')).trim();
            return TypeRef.unspecified(type);
        }
        Matcher constructor = Pattern.compile("^([A-Z][A-Za-z0-9_]*(?:<[^>]+>)?)\\s*\\(.*").matcher(value);
        if (constructor.matches()) {
            return TypeRef.unspecified(constructor.group(1));
        }
        if (value.startsWith("\"")) {
            return TypeRef.unspecified("String");
        }
        if (value.equals("true") || value.equals("false")) {
            return TypeRef.unspecified("boolean");
        }
        if (value.matches("-?\\d+[lL]")) {
            return TypeRef.unspecified("long");
        }
        if (value.matches("-?\\d+")) {
            return TypeRef.unspecified("int");
        }
        if (value.matches("-?\\d+\\.\\d+[fFdD]?")) {
            return TypeRef.unspecified("double");
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
            }
        }
        return new Modifiers(access, isStatic, isOverride);
    }

    private String accessFromClassModifiers(List<AffogatoParser.ClassModifierContext> modifiers) {
        for (AffogatoParser.ClassModifierContext modifier : modifiers) {
            if (modifier.PRIVATE() != null) {
                return "private";
            }
            if (modifier.PROTECTED() != null) {
                return "protected";
            }
            if (modifier.PUBLIC() != null) {
                return "public";
            }
        }
        return "public";
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

    private AffogatoDiagnostic error(Path sourceFile, int line, int column, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column);
    }

    public record ParsedUnit(Path sourceFile, CompilationUnit unit) {
        static ParsedUnit empty(Path sourceFile, String source) {
            return new ParsedUnit(sourceFile, new CompilationUnit(sourceFile, source, "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        }
    }

    public record GeneratedJava(String packageName, String className, String source) {
    }

    private record SourceLocation(int line, int column) {
    }

    private record CompilationUnit(Path sourceFile, String source, String packageName, List<String> imports, List<ParsedClass> classes, List<ParsedEnum> enums, List<ParsedInterface> interfaces, List<ParsedRecord> records, List<ExtensionFuncDecl> extensions) {
    }

    private record ParsedClass(
            String access,
            String name,
            List<TypeParamDecl> typeParameters,
            List<String> superTypes,
            List<ParamDecl> compactParameters,
            List<FieldDecl> fields,
            List<ConstructorDecl> constructors,
            List<MethodDecl> methods,
            List<String> annotations
    ) {
    }

    private record ParsedEnum(String access, String name, List<String> constants, List<String> annotations) {
    }

    private record ParsedRecord(
            String access,
            String name,
            List<TypeParamDecl> typeParameters,
            List<ParamDecl> components,
            List<String> superTypes,
            List<MethodDecl> methods,
            List<String> annotations
    ) {
    }

    private record InterfaceMethod(
            boolean isDefault,
            TypeRef returnType,
            String name,
            List<ParamDecl> parameters,
            AffogatoParser.BlockContext body,
            int line
    ) {
    }

    private record ParsedInterface(String access, String name, List<TypeParamDecl> typeParameters, List<InterfaceMethod> methods, List<String> annotations) {
    }

    private record FieldDecl(String access, boolean isStatic, boolean mutable, String name, TypeRef type, String initializer, int line, List<String> annotations) {
    }

    private record ConstructorDecl(String access, List<ParamDecl> parameters, AffogatoParser.BlockContext body, int line, List<String> annotations) {
    }

    private record MethodDecl(
            String access,
            boolean isStatic,
            boolean isOverride,
            List<TypeParamDecl> typeParameters,
            TypeRef returnType,
            String name,
            List<ParamDecl> parameters,
            AffogatoParser.BlockContext body,
            int line,
            List<String> annotations
    ) {
    }

    private record ExtensionFuncDecl(
            TypeRef receiverType,
            String name,
            TypeRef returnType,
            List<ParamDecl> parameters,
            AffogatoParser.BlockContext body,
            int line,
            List<String> annotations
    ) {
    }

    private record ExtensionSymbol(
            String holderPackage,
            String holderSimpleName,
            String name,
            TypeRef receiverType,
            TypeRef returnType,
            List<ParamDecl> parameters
    ) {
        String holderJavaName() {
            return holderPackage.isBlank() ? holderSimpleName : holderPackage + "." + holderSimpleName;
        }
    }

    private record TypeParamDecl(String name, String bound) {
        String declaration() {
            return bound.isBlank() ? name : name + " extends " + bound;
        }
    }

    private record ParamDecl(String name, TypeRef type, PropertyKind propertyKind, List<String> annotations) {
    }

    private record ConstructorSymbol(List<ParamDecl> parameters) {
    }

    private record MethodSymbol(String name, TypeRef returnType, List<ParamDecl> parameters, boolean isStatic) {
    }

    private record ResolvedArguments(List<String> expressions) {
    }

    private record ScoredAffogatoArguments(int score, ResolvedArguments resolved, List<ParamDecl> parameters, InvocationPhase phase) {
    }

    private record ScoredReturn(int score, TypeGuess returnType) {
    }

    private record ExtensionMatch(ExtensionSymbol symbol, ResolvedArguments resolved) {
    }

    private record ScoredExecutable(
            int score,
            ResolvedArguments resolved,
            Executable executable,
            InvocationPhase phase,
            Map<TypeVariable<?>, TypeGuess> typeBindings
    ) {
    }

    private enum PropertyKind {
        NONE,
        VAR,
        LET
    }

    private enum Nullability {
        UNSPECIFIED,
        NULLABLE,
        NOT_NULL
    }

    private enum InvocationPhase {
        STRICT,
        LOOSE,
        VARARGS
    }

    private record TypeRef(String javaType, Nullability nullability) {
        static TypeRef unspecified(String javaType) {
            return new TypeRef(javaType, Nullability.UNSPECIFIED);
        }

        String declaration() {
            return switch (nullability) {
                case NULLABLE -> "@Nullable " + javaType;
                case NOT_NULL -> "@NotNull " + javaType;
                case UNSPECIFIED -> javaType;
            };
        }

        boolean requiresRuntimeCheck() {
            return nullability == Nullability.NOT_NULL && !PRIMITIVES.contains(javaType);
        }
    }

    private record Modifiers(String access, boolean isStatic, boolean isOverride) {
    }

    private static final class MethodContext {
        final CompilationUnit unit;
        final ParsedClass currentClass;
        final String executableName;
        final TypeRef returnType;
        private final Map<String, ClassSymbol> classSymbols;
        private final Map<String, List<ExtensionSymbol>> extensionSymbols;
        private final JavaResolver javaResolver;
        final Map<String, String> variableTypes = new LinkedHashMap<>();
        final Map<String, Boolean> mutableVariables = new LinkedHashMap<>();
        final Map<String, Nullability> variableNullabilities = new LinkedHashMap<>();
        /** Non-null only when generating an extension function body; the receiver type bound to {@code $this}. */
        String receiverType;
        private String resolutionFailure = "";
        int currentLine = 1;
        int currentColumn = 1;

        private MethodContext(
                CompilationUnit unit,
                ParsedClass currentClass,
                String executableName,
                TypeRef returnType,
                Map<String, ClassSymbol> classSymbols,
                Map<String, List<ExtensionSymbol>> extensionSymbols,
                JavaResolver javaResolver
        ) {
            this.unit = unit;
            this.currentClass = currentClass;
            this.executableName = executableName;
            this.returnType = returnType;
            this.classSymbols = classSymbols;
            this.extensionSymbols = extensionSymbols;
            this.javaResolver = javaResolver;
        }

        static MethodContext forExecutable(
                CompilationUnit unit,
                ParsedClass currentClass,
                String executableName,
                TypeRef returnType,
                Map<String, ClassSymbol> classSymbols,
                Map<String, List<ExtensionSymbol>> extensionSymbols,
                JavaResolver javaResolver
        ) {
            return new MethodContext(unit, currentClass, executableName, returnType, classSymbols, extensionSymbols, javaResolver);
        }

        static MethodContext empty(
                CompilationUnit unit,
                ParsedClass currentClass,
                Map<String, ClassSymbol> classSymbols,
                Map<String, List<ExtensionSymbol>> extensionSymbols,
                JavaResolver javaResolver
        ) {
            return new MethodContext(unit, currentClass, "", TypeRef.unspecified("void"), classSymbols, extensionSymbols, javaResolver);
        }

        void declareVariable(String name, TypeRef type, boolean mutable) {
            variableTypes.put(name, type.javaType());
            mutableVariables.put(name, mutable);
            variableNullabilities.put(name, type.nullability());
        }

        ScopeSnapshot snapshotScope() {
            return new ScopeSnapshot(
                    new LinkedHashMap<>(variableTypes),
                    new LinkedHashMap<>(mutableVariables),
                    new LinkedHashMap<>(variableNullabilities)
            );
        }

        void restoreScope(ScopeSnapshot snapshot) {
            variableTypes.clear();
            variableTypes.putAll(snapshot.variableTypes());
            mutableVariables.clear();
            mutableVariables.putAll(snapshot.mutableVariables());
            variableNullabilities.clear();
            variableNullabilities.putAll(snapshot.variableNullabilities());
        }

        private record ScopeSnapshot(
                Map<String, String> variableTypes,
                Map<String, Boolean> mutableVariables,
                Map<String, Nullability> variableNullabilities
        ) {
        }

        Optional<ResolvedArguments> resolveArguments(String callName, List<TypedArgument> arguments) {
            resolutionFailure = "";
            String simpleName = callName;
            int dot = simpleName.lastIndexOf('.');
            if (dot >= 0) {
                simpleName = simpleName.substring(dot + 1);
            }
            int generic = simpleName.indexOf('<');
            if (generic >= 0) {
                simpleName = simpleName.substring(0, generic);
            }

            ClassSymbol constructorTarget = classSymbols.get(simpleName);
            if (constructorTarget != null) {
                Optional<ScoredAffogatoArguments> constructor = resolveAffogatoCandidates(
                        callName,
                        constructorTarget.constructors.stream().map(ConstructorSymbol::parameters).toList(),
                        arguments
                );
                if (constructor.isPresent()) {
                    return Optional.of(constructor.get().resolved());
                }
            }

            List<MethodSymbol> currentMethods = affogatoMethods(currentClass.name(), simpleName, unit);
            if (!currentMethods.isEmpty()) {
                Optional<ScoredAffogatoArguments> method = resolveAffogatoCandidates(
                        callName,
                        currentMethods.stream().map(MethodSymbol::parameters).toList(),
                        arguments
                );
                if (method.isPresent()) {
                    return Optional.of(method.get().resolved());
                }
            }

            Optional<ResolvedArguments> staticImport = javaResolver.resolveStaticMethodArguments(simpleName, arguments, unit);
            if (staticImport.isPresent()) {
                return staticImport;
            }
            if (javaResolver.lastResolutionAmbiguous()) {
                resolutionFailure = "Ambiguous overload for call " + callName + ".";
                return Optional.empty();
            }

            if (callName.contains(".")) {
                String owner = callName.substring(0, callName.lastIndexOf('.'));
                String method = callName.substring(callName.lastIndexOf('.') + 1);
                String ownerType = resolveOwnerType(owner);
                List<MethodSymbol> ownerMethods = affogatoMethods(ownerType, method, unit);
                if (!ownerMethods.isEmpty()) {
                    Optional<ScoredAffogatoArguments> affogatoMethod = resolveAffogatoCandidates(
                            callName,
                            ownerMethods.stream().map(MethodSymbol::parameters).toList(),
                            arguments
                    );
                    if (affogatoMethod.isPresent()) {
                        return Optional.of(affogatoMethod.get().resolved());
                    }
                }
                Optional<ResolvedArguments> javaMethod = javaResolver.resolveMethodArguments(ownerType, method, arguments, unit);
                if (javaMethod.isEmpty() && javaResolver.lastResolutionAmbiguous()) {
                    resolutionFailure = "Ambiguous overload for call " + callName + ".";
                }
                return javaMethod;
            }

            Optional<ResolvedArguments> javaConstructor = javaResolver.resolveConstructorArguments(callName, arguments, unit);
            if (javaConstructor.isEmpty() && javaResolver.lastResolutionAmbiguous()) {
                resolutionFailure = "Ambiguous overload for call " + callName + ".";
            }
            return javaConstructor;
        }

        String resolutionFailure() {
            return resolutionFailure;
        }

        boolean hasCurrentMethod(String methodName) {
            return !affogatoMethods(currentClass.name(), methodName, unit).isEmpty();
        }

        boolean hasStaticImport(String methodName) {
            return unit.imports().stream().anyMatch(importName -> {
                if (!importName.startsWith("static ")) {
                    return false;
                }
                String cleaned = importName.substring("static ".length()).trim();
                return cleaned.endsWith(".*") || cleaned.endsWith("." + methodName);
            });
        }

        TypeGuess returnType(String callName, List<TypedArgument> arguments) {
            String simpleName = callName;
            int dot = simpleName.lastIndexOf('.');
            if (dot >= 0) {
                simpleName = simpleName.substring(dot + 1);
            }

            List<MethodSymbol> currentMethods = affogatoMethods(currentClass.name(), simpleName, unit);
            if (!callName.contains(".") && !currentMethods.isEmpty()) {
                return currentMethods.stream()
                        .map(candidate -> new ScoredReturn(
                                scoreAffogatoParameters(candidate.parameters(), arguments, InvocationPhase.LOOSE).map(ScoredAffogatoArguments::score).orElse(NO_CONVERSION),
                                TypeGuess.of(candidate.returnType().javaType())
                        ))
                        .filter(candidate -> candidate.score() < NO_CONVERSION)
                        .min(Comparator.comparingInt(ScoredReturn::score))
                        .map(ScoredReturn::returnType)
                        .orElse(TypeGuess.unknown());
            }

            if (!callName.contains(".")) {
                TypeGuess staticImportReturnType = javaResolver.staticMethodReturnType(simpleName, arguments, unit)
                        .orElse(TypeGuess.unknown());
                if (staticImportReturnType.isKnown()) {
                    return staticImportReturnType;
                }
            }

            if (callName.contains(".")) {
                String owner = callName.substring(0, callName.lastIndexOf('.'));
                String method = callName.substring(callName.lastIndexOf('.') + 1);
                return returnTypeForReceiverType(resolveOwnerType(owner), method, arguments);
            }

            return TypeGuess.unknown();
        }

        /** Return type of {@code method(args)} invoked on a receiver of static type {@code ownerType}. */
        TypeGuess returnTypeForReceiverType(String ownerType, String method, List<TypedArgument> arguments) {
            List<MethodSymbol> ownerMethods = affogatoMethods(ownerType, method, unit);
            if (!ownerMethods.isEmpty()) {
                Optional<TypeGuess> affogatoReturn = ownerMethods.stream()
                        .map(candidate -> new ScoredReturn(
                                scoreAffogatoParameters(candidate.parameters(), arguments, InvocationPhase.LOOSE).map(ScoredAffogatoArguments::score).orElse(NO_CONVERSION),
                                TypeGuess.of(candidate.returnType().javaType())
                        ))
                        .filter(candidate -> candidate.score() < NO_CONVERSION)
                        .min(Comparator.comparingInt(ScoredReturn::score))
                        .map(ScoredReturn::returnType);
                if (affogatoReturn.isPresent()) {
                    return affogatoReturn.get();
                }
            }
            TypeGuess javaReturn = javaResolver.methodReturnType(ownerType, method, arguments, unit)
                    .orElse(TypeGuess.unknown());
            if (javaReturn.isKnown()) {
                return javaReturn;
            }
            // Extension functions are the last fallback, after instance methods fail to resolve.
            return resolveExtensionCall(ownerType, method, arguments)
                    .map(match -> TypeGuess.of(match.symbol().returnType().javaType()))
                    .orElse(TypeGuess.unknown());
        }

        private String resolveOwnerType(String owner) {
            if ("this".equals(owner)) {
                return currentClass.name();
            }
            if ("super".equals(owner) && !currentClass.superTypes().isEmpty()) {
                for (String superType : currentClass.superTypes()) {
                    ClassSymbol symbol = classSymbols.get(superType);
                    if (symbol == null || !symbol.isInterface()) {
                        return superType;
                    }
                }
                return currentClass.superTypes().get(0);
            }
            return variableTypes.getOrDefault(owner, owner);
        }

        /**
         * Resolves an extension function call {@code receiver.method(args)} for a receiver of static type
         * {@code ownerType}. Extension functions are dispatched statically and only consulted after instance
         * methods fail. Candidates match the exact receiver type plus the Affogato supertype chain.
         */
        Optional<ExtensionMatch> resolveExtensionCall(String ownerType, String methodName, List<TypedArgument> arguments) {
            List<ExtensionSymbol> candidates = extensionCandidates(ownerType, methodName);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE)) {
                ExtensionMatch best = null;
                int bestScore = NO_CONVERSION;
                for (ExtensionSymbol candidate : candidates) {
                    Optional<ScoredAffogatoArguments> scored = scoreAffogatoParameters(candidate.parameters(), arguments, phase);
                    if (scored.isPresent() && scored.get().score() < bestScore) {
                        bestScore = scored.get().score();
                        best = new ExtensionMatch(candidate, scored.get().resolved());
                    }
                }
                if (best != null) {
                    return Optional.of(best);
                }
            }
            return Optional.empty();
        }

        /**
         * Decides whether {@code receiver.method(args)} dispatches to an extension function. Mirrors the
         * member-wins precedence of {@link #resolveArguments}: returns a match only when no instance method
         * (Affogato or Java) resolves for {@code ownerType} and an extension does.
         */
        Optional<ExtensionMatch> dispatchExtension(String ownerType, String methodName, List<TypedArgument> arguments) {
            if (instanceMethodResolves(ownerType, methodName, arguments)) {
                return Optional.empty();
            }
            return resolveExtensionCall(ownerType, methodName, arguments);
        }

        private boolean instanceMethodResolves(String ownerType, String methodName, List<TypedArgument> arguments) {
            for (MethodSymbol candidate : affogatoMethods(ownerType, methodName, unit)) {
                for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE)) {
                    if (scoreAffogatoParameters(candidate.parameters(), arguments, phase).isPresent()) {
                        return true;
                    }
                }
            }
            return javaResolver.resolveMethodArguments(ownerType, methodName, arguments, unit).isPresent();
        }

        /** True when the extension receiver type exposes a (possibly inherited) field or getter named {@code name}. */
        boolean receiverHasField(String name) {
            if (receiverType == null) {
                return false;
            }
            if (affogatoFieldExists(receiverType, name)) {
                return true;
            }
            return javaResolver.getterExists(receiverType, name, unit) || javaResolver.fieldExists(receiverType, name, unit);
        }

        /** True when the extension receiver type exposes a (possibly inherited) method named {@code name}. */
        boolean receiverHasMethod(String name) {
            if (receiverType == null) {
                return false;
            }
            if (!affogatoMethods(receiverType, name, unit).isEmpty()) {
                return true;
            }
            return javaResolver.hasMethodNamed(receiverType, name, unit);
        }

        boolean identifierResolvesAsMember(String name) {
            return identifierType(name).isPresent();
        }

        Optional<String> identifierType(String name) {
            String localType = variableTypes.get(name);
            if (localType != null) {
                return Optional.of(localType);
            }
            if (currentClass != null) {
                Optional<String> fieldType = currentClass.fields().stream()
                        .filter(field -> field.name().equals(name))
                        .map(field -> field.type().javaType())
                        .findFirst();
                if (fieldType.isPresent()) {
                    return fieldType;
                }
                Optional<String> componentType = currentClass.compactParameters().stream()
                        .filter(parameter -> parameter.name().equals(name))
                        .map(parameter -> parameter.type().javaType())
                        .findFirst();
                if (componentType.isPresent()) {
                    return componentType;
                }
            }
            if (receiverType != null) {
                Optional<String> receiverFieldType = affogatoFieldType(receiverType, name)
                        .map(TypeRef::javaType)
                        .or(() -> javaResolver.getterReturnType(receiverType, name, unit).map(TypeGuess::javaType))
                        .or(() -> javaResolver.fieldType(receiverType, name, unit).map(TypeGuess::javaType));
                if (receiverFieldType.isPresent()) {
                    return receiverFieldType;
                }
            }
            return Optional.empty();
        }

        private boolean affogatoFieldExists(String type, String name) {
            return affogatoFieldType(type, name).isPresent();
        }

        private Optional<TypeRef> affogatoFieldType(String type, String name) {
            Set<String> seen = new LinkedHashSet<>();
            String current = type;
            while (current != null && !current.isBlank()) {
                ClassSymbol symbol = affogatoClassSymbol(current, unit);
                if (symbol == null || !seen.add(symbol.name())) {
                    break;
                }
                FieldSymbol field = symbol.fields.get(name);
                if (field != null) {
                    return Optional.of(field.type());
                }
                current = symbol.extendsType();
            }
            return Optional.empty();
        }

        private List<ExtensionSymbol> extensionCandidates(String ownerType, String methodName) {
            List<ExtensionSymbol> result = new ArrayList<>();
            collectExtensionCandidates(ownerType, methodName, result, new LinkedHashSet<>());
            return result;
        }

        private void collectExtensionCandidates(String type, String methodName, List<ExtensionSymbol> out, Set<String> seen) {
            if (type == null || type.isBlank()) {
                return;
            }
            String simple = simpleTypeName(type);
            if (!seen.add(simple)) {
                return;
            }
            for (ExtensionSymbol candidate : extensionSymbols.getOrDefault(simple, List.of())) {
                if (candidate.name().equals(methodName)) {
                    out.add(candidate);
                }
            }
            ClassSymbol symbol = affogatoClassSymbol(type, unit);
            if (symbol != null && !symbol.extendsType().isBlank()) {
                collectExtensionCandidates(symbol.extendsType(), methodName, out, seen);
            } else if (symbol == null) {
                // Not an Affogato type: walk the Java superclass/interface chain so an extension declared on a
                // supertype (e.g. CharSequence) is visible on a subtype receiver (e.g. String).
                for (String ancestor : javaResolver.ancestorSimpleNames(type, unit)) {
                    collectExtensionCandidates(ancestor, methodName, out, seen);
                }
            }
        }

        private List<MethodSymbol> affogatoMethods(String ownerType, String methodName, CompilationUnit unit) {
            return affogatoMethods(ownerType, methodName, unit, new LinkedHashSet<>());
        }

        private List<MethodSymbol> affogatoMethods(String ownerType, String methodName, CompilationUnit unit, Set<String> seen) {
            ClassSymbol symbol = affogatoClassSymbol(ownerType, unit);
            if (symbol == null || !seen.add(symbol.name())) {
                return List.of();
            }
            List<MethodSymbol> methods = new ArrayList<>();
            methods.addAll(symbol.methods.getOrDefault(methodName, List.of()));
            if (!symbol.extendsType().isBlank()) {
                methods.addAll(affogatoMethods(symbol.extendsType(), methodName, unit, seen));
            }
            return methods;
        }

        private ClassSymbol affogatoClassSymbol(String type, CompilationUnit unit) {
            String simple = simpleTypeName(type);
            ClassSymbol symbol = classSymbols.get(type);
            if (symbol != null) {
                return symbol;
            }
            symbol = classSymbols.get(simple);
            if (symbol != null) {
                return symbol;
            }
            if (!unit.packageName().isBlank()) {
                symbol = classSymbols.get(unit.packageName() + "." + simple);
                if (symbol != null) {
                    return symbol;
                }
            }
            return null;
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

        private Optional<ScoredAffogatoArguments> resolveAffogatoCandidates(String callName, List<List<ParamDecl>> candidates, List<TypedArgument> arguments) {
            for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE)) {
                List<ScoredAffogatoArguments> matches = candidates.stream()
                        .map(candidate -> scoreAffogatoParameters(candidate, arguments, phase))
                        .flatMap(Optional::stream)
                        .toList();
                if (!matches.isEmpty()) {
                    Optional<ScoredAffogatoArguments> selected = chooseMostSpecificAffogato(matches);
                    if (selected.isEmpty()) {
                        resolutionFailure = "Ambiguous overload for call " + callName + ".";
                    }
                    return selected;
                }
            }
            return Optional.empty();
        }

        private Optional<ScoredAffogatoArguments> chooseMostSpecificAffogato(List<ScoredAffogatoArguments> matches) {
            int bestScore = matches.stream().mapToInt(ScoredAffogatoArguments::score).min().orElse(NO_CONVERSION);
            List<ScoredAffogatoArguments> best = matches.stream()
                    .filter(match -> match.score() == bestScore)
                    .toList();
            if (best.size() == 1) {
                return Optional.of(best.get(0));
            }
            for (ScoredAffogatoArguments candidate : best) {
                boolean moreSpecificThanAll = best.stream()
                        .filter(other -> other != candidate)
                        .allMatch(other -> javaResolver.affogatoParametersMoreSpecific(candidate.parameters(), other.parameters(), unit));
                if (moreSpecificThanAll) {
                    return Optional.of(candidate);
                }
            }
            // Maximally-specific rule: override-equivalent candidates (same parameter types reached through
            // an override of a superclass method) are not ambiguous. affogatoMethods collects the most-derived
            // class first, so the first match is the effective override.
            ScoredAffogatoArguments first = best.get(0);
            boolean overrideEquivalent = best.stream()
                    .allMatch(candidate -> sameParameterTypes(candidate.parameters(), first.parameters()));
            return overrideEquivalent ? Optional.of(first) : Optional.empty();
        }

        private boolean sameParameterTypes(List<ParamDecl> left, List<ParamDecl> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!javaResolver.rawClassName(left.get(index).type().javaType()).equals(javaResolver.rawClassName(right.get(index).type().javaType()))) {
                    return false;
                }
            }
            return true;
        }

        private Optional<ScoredAffogatoArguments> scoreAffogatoParameters(
                List<ParamDecl> parameters,
                List<TypedArgument> arguments,
                InvocationPhase phase
        ) {
            if (arguments.size() != parameters.size()) {
                return Optional.empty();
            }
            List<TypedArgument> ordered = new ArrayList<>();
            for (int index = 0; index < parameters.size(); index++) {
                ordered.add(null);
            }

            // Bind named arguments to their slots first, then fill the remaining slots with
            // positional arguments left-to-right. This lets a positional argument follow a named
            // one (e.g. a Swift-style trailing closure after a named argument: f(value = x) { ... }).
            for (TypedArgument argument : arguments) {
                if (argument.name().isBlank()) {
                    continue;
                }
                int parameterIndex = -1;
                for (int index = 0; index < parameters.size(); index++) {
                    if (parameters.get(index).name().equals(argument.name())) {
                        parameterIndex = index;
                        break;
                    }
                }
                if (parameterIndex < 0 || ordered.get(parameterIndex) != null) {
                    return Optional.empty();
                }
                ordered.set(parameterIndex, argument);
            }
            int slot = 0;
            for (TypedArgument argument : arguments) {
                if (!argument.name().isBlank()) {
                    continue;
                }
                while (slot < parameters.size() && ordered.get(slot) != null) {
                    slot++;
                }
                if (slot >= parameters.size()) {
                    return Optional.empty();
                }
                ordered.set(slot++, argument);
            }

            List<String> expressions = new ArrayList<>();
            int score = 0;
            for (int index = 0; index < parameters.size(); index++) {
                TypedArgument argument = ordered.get(index);
                if (argument == null) {
                    return Optional.empty();
                }
                int conversion = javaResolver.conversionScore(argument, parameters.get(index).type().javaType(), unit, phase);
                if (conversion >= NO_CONVERSION) {
                    return Optional.empty();
                }
                score += conversion;
                expressions.add(argument.expression());
            }
            return Optional.of(new ScoredAffogatoArguments(score, new ResolvedArguments(expressions), parameters, phase));
        }
    }

    private static final class ClassSymbol {
        private final String packageName;
        private final String name;
        private final String extendsType;
        private final boolean isInterface;
        private boolean isRecord;
        private final List<String> typeParamNames;
        private final Map<String, FieldSymbol> fields = new LinkedHashMap<>();
        private final Map<String, List<MethodSymbol>> methods = new LinkedHashMap<>();
        private final List<ConstructorSymbol> constructors = new ArrayList<>();

        private ClassSymbol(String packageName, String name, String extendsType, boolean isInterface, List<String> typeParamNames) {
            this.packageName = packageName;
            this.name = name;
            this.extendsType = extendsType;
            this.isInterface = isInterface;
            this.typeParamNames = typeParamNames;
        }

        private String name() {
            return name;
        }

        private String extendsType() {
            return extendsType;
        }

        private boolean isInterface() {
            return isInterface;
        }

        private boolean isRecord() {
            return isRecord;
        }
    }

    private record FieldSymbol(String name, TypeRef type, boolean mutable) {
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
            diagnostics.add(error(sourceFile, line, charPositionInLine + 1, "AFFOGATO_PARSE", message));
        }

        private boolean hadErrors() {
            return hadErrors;
        }
    }

    private final class JavaResolver {
        private final URLClassLoader classLoader;
        private boolean lastResolutionAmbiguous;

        private JavaResolver(List<Path> classpath) {
            List<URL> urls = new ArrayList<>();
            for (Path path : classpath) {
                try {
                    urls.add(path.toUri().toURL());
                } catch (MalformedURLException ignored) {
                    // Invalid classpath entries are ignored here; javac/Gradle will report them later.
                }
            }
            this.classLoader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        }

        private boolean getterExists(String ownerType, String property, CompilationUnit unit) {
            return getterReturnType(ownerType, property, unit).isPresent();
        }

        private boolean hasMethodNamed(String ownerType, String name, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .map(type -> methodsForInvocation(type, unit).stream().anyMatch(method -> method.getName().equals(name)))
                    .orElse(false);
        }

        private boolean typeExists(String typeName, CompilationUnit unit) {
            return classForType(typeName, unit).isPresent();
        }

        private boolean isInterface(String typeName, CompilationUnit unit) {
            return classForType(typeName, unit).map(Class::isInterface).orElse(false);
        }

        private boolean switchSelectorCompatible(TypeGuess selectorType, CompilationUnit unit) {
            if (!selectorType.isKnown() || selectorType.isNullLiteral() || selectorType.isLambda()) {
                return false;
            }
            return switch (simpleType(selectorType.javaType())) {
                case "byte", "short", "char", "int",
                     "Byte", "Short", "Character", "Integer",
                     "String" -> true;
                case "boolean", "Boolean", "long", "Long", "float", "Float", "double", "Double" -> false;
                default -> classForType(selectorType.javaType(), unit)
                        .map(Class::isEnum)
                        .orElse(true);
            };
        }

        private boolean throwableCompatible(TypeGuess type, CompilationUnit unit) {
            return classForType(type.javaType(), unit)
                    .map(Throwable.class::isAssignableFrom)
                    .orElse(true);
        }

        private Optional<TypeGuess> getterReturnType(String ownerType, String property, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> getterMethod(type, getterName(property, TypeRef.unspecified("Object")))
                            .or(() -> getterMethod(type, "is" + capitalize(property))))
                    .map(method -> TypeGuess.of(typeName(method.getReturnType())));
        }

        private Optional<String> getterInvocationName(String ownerType, String property, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> getterMethod(type, getterName(property, TypeRef.unspecified("Object")))
                            .or(() -> getterMethod(type, "is" + capitalize(property))))
                    .map(Method::getName);
        }

        private boolean fieldExists(String ownerType, String fieldName, CompilationUnit unit) {
            return fieldType(ownerType, fieldName, unit).isPresent();
        }

        private Optional<TypeGuess> fieldType(String ownerType, String fieldName, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> fieldForInvocation(type, fieldName, unit))
                    .map(field -> TypeGuess.of(genericTypeName(
                            field.getGenericType(),
                            classTypeBindings(field.getDeclaringClass(), ownerType),
                            unit
                    )));
        }

        private boolean fieldMutable(String ownerType, String fieldName, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> fieldForInvocation(type, fieldName, unit))
                    .map(field -> !Modifier.isFinal(field.getModifiers()))
                    .orElse(false);
        }

        private boolean setterExists(String ownerType, String property, CompilationUnit unit) {
            return setterParameterType(ownerType, property, unit).isPresent();
        }

        private Optional<TypeGuess> setterParameterType(String ownerType, String property, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> setterMethod(type, setterName(property), unit))
                    .map(method -> TypeGuess.of(genericTypeName(
                            method.getGenericParameterTypes()[0],
                            classTypeBindings(method.getDeclaringClass(), ownerType),
                            unit
                    )));
        }

        private Optional<ResolvedArguments> resolveConstructorArguments(String className, List<TypedArgument> arguments, CompilationUnit unit) {
            return loadClass(className, unit)
                    .flatMap(type -> resolveExecutableArguments(constructorsForInvocation(type, unit), arguments, unit, className)
                            .map(ScoredExecutable::resolved));
        }

        private Optional<ResolvedArguments> resolveMethodArguments(String ownerType, String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> resolveExecutableArguments(
                            methodsForInvocation(type, unit).stream()
                                    .filter(method -> method.getName().equals(methodName))
                                    .sorted(Comparator.comparing(Method::toString))
                                    .map(method -> (Executable) method)
                                    .toList(),
                            arguments,
                            unit,
                            ownerType
                    ).map(ScoredExecutable::resolved));
        }

        private Optional<ResolvedArguments> resolveStaticMethodArguments(String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
            return resolveExecutableArguments(staticMethodExecutables(methodName, unit), arguments, unit, "")
                    .map(ScoredExecutable::resolved);
        }

        private Optional<TypeGuess> methodReturnType(String ownerType, String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
            return loadClass(ownerType, unit)
                    .flatMap(type -> resolveExecutableArguments(
                            methodsForInvocation(type, unit).stream()
                                    .filter(method -> method.getName().equals(methodName))
                                    .sorted(Comparator.comparing(Method::toString))
                                    .map(method -> (Executable) method)
                                    .toList(),
                            arguments,
                            unit,
                            ownerType
                    ))
                    .filter(match -> match.executable() instanceof Method)
                    .map(match -> TypeGuess.of(genericTypeName(
                            ((Method) match.executable()).getGenericReturnType(),
                            match.typeBindings(),
                            unit
                    )));
        }

        private Optional<TypeGuess> staticMethodReturnType(String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
            return resolveExecutableArguments(staticMethodExecutables(methodName, unit), arguments, unit, "")
                    .filter(match -> match.executable() instanceof Method)
                    .map(match -> TypeGuess.of(genericTypeName(
                            ((Method) match.executable()).getGenericReturnType(),
                            match.typeBindings(),
                            unit
                    )));
        }

        private List<Executable> staticMethodExecutables(String methodName, CompilationUnit unit) {
            List<Executable> executables = new ArrayList<>();
            for (String ownerType : staticMethodOwners(methodName, unit)) {
                loadClass(ownerType, unit).ifPresent(type -> methodsForInvocation(type, unit).stream()
                        .filter(method -> method.getName().equals(methodName))
                        .filter(method -> Modifier.isStatic(method.getModifiers()))
                        .sorted(Comparator.comparing(Method::toString))
                        .map(method -> (Executable) method)
                        .forEach(executables::add));
            }
            return executables;
        }

        private List<String> staticMethodOwners(String methodName, CompilationUnit unit) {
            List<String> owners = new ArrayList<>();
            for (String importName : unit.imports()) {
                if (!importName.startsWith("static ")) {
                    continue;
                }
                String cleaned = importName.substring("static ".length()).trim();
                if (cleaned.endsWith(".*")) {
                    owners.add(cleaned.substring(0, cleaned.length() - 2));
                } else if (cleaned.endsWith("." + methodName)) {
                    owners.add(cleaned.substring(0, cleaned.length() - methodName.length() - 1));
                }
            }
            return owners.stream().distinct().toList();
        }

        private List<Constructor<?>> constructorsForInvocation(Class<?> type, CompilationUnit unit) {
            List<Constructor<?>> constructors = new ArrayList<>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (isAccessible(constructor, unit)) {
                    constructors.add(constructor);
                }
            }
            return constructors;
        }

        private List<Method> methodsForInvocation(Class<?> type, CompilationUnit unit) {
            Map<String, Method> methods = new LinkedHashMap<>();
            for (Method method : type.getMethods()) {
                if (isAccessible(method, unit)) {
                    methods.put(methodSignatureKey(method), method);
                }
            }
            Class<?> cursor = type;
            while (cursor != null) {
                for (Method method : cursor.getDeclaredMethods()) {
                    if (isAccessible(method, unit)) {
                        methods.putIfAbsent(methodSignatureKey(method), method);
                    }
                }
                cursor = cursor.getSuperclass();
            }
            return new ArrayList<>(methods.values());
        }

        private boolean isAccessible(Executable executable, CompilationUnit unit) {
            int modifiers = executable.getModifiers();
            if (Modifier.isPrivate(modifiers)) {
                return false;
            }
            if (Modifier.isPublic(modifiers)) {
                return true;
            }
            return executable.getDeclaringClass().getPackageName().equals(unit.packageName());
        }

        private boolean isAccessible(Field field, CompilationUnit unit) {
            int modifiers = field.getModifiers();
            if (Modifier.isPrivate(modifiers)) {
                return false;
            }
            if (Modifier.isPublic(modifiers)) {
                return true;
            }
            return field.getDeclaringClass().getPackageName().equals(unit.packageName());
        }

        private String methodSignatureKey(Method method) {
            return method.getDeclaringClass().getName() + "#" + method.getName() + parameterDescriptor(method.getParameterTypes());
        }

        private Optional<ScoredExecutable> resolveExecutableArguments(
                List<? extends Executable> executables,
                List<TypedArgument> arguments,
                CompilationUnit unit,
                String ownerType
        ) {
            lastResolutionAmbiguous = false;
            for (InvocationPhase phase : List.of(InvocationPhase.STRICT, InvocationPhase.LOOSE, InvocationPhase.VARARGS)) {
                List<ScoredExecutable> matches = new ArrayList<>();
                for (Executable executable : executables) {
                    scoreExecutable(executable, arguments, unit, phase, ownerType).ifPresent(matches::add);
                }
                if (!matches.isEmpty()) {
                    Optional<ScoredExecutable> selected = chooseMostSpecificExecutable(matches, arguments.size(), unit);
                    if (selected.isEmpty()) {
                        lastResolutionAmbiguous = true;
                    }
                    return selected;
                }
            }
            return Optional.empty();
        }

        private boolean lastResolutionAmbiguous() {
            return lastResolutionAmbiguous;
        }

        private Optional<ScoredExecutable> chooseMostSpecificExecutable(
                List<ScoredExecutable> matches,
                int argumentCount,
                CompilationUnit unit
        ) {
            int bestScore = matches.stream().mapToInt(ScoredExecutable::score).min().orElse(NO_CONVERSION);
            List<ScoredExecutable> best = matches.stream()
                    .filter(match -> match.score() == bestScore)
                    .sorted(Comparator.comparing(match -> match.executable().toString()))
                    .toList();
            if (best.size() == 1) {
                return Optional.of(best.get(0));
            }
            for (ScoredExecutable candidate : best) {
                boolean moreSpecificThanAll = best.stream()
                        .filter(other -> other != candidate)
                        .allMatch(other -> executableMoreSpecific(candidate.executable(), other.executable(), candidate.phase(), argumentCount, unit));
                if (moreSpecificThanAll) {
                    return Optional.of(candidate);
                }
            }
            return mostSpecificOverrideEquivalent(best, argumentCount);
        }

        /**
         * Maximally-specific rule (JLS 15.12.2.5): when the tied candidates are override-equivalent — same
         * effective parameter signature, e.g. one method inherited through two unrelated interfaces — they
         * are not really ambiguous. Pick a concrete method over an abstract one, then the most specific
         * return type, then deterministically by signature.
         */
        private Optional<ScoredExecutable> mostSpecificOverrideEquivalent(List<ScoredExecutable> best, int argumentCount) {
            List<Class<?>> signature = effectiveParameterTypes(best.get(0).executable(), best.get(0).phase(), argumentCount);
            boolean overrideEquivalent = best.stream().allMatch(candidate ->
                    effectiveParameterTypes(candidate.executable(), candidate.phase(), argumentCount).equals(signature));
            if (!overrideEquivalent) {
                return Optional.empty();
            }
            if (best.stream().anyMatch(candidate -> Modifier.isStatic(candidate.executable().getModifiers()))) {
                Class<?> declaringClass = best.get(0).executable().getDeclaringClass();
                boolean sameStaticOwner = best.stream().allMatch(candidate ->
                        Modifier.isStatic(candidate.executable().getModifiers())
                                && candidate.executable().getDeclaringClass().equals(declaringClass));
                if (!sameStaticOwner) {
                    return Optional.empty();
                }
            }
            return best.stream().min(Comparator
                    .comparingInt((ScoredExecutable candidate) -> Modifier.isAbstract(candidate.executable().getModifiers()) ? 1 : 0)
                    .thenComparingInt(candidate -> -returnTypeSpecificity(candidate, best))
                    .thenComparing(candidate -> candidate.executable().toString()));
        }

        /** Counts how many other candidates' return types are supertypes of this one's — higher means more specific. */
        private int returnTypeSpecificity(ScoredExecutable candidate, List<ScoredExecutable> best) {
            if (!(candidate.executable() instanceof Method method)) {
                return 0;
            }
            Class<?> returnType = method.getReturnType();
            int specificity = 0;
            for (ScoredExecutable other : best) {
                if (other != candidate
                        && other.executable() instanceof Method otherMethod
                        && otherMethod.getReturnType().isAssignableFrom(returnType)) {
                    specificity++;
                }
            }
            return specificity;
        }

        private Optional<ScoredExecutable> scoreExecutable(
                Executable executable,
                List<TypedArgument> arguments,
                CompilationUnit unit,
                InvocationPhase phase,
                String ownerType
        ) {
            Parameter[] parameters = executable.getParameters();
            boolean hasNamedArguments = arguments.stream().anyMatch(argument -> !argument.name().isBlank());
            if (hasNamedArguments) {
                for (Parameter parameter : parameters) {
                    if (!parameter.isNamePresent()) {
                        return Optional.empty();
                    }
                }
            }

            if (phase == InvocationPhase.VARARGS) {
                if (!executable.isVarArgs() || arguments.size() < parameters.length - 1) {
                    return Optional.empty();
                }
            } else if (arguments.size() != parameters.length) {
                return Optional.empty();
            }

            List<List<TypedArgument>> assigned = new ArrayList<>();
            for (int index = 0; index < parameters.length; index++) {
                assigned.add(new ArrayList<>());
            }

            int positionalIndex = 0;
            for (TypedArgument argument : arguments) {
                int parameterIndex;
                if (argument.name().isBlank()) {
                    if (phase == InvocationPhase.VARARGS && positionalIndex >= parameters.length - 1) {
                        parameterIndex = parameters.length - 1;
                    } else {
                        parameterIndex = positionalIndex;
                    }
                    positionalIndex++;
                } else {
                    parameterIndex = -1;
                    for (int index = 0; index < parameters.length; index++) {
                        if (parameters[index].getName().equals(argument.name())) {
                            parameterIndex = index;
                            break;
                        }
                    }
                }

                if (parameterIndex < 0 || parameterIndex >= parameters.length) {
                    return Optional.empty();
                }
                if (phase != InvocationPhase.VARARGS || parameterIndex < parameters.length - 1) {
                    if (!assigned.get(parameterIndex).isEmpty()) {
                        return Optional.empty();
                    }
                }
                assigned.get(parameterIndex).add(argument);
            }

            Map<TypeVariable<?>, TypeGuess> typeBindings = inferTypeBindings(executable, assigned, unit, phase, ownerType);
            if (!validTypeBindings(typeBindings, unit)) {
                return Optional.empty();
            }
            List<String> expressions = new ArrayList<>();
            int score = 0;
            for (int index = 0; index < parameters.length; index++) {
                Parameter parameter = parameters[index];
                List<TypedArgument> parameterArguments = assigned.get(index);
                if (phase == InvocationPhase.VARARGS && index == parameters.length - 1) {
                    if (parameterArguments.isEmpty()) {
                        continue;
                    }
                    Type componentGenericType = genericComponentType(parameter.getParameterizedType());
                    if (parameterArguments.size() == 1) {
                        int arrayConversion = conversionScore(parameterArguments.get(0), parameter.getParameterizedType(), typeBindings, unit, InvocationPhase.LOOSE);
                        int componentConversion = conversionScore(parameterArguments.get(0), componentGenericType, typeBindings, unit, InvocationPhase.LOOSE);
                        int conversion = Math.min(arrayConversion, componentConversion);
                        if (conversion >= NO_CONVERSION) {
                            return Optional.empty();
                        }
                        score += conversion;
                        expressions.add(parameterArguments.get(0).expression());
                    } else {
                        for (TypedArgument argument : parameterArguments) {
                            int conversion = conversionScore(argument, componentGenericType, typeBindings, unit, InvocationPhase.LOOSE);
                            if (conversion >= NO_CONVERSION) {
                                return Optional.empty();
                            }
                            score += conversion;
                            expressions.add(argument.expression());
                        }
                    }
                    continue;
                }

                if (parameterArguments.size() != 1) {
                    return Optional.empty();
                }
                TypedArgument argument = parameterArguments.get(0);
                int conversion = conversionScore(argument, parameter.getParameterizedType(), typeBindings, unit, phase);
                if (conversion >= NO_CONVERSION) {
                    return Optional.empty();
                }
                score += conversion;
                expressions.add(argument.expression());
            }
            return Optional.of(new ScoredExecutable(score, new ResolvedArguments(expressions), executable, phase, typeBindings));
        }

        private Map<TypeVariable<?>, TypeGuess> inferTypeBindings(
                Executable executable,
                List<List<TypedArgument>> assigned,
                CompilationUnit unit,
                InvocationPhase phase,
                String ownerType
        ) {
            Map<TypeVariable<?>, TypeGuess> bindings = new LinkedHashMap<>(classTypeBindings(executable.getDeclaringClass(), ownerType));
            Parameter[] parameters = executable.getParameters();
            for (int index = 0; index < parameters.length; index++) {
                List<TypedArgument> arguments = assigned.get(index);
                if (arguments.isEmpty()) {
                    continue;
                }
                if (phase == InvocationPhase.VARARGS && index == parameters.length - 1) {
                    Type componentType = genericComponentType(parameters[index].getParameterizedType());
                    if (arguments.size() == 1 && arrayType(arguments.get(0).type()).isPresent()) {
                        inferFromTypes(parameters[index].getParameterizedType(), arguments.get(0).type(), bindings, unit);
                    } else {
                        for (TypedArgument argument : arguments) {
                            inferFromTypes(componentType, argument.type(), bindings, unit);
                        }
                    }
                } else {
                    inferFromTypes(parameters[index].getParameterizedType(), arguments.get(0).type(), bindings, unit);
                }
            }
            return bindings;
        }

        private boolean validTypeBindings(Map<TypeVariable<?>, TypeGuess> bindings, CompilationUnit unit) {
            for (Map.Entry<TypeVariable<?>, TypeGuess> entry : bindings.entrySet()) {
                TypeGuess binding = entry.getValue();
                if (!binding.isKnown() || binding.isNullLiteral()) {
                    continue;
                }
                for (Type bound : entry.getKey().getBounds()) {
                    if (bound == Object.class) {
                        continue;
                    }
                    int conversion = conversionScore(binding, genericTypeName(bound, bindings, unit), unit, InvocationPhase.STRICT);
                    if (conversion >= NO_CONVERSION) {
                        return false;
                    }
                }
            }
            return true;
        }

        private Map<TypeVariable<?>, TypeGuess> classTypeBindings(Class<?> ownerClass, String ownerType) {
            Map<TypeVariable<?>, TypeGuess> bindings = new LinkedHashMap<>();
            TypeVariable<?>[] variables = ownerClass.getTypeParameters();
            if (variables.length == 0) {
                return bindings;
            }
            List<String> arguments = genericArguments(ownerType);
            for (int index = 0; index < Math.min(variables.length, arguments.size()); index++) {
                bindings.put(variables[index], TypeGuess.of(arguments.get(index)));
            }
            return bindings;
        }

        private void inferFromTypes(
                Type formalType,
                TypeGuess actualType,
                Map<TypeVariable<?>, TypeGuess> bindings,
                CompilationUnit unit
        ) {
            if (!actualType.isKnown() || actualType.isNullLiteral()) {
                return;
            }
            if (formalType instanceof TypeVariable<?> typeVariable) {
                bindTypeVariable(typeVariable, actualType, bindings, unit);
                return;
            }
            if (formalType instanceof ParameterizedType parameterizedType) {
                List<String> actualArguments = genericArguments(actualType.javaType());
                Type[] formalArguments = parameterizedType.getActualTypeArguments();
                for (int index = 0; index < Math.min(formalArguments.length, actualArguments.size()); index++) {
                    inferFromTypes(formalArguments[index], TypeGuess.of(actualArguments.get(index)), bindings, unit);
                }
                return;
            }
            if (formalType instanceof GenericArrayType genericArrayType) {
                arrayType(actualType).ifPresent(component -> inferFromTypes(genericArrayType.getGenericComponentType(), component, bindings, unit));
                return;
            }
            if (formalType instanceof Class<?> formalClass && formalClass.isArray()) {
                arrayType(actualType).ifPresent(component -> inferFromTypes(formalClass.getComponentType(), component, bindings, unit));
                return;
            }
            if (formalType instanceof WildcardType wildcardType) {
                for (Type upperBound : wildcardType.getUpperBounds()) {
                    inferFromTypes(upperBound, actualType, bindings, unit);
                }
                for (Type lowerBound : wildcardType.getLowerBounds()) {
                    inferFromTypes(lowerBound, actualType, bindings, unit);
                }
            }
        }

        private void bindTypeVariable(
                TypeVariable<?> typeVariable,
                TypeGuess actualType,
                Map<TypeVariable<?>, TypeGuess> bindings,
                CompilationUnit unit
        ) {
            TypeGuess existing = bindings.get(typeVariable);
            TypeGuess normalized = normalizeTypeVariableBinding(actualType, unit);
            if (existing == null || !existing.isKnown()) {
                bindings.put(typeVariable, normalized);
                return;
            }
            if (rawClassName(existing.javaType()).equals(rawClassName(normalized.javaType()))) {
                return;
            }
            bindings.put(typeVariable, commonSuperType(existing, normalized, unit));
        }

        private TypeGuess normalizeTypeVariableBinding(TypeGuess actualType, CompilationUnit unit) {
            // Capture conversion: a wildcard argument (e.g. List<? extends CharSequence> passed to List<T>)
            // captures the wildcard's bound as the type variable, never the literal "? ..." text.
            String captured = captureWildcard(actualType.javaType());
            TypeGuess source = captured.equals(actualType.javaType()) ? actualType : TypeGuess.of(captured);
            Optional<Class<?>> actualClass = classForType(source.javaType(), unit);
            if (actualClass.isPresent() && actualClass.get().isPrimitive()) {
                return TypeGuess.of(typeName(BOXED_PRIMITIVES.getOrDefault(actualClass.get(), actualClass.get())));
            }
            return source;
        }

        /** Capture conversion for a single type argument: {@code ? extends X} → X, {@code ? super X}/{@code ?} → Object. */
        private String captureWildcard(String typeName) {
            String trimmed = typeName.trim();
            if (trimmed.equals("?") || trimmed.startsWith("? super ")) {
                return "java.lang.Object";
            }
            if (trimmed.startsWith("? extends ")) {
                return trimmed.substring("? extends ".length()).trim();
            }
            return typeName;
        }

        private TypeGuess commonSuperType(TypeGuess left, TypeGuess right, CompilationUnit unit) {
            Optional<Class<?>> leftClass = classForType(left.javaType(), unit);
            Optional<Class<?>> rightClass = classForType(right.javaType(), unit);
            if (leftClass.isPresent() && rightClass.isPresent()) {
                Class<?> leftType = boxedType(leftClass.get());
                Class<?> rightType = boxedType(rightClass.get());
                if (leftType.isAssignableFrom(rightType)) {
                    return TypeGuess.of(typeName(leftType));
                }
                if (rightType.isAssignableFrom(leftType)) {
                    return TypeGuess.of(typeName(rightType));
                }
                Class<?> cursor = leftType;
                while (cursor != null) {
                    if (cursor.isAssignableFrom(rightType)) {
                        return TypeGuess.of(typeName(cursor));
                    }
                    cursor = cursor.getSuperclass();
                }
            }
            return TypeGuess.of("Object");
        }

        private Class<?> boxedType(Class<?> type) {
            return type.isPrimitive() ? BOXED_PRIMITIVES.getOrDefault(type, type) : type;
        }

        private Optional<TypeGuess> arrayType(TypeGuess type) {
            if (!type.isKnown() || type.isNullLiteral()) {
                return Optional.empty();
            }
            String javaType = type.javaType();
            if (!javaType.endsWith("[]")) {
                return Optional.empty();
            }
            return Optional.of(TypeGuess.of(javaType.substring(0, javaType.length() - 2)));
        }

        private Type genericComponentType(Type type) {
            if (type instanceof GenericArrayType genericArrayType) {
                return genericArrayType.getGenericComponentType();
            }
            if (type instanceof Class<?> clazz && clazz.isArray()) {
                return clazz.getComponentType();
            }
            return Object.class;
        }

        private List<String> genericArguments(String typeName) {
            int start = typeName.indexOf('<');
            int end = typeName.lastIndexOf('>');
            if (start < 0 || end <= start) {
                return List.of();
            }
            return splitTopLevel(typeName.substring(start + 1, end), ',').stream()
                    .map(String::trim)
                    .filter(argument -> !argument.isBlank())
                    .toList();
        }

        private boolean assignmentCompatible(TypeGuess source, String targetType, CompilationUnit unit, InvocationPhase phase) {
            if (conversionScore(source, targetType, unit, phase) >= NO_CONVERSION) {
                return false;
            }
            if (source.isLambda()) {
                return true;
            }
            return genericTypeArgumentsAssignable(source.javaType(), targetType, unit);
        }

        private boolean genericTypeArgumentsAssignable(String sourceType, String targetType, CompilationUnit unit) {
            List<String> targetArguments = genericArguments(targetType);
            if (targetArguments.isEmpty()) {
                return true;
            }
            List<String> sourceArguments = genericArguments(sourceType);
            if (sourceArguments.isEmpty() || sourceArguments.size() != targetArguments.size()) {
                return true;
            }
            for (int index = 0; index < targetArguments.size(); index++) {
                if (!genericTypeArgumentAssignable(sourceArguments.get(index), targetArguments.get(index), unit)) {
                    return false;
                }
            }
            return true;
        }

        private boolean genericTypeArgumentAssignable(String sourceArgument, String targetArgument, CompilationUnit unit) {
            String source = stripNullableSuffix(sourceArgument.trim());
            String target = stripNullableSuffix(targetArgument.trim());
            if (target.equals("?")) {
                return true;
            }
            if (target.startsWith("? extends ")) {
                String bound = target.substring("? extends ".length()).trim();
                return rawClassName(source).equals(rawClassName(bound)) || typeMoreSpecific(source, bound, unit);
            }
            if (target.startsWith("? super ")) {
                String bound = target.substring("? super ".length()).trim();
                return rawClassName(source).equals(rawClassName(bound)) || typeMoreSpecific(bound, source, unit);
            }
            if (!rawClassName(source).equals(rawClassName(target))) {
                return false;
            }
            return genericTypeArgumentsAssignable(source, target, unit);
        }

        private int conversionScore(
                TypedArgument argument,
                Type targetType,
                Map<TypeVariable<?>, TypeGuess> bindings,
                CompilationUnit unit,
                InvocationPhase phase
        ) {
            if (argument.ast() instanceof TernaryExpression ternary) {
                TypeGuess thenType = ternary.thenExpression().resolvedType();
                TypeGuess elseType = ternary.elseExpression().resolvedType();
                int thenScore = conversionScore(thenType, targetType, bindings, unit, phase);
                int elseScore = conversionScore(elseType, targetType, bindings, unit, phase);
                if (thenScore >= NO_CONVERSION || elseScore >= NO_CONVERSION) {
                    return NO_CONVERSION;
                }
                return Math.max(thenScore, elseScore);
            }
            return conversionScore(argument.type(), targetType, bindings, unit, phase);
        }

        private int conversionScore(
                TypeGuess source,
                Type targetType,
                Map<TypeVariable<?>, TypeGuess> bindings,
                CompilationUnit unit,
                InvocationPhase phase
        ) {
            // Lambdas, null, and untyped sources carry no generic arguments to compare; the "<lambda>"
            // sentinel in particular would be misread as a one-argument generic type. Skip the check.
            if (targetType instanceof ParameterizedType parameterizedType
                    && source.isKnown() && !source.isLambda() && !source.isNullLiteral()
                    && !genericArgumentsCompatible(source, parameterizedType, bindings, unit)) {
                return NO_CONVERSION;
            }
            return conversionScore(source, genericTypeName(targetType, bindings, unit), unit, phase);
        }

        private boolean genericArgumentsCompatible(
                TypeGuess source,
                ParameterizedType targetType,
                Map<TypeVariable<?>, TypeGuess> bindings,
                CompilationUnit unit
        ) {
            List<String> sourceArguments = genericArguments(source.javaType());
            Type[] targetArguments = targetType.getActualTypeArguments();
            if (sourceArguments.isEmpty() || sourceArguments.size() != targetArguments.length) {
                return true;
            }
            for (int index = 0; index < targetArguments.length; index++) {
                if (!genericArgumentCompatible(sourceArguments.get(index), targetArguments[index], bindings, unit)) {
                    return false;
                }
            }
            return true;
        }

        private boolean genericArgumentCompatible(
                String sourceArgument,
                Type targetArgument,
                Map<TypeVariable<?>, TypeGuess> bindings,
                CompilationUnit unit
        ) {
            // Capture conversion: a wildcard source argument matches by its bound, e.g. List<? extends CharSequence>
            // satisfies a List<T> parameter with T captured to CharSequence.
            sourceArgument = captureWildcard(sourceArgument);
            if (targetArgument instanceof WildcardType wildcardType) {
                Type[] lowerBounds = wildcardType.getLowerBounds();
                if (lowerBounds.length > 0) {
                    return typeMoreSpecific(genericTypeName(lowerBounds[0], bindings, unit), sourceArgument, unit)
                            || rawClassName(genericTypeName(lowerBounds[0], bindings, unit)).equals(rawClassName(sourceArgument));
                }
                Type[] upperBounds = wildcardType.getUpperBounds();
                if (upperBounds.length == 0 || upperBounds[0] == Object.class) {
                    return true;
                }
                return typeMoreSpecific(sourceArgument, genericTypeName(upperBounds[0], bindings, unit), unit)
                        || rawClassName(sourceArgument).equals(rawClassName(genericTypeName(upperBounds[0], bindings, unit)));
            }
            String target = genericTypeName(targetArgument, bindings, unit);
            return rawClassName(sourceArgument).equals(rawClassName(target));
        }

        private Optional<Method> getterMethod(Class<?> type, String methodName) {
            try {
                return Optional.of(type.getMethod(methodName));
            } catch (NoSuchMethodException exception) {
                return Optional.empty();
            }
        }

        private Optional<Field> fieldForInvocation(Class<?> type, String fieldName, CompilationUnit unit) {
            Class<?> cursor = type;
            while (cursor != null) {
                try {
                    Field field = cursor.getDeclaredField(fieldName);
                    if (isAccessible(field, unit)) {
                        return Optional.of(field);
                    }
                } catch (NoSuchFieldException ignored) {
                    // Try superclass.
                }
                cursor = cursor.getSuperclass();
            }
            try {
                Field field = type.getField(fieldName);
                return isAccessible(field, unit) ? Optional.of(field) : Optional.empty();
            } catch (NoSuchFieldException exception) {
                return Optional.empty();
            }
        }

        private Optional<Method> setterMethod(Class<?> type, String methodName, CompilationUnit unit) {
            return methodsForInvocation(type, unit).stream()
                    .filter(method -> method.getName().equals(methodName))
                    .filter(method -> method.getParameterCount() == 1)
                    .findFirst();
        }

        private boolean executableMoreSpecific(
                Executable left,
                Executable right,
                InvocationPhase phase,
                int argumentCount,
                CompilationUnit unit
        ) {
            List<Class<?>> leftTypes = effectiveParameterTypes(left, phase, argumentCount);
            List<Class<?>> rightTypes = effectiveParameterTypes(right, phase, argumentCount);
            if (leftTypes.size() != rightTypes.size()) {
                return false;
            }

            boolean strictlyMoreSpecific = false;
            for (int index = 0; index < leftTypes.size(); index++) {
                Class<?> leftType = leftTypes.get(index);
                Class<?> rightType = rightTypes.get(index);
                if (leftType.equals(rightType)) {
                    continue;
                }
                if (!typeMoreSpecific(leftType, rightType)) {
                    return false;
                }
                strictlyMoreSpecific = true;
            }

            Class<?> leftOwner = left.getDeclaringClass();
            Class<?> rightOwner = right.getDeclaringClass();
            if (!leftOwner.equals(rightOwner) && rightOwner.isAssignableFrom(leftOwner)) {
                strictlyMoreSpecific = true;
            }
            return strictlyMoreSpecific;
        }

        private List<Class<?>> effectiveParameterTypes(Executable executable, InvocationPhase phase, int argumentCount) {
            Parameter[] parameters = executable.getParameters();
            List<Class<?>> types = new ArrayList<>();
            if (phase == InvocationPhase.VARARGS && executable.isVarArgs()) {
                for (int index = 0; index < parameters.length - 1; index++) {
                    types.add(parameters[index].getType());
                }
                Class<?> componentType = parameters[parameters.length - 1].getType().getComponentType();
                int varargCount = Math.max(0, argumentCount - parameters.length + 1);
                for (int index = 0; index < varargCount; index++) {
                    types.add(componentType);
                }
            } else {
                for (Parameter parameter : parameters) {
                    types.add(parameter.getType());
                }
            }
            return types;
        }

        private boolean affogatoParametersMoreSpecific(List<ParamDecl> left, List<ParamDecl> right, CompilationUnit unit) {
            if (left.size() != right.size()) {
                return false;
            }
            boolean strictlyMoreSpecific = false;
            for (int index = 0; index < left.size(); index++) {
                String leftType = left.get(index).type().javaType();
                String rightType = right.get(index).type().javaType();
                if (rawClassName(leftType).equals(rawClassName(rightType))) {
                    continue;
                }
                if (!typeMoreSpecific(leftType, rightType, unit)) {
                    return false;
                }
                strictlyMoreSpecific = true;
            }
            return strictlyMoreSpecific;
        }

        private boolean typeMoreSpecific(String leftType, String rightType, CompilationUnit unit) {
            Optional<Class<?>> leftClass = classForType(leftType, unit);
            Optional<Class<?>> rightClass = classForType(rightType, unit);
            if (leftClass.isPresent() && rightClass.isPresent()) {
                return typeMoreSpecific(leftClass.get(), rightClass.get());
            }
            String left = rawClassName(leftType);
            String right = rawClassName(rightType);
            return !left.equals(right) && (right.equals("Object") || right.equals("java.lang.Object"));
        }

        private boolean typeMoreSpecific(Class<?> leftType, Class<?> rightType) {
            if (leftType.equals(rightType)) {
                return false;
            }
            if (leftType.isPrimitive() && rightType.isPrimitive()) {
                return primitiveWideningScore(leftType, rightType) < NO_CONVERSION;
            }
            if (leftType.isPrimitive() || rightType.isPrimitive()) {
                return false;
            }
            return rightType.isAssignableFrom(leftType);
        }

        private boolean castPossible(TypeGuess source, String targetType, CompilationUnit unit) {
            Optional<Class<?>> sourceClass = classForType(source.javaType(), unit);
            Optional<Class<?>> targetClass = classForType(targetType, unit);
            if (sourceClass.isEmpty() || targetClass.isEmpty()) {
                return true;
            }
            Class<?> sourceType = sourceClass.get();
            Class<?> target = targetClass.get();
            if (sourceType.equals(target)) {
                return true;
            }
            if (sourceType.isPrimitive() || target.isPrimitive()) {
                if (sourceType.isPrimitive() && target.isPrimitive()) {
                    return numericPrimitive(sourceType) && numericPrimitive(target) || sourceType == boolean.class && target == boolean.class;
                }
                return BOXED_PRIMITIVES.get(sourceType) == target || BOXED_PRIMITIVES.get(target) == sourceType;
            }
            if (sourceType.isAssignableFrom(target) || target.isAssignableFrom(sourceType)) {
                return true;
            }
            return sourceType.isInterface()
                    || target.isInterface()
                    || !Modifier.isFinal(sourceType.getModifiers()) && !Modifier.isFinal(target.getModifiers());
        }

        private boolean numericPrimitive(Class<?> type) {
            return type == byte.class
                    || type == short.class
                    || type == int.class
                    || type == long.class
                    || type == float.class
                    || type == double.class
                    || type == char.class;
        }

        private int conversionScore(TypedArgument argument, String targetType, CompilationUnit unit, InvocationPhase phase) {
            if (argument.ast() instanceof TernaryExpression ternary) {
                int thenScore = conversionScore(ternary.thenExpression().resolvedType(), targetType, unit, phase);
                int elseScore = conversionScore(ternary.elseExpression().resolvedType(), targetType, unit, phase);
                if (thenScore >= NO_CONVERSION || elseScore >= NO_CONVERSION) {
                    return NO_CONVERSION;
                }
                return Math.max(thenScore, elseScore);
            }
            return conversionScore(argument.type(), targetType, unit, phase);
        }

        private int conversionScore(TypeGuess source, String targetType, CompilationUnit unit, InvocationPhase phase) {
            Optional<Class<?>> targetClass = classForType(targetType, unit);
            if (targetClass.isPresent()) {
                return conversionScore(source, targetClass.get(), unit, phase);
            }
            String sourceType = rawClassName(source.javaType());
            String target = rawClassName(targetType);
            if (source.isNullLiteral()) {
                return PRIMITIVES.contains(target) ? NO_CONVERSION : 30;
            }
            if (!source.isKnown()) {
                return 200;
            }
            if (sourceType.equals(target) || simpleType(sourceType).equals(simpleType(target))) {
                return 0;
            }
            if (target.equals("Object") || target.equals("java.lang.Object")) {
                if (PRIMITIVES.contains(sourceType) && phase == InvocationPhase.STRICT) {
                    return NO_CONVERSION;
                }
                return 90;
            }
            return phase == InvocationPhase.STRICT ? NO_CONVERSION : 200;
        }

        private int conversionScore(TypeGuess source, Class<?> targetType, CompilationUnit unit, InvocationPhase phase) {
            if (source.isNullLiteral()) {
                return targetType.isPrimitive() ? NO_CONVERSION : 30;
            }
            if (source.isLambda()) {
                Optional<Method> sam = functionalMethod(targetType);
                if (sam.isEmpty()) {
                    return NO_CONVERSION;
                }
                // Target-typed poly expression: an arrow lambda with a known parameter count must agree
                // with the functional interface's single abstract method. A matching arity is rewarded
                // over a method reference (unknown arity), so the precise overload wins.
                if (source.lambdaArity() == UNKNOWN_ARITY) {
                    return 40;
                }
                return source.lambdaArity() == sam.get().getParameterCount() ? 35 : NO_CONVERSION;
            }
            if (!source.isKnown()) {
                return 200;
            }

            Optional<Class<?>> sourceClass = classForType(source.javaType(), unit);
            if (sourceClass.isEmpty()) {
                return fallbackConversionScore(source.javaType(), targetType);
            }

            Class<?> sourceType = sourceClass.get();
            if (sourceType.equals(targetType)) {
                return 0;
            }
            if (sourceType.isPrimitive() && targetType.isPrimitive()) {
                return primitiveWideningScore(sourceType, targetType);
            }
            if (phase == InvocationPhase.STRICT) {
                if (!sourceType.isPrimitive() && !targetType.isPrimitive() && targetType.isAssignableFrom(sourceType)) {
                    return 50 + inheritanceDistance(sourceType, targetType);
                }
                return NO_CONVERSION;
            }
            if (sourceType.isPrimitive()) {
                Class<?> boxed = BOXED_PRIMITIVES.get(sourceType);
                if (boxed != null && boxed.equals(targetType)) {
                    return 20;
                }
                if (boxed != null && targetType.isAssignableFrom(boxed)) {
                    return 70 + inheritanceDistance(boxed, targetType);
                }
                return NO_CONVERSION;
            }
            if (targetType.isPrimitive()) {
                Class<?> unboxed = UNBOXED_PRIMITIVES.get(sourceType);
                if (unboxed == null) {
                    return NO_CONVERSION;
                }
                if (unboxed.equals(targetType)) {
                    return 20;
                }
                int widening = primitiveWideningScore(unboxed, targetType);
                return widening >= NO_CONVERSION ? NO_CONVERSION : 30 + widening;
            }
            if (targetType.isAssignableFrom(sourceType)) {
                return 50 + inheritanceDistance(sourceType, targetType);
            }
            return NO_CONVERSION;
        }

        private boolean isFunctionalInterface(Class<?> targetType) {
            return functionalMethod(targetType).isPresent();
        }

        /** Returns the single abstract method of a functional interface, or empty when {@code targetType} is not one. */
        private Optional<Method> functionalMethod(Class<?> targetType) {
            if (!targetType.isInterface()) {
                return Optional.empty();
            }
            Map<String, Method> abstractMethods = new LinkedHashMap<>();
            for (Method method : targetType.getMethods()) {
                int modifiers = method.getModifiers();
                if (!Modifier.isAbstract(modifiers) || Modifier.isStatic(modifiers) || method.isDefault()) {
                    continue;
                }
                if (method.getDeclaringClass().equals(Object.class) || isObjectMethod(method)) {
                    continue;
                }
                abstractMethods.putIfAbsent(method.getName() + parameterDescriptor(method.getParameterTypes()), method);
            }
            return abstractMethods.size() == 1 ? Optional.of(abstractMethods.values().iterator().next()) : Optional.empty();
        }

        private boolean isObjectMethod(Method method) {
            try {
                Object.class.getMethod(method.getName(), method.getParameterTypes());
                return true;
            } catch (NoSuchMethodException exception) {
                return false;
            }
        }

        private String parameterDescriptor(Class<?>[] parameterTypes) {
            StringBuilder descriptor = new StringBuilder("(");
            for (Class<?> parameterType : parameterTypes) {
                descriptor.append(parameterType.getName()).append(';');
            }
            return descriptor.append(')').toString();
        }

        private int fallbackConversionScore(String sourceType, Class<?> targetType) {
            String source = rawClassName(sourceType);
            String target = typeName(targetType);
            if (source.equals(target) || simpleType(source).equals(simpleType(target))) {
                return 0;
            }
            if (!targetType.isPrimitive() && targetType.equals(Object.class)) {
                return 90;
            }
            return 200;
        }

        private int primitiveWideningScore(Class<?> sourceType, Class<?> targetType) {
            List<Class<?>> widening = PRIMITIVE_WIDENING.getOrDefault(sourceType, List.of());
            int index = widening.indexOf(targetType);
            return index < 0 ? NO_CONVERSION : 10 + index;
        }

        private int inheritanceDistance(Class<?> sourceType, Class<?> targetType) {
            if (sourceType.equals(targetType)) {
                return 0;
            }
            if (targetType.isInterface()) {
                return interfaceDistance(sourceType, targetType, 1);
            }
            int distance = 0;
            Class<?> cursor = sourceType;
            while (cursor != null) {
                if (cursor.equals(targetType)) {
                    return distance;
                }
                cursor = cursor.getSuperclass();
                distance++;
            }
            return 100;
        }

        private int interfaceDistance(Class<?> sourceType, Class<?> targetType, int depth) {
            for (Class<?> interfaceType : sourceType.getInterfaces()) {
                if (interfaceType.equals(targetType)) {
                    return depth;
                }
                int nested = interfaceDistance(interfaceType, targetType, depth + 1);
                if (nested < 100) {
                    return nested;
                }
            }
            Class<?> superclass = sourceType.getSuperclass();
            if (superclass != null) {
                return interfaceDistance(superclass, targetType, depth + 1);
            }
            return 100;
        }

        private Optional<Class<?>> classForType(String typeName, CompilationUnit unit) {
            String trimmed = typeName.trim();
            if (trimmed.endsWith("[]")) {
                return classForType(trimmed.substring(0, trimmed.length() - 2), unit)
                        .map(component -> Array.newInstance(component, 0).getClass());
            }
            String cleaned = rawClassName(trimmed);
            Class<?> primitive = PRIMITIVE_CLASSES.get(cleaned);
            if (primitive != null) {
                return Optional.of(primitive);
            }
            return loadClass(cleaned, unit);
        }

        private String typeName(Class<?> type) {
            if (type.isArray()) {
                return typeName(type.getComponentType()) + "[]";
            }
            String canonical = type.getCanonicalName();
            return canonical == null ? type.getName() : canonical;
        }

        private String genericTypeName(Type type, Map<TypeVariable<?>, TypeGuess> bindings, CompilationUnit unit) {
            if (type instanceof Class<?> clazz) {
                return typeName(clazz);
            }
            if (type instanceof TypeVariable<?> typeVariable) {
                TypeGuess bound = bindings.get(typeVariable);
                if (bound != null && bound.isKnown() && !bound.isNullLiteral()) {
                    return bound.javaType();
                }
                Type[] bounds = typeVariable.getBounds();
                return bounds.length == 0 ? "java.lang.Object" : genericTypeName(bounds[0], bindings, unit);
            }
            if (type instanceof ParameterizedType parameterizedType) {
                String raw = genericTypeName(parameterizedType.getRawType(), bindings, unit);
                Type[] arguments = parameterizedType.getActualTypeArguments();
                if (arguments.length == 0) {
                    return raw;
                }
                List<String> rendered = new ArrayList<>();
                for (Type argument : arguments) {
                    rendered.add(genericTypeName(argument, bindings, unit));
                }
                return raw + "<" + String.join(", ", rendered) + ">";
            }
            if (type instanceof GenericArrayType genericArrayType) {
                return genericTypeName(genericArrayType.getGenericComponentType(), bindings, unit) + "[]";
            }
            if (type instanceof WildcardType wildcardType) {
                Type[] lowerBounds = wildcardType.getLowerBounds();
                if (lowerBounds.length > 0) {
                    return "? super " + genericTypeName(lowerBounds[0], bindings, unit);
                }
                Type[] upperBounds = wildcardType.getUpperBounds();
                if (upperBounds.length > 0 && upperBounds[0] != Object.class) {
                    return "? extends " + genericTypeName(upperBounds[0], bindings, unit);
                }
                return "?";
            }
            return "java.lang.Object";
        }

        private String simpleType(String type) {
            String cleaned = rawClassName(type);
            int dot = cleaned.lastIndexOf('.');
            return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
        }

        private Optional<Class<?>> loadClass(String requestedName, CompilationUnit unit) {
            String cleaned = rawClassName(requestedName);
            for (String candidate : classCandidates(cleaned, unit)) {
                try {
                    return Optional.of(Class.forName(candidate, false, classLoader));
                } catch (ClassNotFoundException ignored) {
                    // Try next candidate.
                }
            }
            return Optional.empty();
        }

        /** Simple names of all Java superclasses and (transitively) implemented interfaces of {@code type}. */
        private List<String> ancestorSimpleNames(String type, CompilationUnit unit) {
            return loadClass(type, unit)
                    .map(loaded -> {
                        List<String> names = new ArrayList<>();
                        Set<Class<?>> seen = new LinkedHashSet<>();
                        collectAncestors(loaded, names, seen);
                        return names;
                    })
                    .orElse(List.of());
        }

        private void collectAncestors(Class<?> type, List<String> names, Set<Class<?>> seen) {
            Class<?> superclass = type.getSuperclass();
            if (superclass != null && seen.add(superclass)) {
                names.add(superclass.getSimpleName());
                collectAncestors(superclass, names, seen);
            }
            for (Class<?> implemented : type.getInterfaces()) {
                if (seen.add(implemented)) {
                    names.add(implemented.getSimpleName());
                    collectAncestors(implemented, names, seen);
                }
            }
        }

        private String rawClassName(String typeName) {
            String cleaned = stripTypeUseAnnotations(typeName.trim());
            if (cleaned.endsWith("?") || cleaned.endsWith("!")) {
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            int generic = cleaned.indexOf('<');
            if (generic >= 0) {
                cleaned = cleaned.substring(0, generic);
            }
            while (cleaned.endsWith("[]")) {
                cleaned = cleaned.substring(0, cleaned.length() - 2);
            }
            return cleaned;
        }

        private List<String> classCandidates(String className, CompilationUnit unit) {
            List<String> candidates = new ArrayList<>();
            if (className.contains(".")) {
                candidates.add(className);
                candidates.addAll(innerClassCandidates(className));
                String outer = className.substring(0, className.indexOf('.'));
                String inner = className.substring(className.indexOf('.') + 1).replace('.', '$');
                if (!unit.packageName().isBlank()) {
                    candidates.add(unit.packageName() + "." + outer + "$" + inner);
                }
                for (String importName : unit.imports()) {
                    String cleaned = importName.replaceFirst("^static\\s+", "");
                    if (cleaned.endsWith("." + outer)) {
                        candidates.add(cleaned + "$" + inner);
                    }
                    if (cleaned.endsWith(".*")) {
                        candidates.add(cleaned.substring(0, cleaned.length() - 2) + "." + outer + "$" + inner);
                    }
                }
                candidates.add("java.lang." + outer + "$" + inner);
                candidates.add("java.util." + outer + "$" + inner);
                return candidates;
            }
            if (!unit.packageName().isBlank()) {
                candidates.add(unit.packageName() + "." + className);
            }
            for (String importName : unit.imports()) {
                String cleaned = importName.replaceFirst("^static\\s+", "");
                if (cleaned.endsWith("." + className)) {
                    candidates.add(cleaned);
                }
                if (cleaned.endsWith(".*")) {
                    candidates.add(cleaned.substring(0, cleaned.length() - 2) + "." + className);
                }
            }
            candidates.add("java.lang." + className);
            candidates.add("java.util." + className);
            candidates.add(className);
            return candidates.stream().distinct().toList();
        }

        private List<String> innerClassCandidates(String className) {
            List<String> candidates = new ArrayList<>();
            for (int index = className.lastIndexOf('.'); index > 0; index = className.lastIndexOf('.', index - 1)) {
                candidates.add(className.substring(0, index) + "$" + className.substring(index + 1).replace('.', '$'));
            }
            return candidates;
        }
    }
}
