package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.SourceLocations;
import dev.affogato.compiler.parser.AffogatoLexer;
import dev.affogato.compiler.parser.AffogatoParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
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

final class AffogatoJavaGenerator implements ExpressionRenderServices {
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

    private final CompilationSession session;
    private AffogatoTypeChecker typeChecker;
    private final List<AffogatoDiagnostic> diagnostics;
    private final AffogatoSymbolResolver symbols;
    private final FlowAnalyzer flow;
    private final AffogatoParserRunner parserRunner;
    private Set<String> activeTypeParams = new HashSet<>();
    // When transforming an array-literal initializer whose binding has an explicit single-dimension array
    // type (`let xs: Person[] = [...]`), this holds that element type so the emitted `new T[]{...}` matches
    // the declared type instead of a too-wide `new Object[]`. Null when there is no explicit array target.
    private String expectedArrayElementType = null;

    @Override
    public String getExpectedArrayElementType() {
        return expectedArrayElementType;
    }

    @Override
    public void setExpectedArrayElementType(String val) {
        expectedArrayElementType = val;
    }

    AffogatoJavaGenerator(CompilationSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.diagnostics = session.diagnostics();
        this.symbols = session.symbols();
        this.flow = session.flow();
        this.parserRunner = session.parserRunner();
    }

    void bindTypeChecker(AffogatoTypeChecker typeChecker) {
        this.typeChecker = Objects.requireNonNull(typeChecker, "typeChecker");
    }

    Set<String> activeTypeParams() {
        return activeTypeParams;
    }

    List<AffogatoTranspiler.GeneratedJava> generate(AffogatoTranspiler.ParsedUnit parsedUnit) {
        typeChecker.setActiveTypeParams(activeTypeParams);
        List<AffogatoTranspiler.GeneratedJava> generatedFiles = new ArrayList<>();
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



    private static String extensionsHolderName(CompilationUnit unit) {
        String fileName = unit.sourceFile().getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return base + "Extensions";
    }


    private AffogatoTranspiler.GeneratedJava generateClass(CompilationUnit unit, ParsedClass clazz) {
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
        writeNestedEnums(out, clazz);
        activeTypeParams = prevTypeParams;

        out.append("}").append(System.lineSeparator());
        return new AffogatoTranspiler.GeneratedJava(unit.packageName(), clazz.name(), out.toString());
    }

    private void writeNestedEnums(StringBuilder out, ParsedClass clazz) {
        for (ParsedEnum nested : clazz.nestedEnums()) {
            writeAnnotations(out, nested.annotations(), 1);
            out.append(indent(1)).append(nested.access()).append(" enum ").append(nested.name()).append(" {")
                    .append(System.lineSeparator());
            out.append(indent(2)).append(String.join(", ", nested.constants())).append(System.lineSeparator());
            out.append(indent(1)).append("}").append(System.lineSeparator());
        }
    }

    private AffogatoTranspiler.GeneratedJava generateExtensionsHolder(CompilationUnit unit) {
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
            MethodContext context = MethodContext.forExecutable(unit, shape, extension.name(), extension.returnType(), symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), activeTypeParams);
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
            writeBlockStatements(out, unit, extension.body(), context, 2);
            out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
        }

        out.append("}").append(System.lineSeparator());
        return new AffogatoTranspiler.GeneratedJava(unit.packageName(), holderName, out.toString());
    }

    private List<ParamDecl> holderParameters(ExtensionFuncDecl extension) {
        List<ParamDecl> parameters = new ArrayList<>();
        parameters.add(new ParamDecl("$this", extension.receiverType(), PropertyKind.NONE, List.of()));
        parameters.addAll(extension.parameters());
        return parameters;
    }

    private AffogatoTranspiler.GeneratedJava generateEnum(CompilationUnit unit, ParsedEnum parsedEnum) {
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
        return new AffogatoTranspiler.GeneratedJava(unit.packageName(), parsedEnum.name(), out.toString());
    }

    private AffogatoTranspiler.GeneratedJava generateInterface(CompilationUnit unit, ParsedInterface parsedInterface) {
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
                MethodContext context = MethodContext.forExecutable(unit, dummyClass, method.name(), method.returnType(), symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), activeTypeParams);
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
        return new AffogatoTranspiler.GeneratedJava(unit.packageName(), parsedInterface.name(), out.toString());
    }

    private AffogatoTranspiler.GeneratedJava generateRecord(CompilationUnit unit, ParsedRecord parsedRecord) {
        ParsedClass shape = new ParsedClass(parsedRecord.access(), parsedRecord.name(), parsedRecord.typeParameters(),
                parsedRecord.superTypes(), parsedRecord.components(), List.of(), parsedRecord.constructors(), parsedRecord.methods(),
                parsedRecord.annotations(), parsedRecord.declarationLine(), parsedRecord.declarationColumn());

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
        // We only do this if there are no explicit constructors, or we could merge them.
        // For now, if there are explicit constructors, they are responsible for null checks or delegating.
        if (parsedRecord.constructors().isEmpty()) {
            boolean needsNullChecks = parsedRecord.components().stream().anyMatch(c -> c.type().requiresRuntimeCheck());
            if (needsNullChecks) {
                out.append("    public ").append(parsedRecord.name()).append(" {").append(System.lineSeparator());
                for (ParamDecl component : parsedRecord.components()) {
                    writeNullCheck(out, component.name(), component.type(), 2);
                }
                out.append("    }").append(System.lineSeparator()).append(System.lineSeparator());
            }
        } else {
            writeConstructors(out, unit, shape);
        }

        writeMethods(out, unit, shape);
        activeTypeParams = prevTypeParams;

        out.append("}").append(System.lineSeparator());
        return new AffogatoTranspiler.GeneratedJava(unit.packageName(), parsedRecord.name(), out.toString());
    }

    private boolean isInterfaceType(String typeName, CompilationUnit unit) {
        ClassSymbol symbol = classSymbol(typeName, unit);
        if (symbol != null) {
            return symbol.isInterface();
        }
        return symbols.javaResolver().isInterface(typeName, unit);
    }

    private void writeFields(StringBuilder out, CompilationUnit unit, ParsedClass clazz) {
        MethodContext context = MethodContext.empty(unit, clazz, symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), activeTypeParams);
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
        MethodContext context = MethodContext.forExecutable(unit, clazz, clazz.name(), TypeRef.unspecified("void"), symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), activeTypeParams);
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
            MethodContext context = MethodContext.forExecutable(unit, clazz, clazz.name(), TypeRef.unspecified("void"), symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), activeTypeParams);
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
            // Accessors are always public; static fields get static accessors.
            String staticMod = field.isStatic() ? " static" : "";
            out.append("    public").append(staticMod).append(' ')
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
                out.append("    public").append(staticMod).append(" void ")
                        .append(setterName(field.name()))
                        .append('(')
                        .append(field.type().declaration())
                        .append(" value) {")
                        .append(System.lineSeparator());
                writeNullCheck(out, "value", field.type(), 2);
                // Static fields must not use `this.` — use bare field name assignment.
                String assignTarget = field.isStatic() ? field.name() : "this." + field.name();
                out.append("        ").append(assignTarget)
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

            MethodContext context = MethodContext.forExecutable(unit, clazz, method.name(), method.returnType(), symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), activeTypeParams);
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

    @Override
    public void writeBlockStatements(StringBuilder out, CompilationUnit unit, AffogatoParser.BlockContext block, MethodContext context, int indent) {
        flow.checkUnreachable(unit.sourceFile(), block);
        context.pushBlockScope();
        try {
            List<AffogatoParser.StatementContext> statements = block.statement();
            boolean unreachable = false;
            for (int index = 0; index < statements.size(); index++) {
                AffogatoParser.StatementContext statement = statements.get(index);
                // Statements after a return/throw/break/continue (or a block that always exits) are
                // unreachable. checkUnreachable already reported them; omit them from the generated Java
                // so javac — which treats unreachable code as a hard error — still accepts the output.
                if (unreachable && !flow.isPureSeparator(statement)) {
                    continue;
                }
                Set<String> declaredLater = new LinkedHashSet<>();
                for (int later = index + 1; later < statements.size(); later++) {
                    declaredLater.addAll(localNamesDeclaredInStatement(statements.get(later)));
                }
                context.setLocalsDeclaredLaterInBlock(declaredLater);
                writeStatement(out, unit, statement, context, indent);
                if (flow.statementStopsControl(statement)) {
                    unreachable = true;
                }
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

    @Override
    public void writeStatement(StringBuilder out, CompilationUnit unit, AffogatoParser.StatementContext statement, MethodContext context, int indent) {
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
            String rawIterable = sourceText(unit.source(), content.expression(0));
            TypedExpression typedIterable = transformExpressionTyped(rawIterable, context, content.expression(0));
            String iterable = typedIterable.javaSource();
            Optional<TypeGuess> elementType = elementType(typedIterable.resolvedType());
            if (elementType.isPresent()) {
                context.declareVariable(variable, TypeRef.unspecified(elementType.get().javaType()), true);
            } else if (!typedIterable.resolvedType().isKnown()
                    || typedIterable.resolvedType().javaType().isBlank()
                    || typedIterable.resolvedType().javaType().equals("Object")
                    || typedIterable.resolvedType().javaType().equals("java.lang.Object")
                    || typedIterable.resolvedType().javaType().endsWith("Collection")
                    || typedIterable.resolvedType().javaType().endsWith("List")
                    || typedIterable.resolvedType().javaType().endsWith("Set")) {
                context.declareVariable(variable, TypeRef.unspecified("Object"), true);
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
        } else if (!content.SEMI().isEmpty()) {
            // C-style for loop: init; condition; update
            StringBuilder forHeader = new StringBuilder("for (");
            AffogatoParser.ForCStyleInitContext init = content.forCStyleInit();
            if (init != null) {
                if (init.variableKind() != null) {
                    String varName = init.Identifier().getText();
                    TypedExpression initTyped = transformExpressionTyped(
                            sourceText(unit.source(), init.expression()), context, init.expression());
                    if (init.typeRef() != null) {
                        TypeRef explicitType = typeRef(init.typeRef());
                        context.declareVariable(varName, explicitType, true);
                        forHeader.append(explicitType.javaType()).append(" ").append(varName)
                                .append(" = ").append(initTyped.javaSource());
                    } else {
                        TypeRef varType = TypeRef.unspecified(
                                initTyped.resolvedType().isKnown() && !initTyped.resolvedType().isNullLiteral()
                                        ? initTyped.resolvedType().javaType() : "int");
                        context.declareVariable(varName, varType, true);
                        forHeader.append("var ").append(varName).append(" = ").append(initTyped.javaSource());
                    }
                    context.mutableVariables.put(varName, true);
                } else {
                    forHeader.append(transformExpression(sourceText(unit.source(), init.expression()), context));
                }
            }
            forHeader.append("; ");
            if (content.cStyleCond != null) {
                forHeader.append(transformExpression(sourceText(unit.source(), content.cStyleCond), context));
            }
            forHeader.append("; ");
            if (content.cStyleUpdate != null) {
                forHeader.append(transformExpression(sourceText(unit.source(), content.cStyleUpdate), context));
            }
            forHeader.append(") {");
            out.append(indent(indent)).append(forHeader).append(System.lineSeparator());
        } else {
            String expression = transformExpression(sourceText(unit.source(), content.expression(0)), context);
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
            MethodContext catchContext = MethodContext.forExecutable(unit, context.currentClass, context.executableName, context.returnType, symbols.classSymbols(), symbols.extensionSymbols(), symbols.javaResolver(), context.activeTypeParams);
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
                out.append(indent(indent + 1)).append("case ")
                        .append(renderSwitchLabels(arm, typedCondition.resolvedType(), unit, context)).append(" -> ");
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
                out.append(indent(indent + 1)).append("case ")
                        .append(renderSwitchLabels(arm, typedCondition.resolvedType(), unit, context)).append(" -> ");
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

    private String renderSwitchLabels(AffogatoParser.SwitchArmContext arm, TypeGuess selectorType, CompilationUnit unit, MethodContext context) {
        List<String> labels = new ArrayList<>();
        for (AffogatoParser.SwitchLabelContext label : arm.switchLabel()) {
            labels.add(renderSwitchLabel(selectorType, label, unit, context));
        }
        return String.join(", ", labels);
    }

    private String renderSwitchLabel(TypeGuess selectorType, AffogatoParser.SwitchLabelContext label, CompilationUnit unit, MethodContext context) {
        String rawLabel = sourceText(unit.source(), label.expression()).trim();
        ClassSymbol enumSymbol = selectorType.isKnown() ? classSymbol(selectorType.javaType(), context.unit) : null;
        if (enumSymbol != null && enumSymbol.isEnum) {
            // An enum case label is a constant name, optionally qualified (both `MON` and `Day.MON`
            // are valid Java 21 enum labels). It is not a general expression, so it bypasses identifier
            // resolution; the label is emitted verbatim after checking the constant exists.
            int dot = rawLabel.lastIndexOf('.');
            String constant = dot >= 0 ? rawLabel.substring(dot + 1).trim() : rawLabel;
            if (!enumSymbol.enumConstants.contains(constant)) {
                diagnostics.add(error(unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_SWITCH_LABEL_TYPE",
                        "'" + rawLabel + "' is not a constant of enum " + enumSymbol.name() + "."));
            }
            return rawLabel;
        }
        TypedExpression typedLabel = transformExpressionTyped(rawLabel, context);
        validateSwitchLabel(selectorType, typedLabel.resolvedType(), unit, context);
        return typedLabel.javaSource();
    }

    private void validateSwitchLabel(TypeGuess selectorType, TypeGuess labelType, CompilationUnit unit, MethodContext context) {
        typeChecker.validateSwitchLabel(selectorType, labelType, unit, context);
    }


    private void validateSwitchSelector(TypeGuess selectorType, CompilationUnit unit, MethodContext context) {
        typeChecker.validateSwitchSelector(selectorType, unit, context);
    }


    private TypeGuess mergeSwitchArmType(TypeGuess current, TypeGuess next, MethodContext context) {
        return typeChecker.mergeSwitchArmType(current, next, context);
    }


    private String transformLocalDeclaration(CompilationUnit unit, AffogatoParser.LocalVarDeclContext declaration, MethodContext context, int indent) {
        boolean immutable = declaration.variableKind().LET() != null;
        String name = declaration.Identifier().getText();
        TypeRef type = declaration.declaredType == null ? null : typeRef(declaration.declaredType);
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
                        unit.source(), declaration.trailingClosure(), context)
                  + (declaration.AS() != null ? " as " + declaration.castType.getText() : "");
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
        String type = context.activeTypeParams.contains(receiverType.javaType()) ? "java.lang.Object" : receiverType.javaType();
        String loweredReceiver = transformExpression(receiver, context);
        String value = transformExpression(rawValue, context);

        // Hoist complex receivers (method calls etc.) to a temp var to avoid double evaluation
        // in read-modify-write operations. e.g., `make().n += 1` must not call `make()` twice.
        boolean hoistReceiver = readModify && loweredReceiver.contains("(");
        String recv = hoistReceiver ? context.nextRecvTempName() : loweredReceiver;
        String hoistPrefix = hoistReceiver ? "var " + recv + " = " + loweredReceiver + "; " : "";

        FieldSymbol field = fieldForOwnerType(type, property, context);
        if (field != null) {
            if (!field.mutable()) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to let property " + property + "."));
                return recv + "." + property + " = " + value + ";";
            }
            String read = recv + "." + getterName(property, field.type()) + "()";
            String setValue = readModify ? read + " " + operator + " (" + value + ")" : value;
            return hoistPrefix + recv + "." + setterName(property) + "(" + setValue + ");";
        }
        if (context.javaResolver.setterExists(type, property, context.unit)) {
            String setValue = value;
            if (readModify) {
                if (!context.javaResolver.getterExists(type, property, context.unit)) {
                    return null;
                }
                String getter = context.javaResolver.getterInvocationName(type, property, context.unit)
                        .orElse(getterName(property, TypeRef.unspecified("Object")));
                setValue = recv + "." + getter + "() " + operator + " (" + value + ")";
            }
            return hoistPrefix + recv + "." + setterName(property) + "(" + setValue + ");";
        }
        if (context.javaResolver.fieldExists(type, property, context.unit)) {
            if (!context.javaResolver.fieldMutable(type, property, context.unit)) {
                diagnostics.add(error(context.unit.sourceFile(), context.currentLine, context.currentColumn,
                        "AFFOGATO_LET_ASSIGN", "Cannot assign to final Java field " + property + "."));
            }
            // Java's compound assignment (`+=`) evaluates the receiver only once; no hoisting needed.
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
            if (current == '"' && !isEscapedQuote(expression, index)) {
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
            if (current == '"' && !isEscapedQuote(text, index)) {
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
        symbols.validateTypeRef(type, unit, line, column, activeTypeParams);
    }

    private void validateTypeName(String typeName, CompilationUnit unit, int line, int column) {
        symbols.validateTypeName(typeName, unit, line, column, activeTypeParams);
    }

    String stripWildcardBound(String bound) {
        return symbols.stripWildcardBound(bound);
    }


    private void validateReturn(String rawExpression, MethodContext context, int line, int column) {
        if (session.typesChecked()) {
            return;
        }
        typeChecker.validateReturn(rawExpression, context, line, column);
    }

    private void validateThrowExpression(TypedExpression expression, MethodContext context, int line, int column) {
        typeChecker.validateThrowExpression(expression, context, line, column);
    }

    private void validateVariableAssignment(Matcher matcher, MethodContext context, int line, int column) {
        typeChecker.validateVariableAssignment(matcher, context, line, column);
    }

    private void validateCondition(String rawExpression, MethodContext context, int line, int column) {
        typeChecker.validateCondition(rawExpression, context, line, column);
    }

    private void validateAssignment(TypeRef expected, String rawExpression, MethodContext context, int line, int column, String code, String message) {
        typeChecker.validateAssignment(expected, rawExpression, context, line, column, code, message);
    }

    private boolean isAssignable(TypeGuess actual, TypeRef expected, MethodContext context) {
        return typeChecker.isAssignable(actual, expected, context);
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

    TypedExpression transformExpressionTypedInSpan(String expression, MethodContext context, ParserRuleContext expressionAnchor) {
        if (session.typesChecked()) {
            AstExpression ast = typeChecker.expressionAst(expression, context);
            TypeGuess resolvedType = ast.resolvedType().isKnown()
                    ? ast.resolvedType()
                    : typeChecker.inferExpressionType(expression.trim(), context);
            String result = new ExpressionRenderer(this).render(ast, context);
            return new TypedExpression(result, resolvedType, ast);
        }
        TypedExpression validated = typeChecker.validateExpressionTyped(expression, context, expressionAnchor);
        String result = new ExpressionRenderer(this).render(validated.ast(), context);
        return new TypedExpression(result, validated.resolvedType(), validated.ast());
    }

    @Override
    public String inferArrayElementType(List<String> elements, MethodContext context) {
        return typeChecker.inferArrayElementType(elements, context);
    }

    @Override
    public String transformStringInterpolation(String expression, MethodContext context) {
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
                    while (idEnd < n
                            && expression.charAt(idEnd) != '$'
                            && Character.isJavaIdentifierPart(expression.charAt(idEnd))) {
                        idEnd++;
                    }
                    exprText = expression.substring(j + 1, idEnd);
                    nextIndex = idEnd;
                }
                if (exprText != null && exprText.isBlank()) {
                    if (context != null) {
                        diagnostics.add(new AffogatoDiagnostic(
                                AffogatoDiagnostic.Severity.ERROR,
                                "AFFOGATO_PARSE",
                                "Empty interpolation '${}' has no expression.",
                                context.unit.sourceFile(),
                                context.currentLine,
                                context.currentColumn
                        ));
                    }
                    segment.append(expression, j, nextIndex);
                    j = nextIndex;
                    continue;
                }
                if (exprText != null) {
                    interpolated = true;
                    parts.add('"' + segment.toString() + '"');
                    segment.setLength(0);
                    parts.add('(' + transformStringInterpolation(exprText, context) + ')');
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
                String content = segment.toString();
                if (content.contains("\\")) {
                    content = parserRunner.unescapeAffogatoString(content);
                }
                out.append('"').append(parserRunner.escapeForJavaString(content)).append('"');
                i = j;
                continue;
            }
            parts.add('"' + segment.toString() + '"');

            List<String> rendered = new ArrayList<>();
            for (String part : parts) {
                if (!part.equals("\"\"")) {
                    if (part.startsWith("\"") && part.endsWith("\"")) {
                        String content = part.substring(1, part.length() - 1);
                        if (content.contains("\\")) {
                            content = parserRunner.unescapeAffogatoString(content);
                        }
                        rendered.add("\"" + parserRunner.escapeForJavaString(content) + "\"");
                    } else {
                        rendered.add(part);
                    }
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

    @Override
    public AffogatoSymbolResolver.PropertyHop resolvePropertyHopOnType(String ownerType, String property, MethodContext context) {
        return symbols.resolvePropertyHopOnType(ownerType, property, context);
    }

    FieldSymbol resolveField(String owner, String property, MethodContext context) {
        return symbols.resolveField(owner, property, context);
    }

    @Override
    public ClassSymbol classSymbol(String type, CompilationUnit unit) {
        return symbols.lookupClass(type, unit);
    }

    @Override
    public FieldSymbol fieldForOwnerType(String ownerType, String property, MethodContext context) {
        return symbols.fieldForOwnerType(ownerType, property, context);
    }

    // ── FLOW ANALYSIS ────────────────────────────────────────────────────────────

    private boolean blockExits(AffogatoParser.BlockContext block) {
        return flow.blockExits(block);
    }

    @Override
    public TypeGuess inferExpressionType(String expression, MethodContext context) {
        return typeChecker.inferExpressionType(expression, context);
    }

    @Override
    public TypeGuess inferExpressionType(String expression, MethodContext context, TypeGuess expected) {
        return typeChecker.inferExpressionType(expression, context, expected);
    }

    @Override
    public AstExpression expressionAst(String expression, MethodContext context) {
        return typeChecker.expressionAst(expression, context);
    }


    @Override
    public TypedExpression buildSwitchExpressionNode(String source, MethodContext context) {
        AffogatoLexer lexer = new AffogatoLexer(CharStreams.fromString(source));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        AffogatoParser parser = new AffogatoParser(tokens);
        AffogatoParser.SwitchExpressionContext switchCtx = parser.switchExpression();
        return buildSwitchExpression(context.unit, switchCtx, context, 0, TypeRef.unspecified("Object"), "AFFOGATO_SWITCH_TYPE", "Invalid switch expression");
    }

    private boolean astTypeCanShortCircuitInference(AstExpression ast) {
        // The ANTLR-backed AST resolves these node types reliably, including cases the regex inference
        // below mishandles. Constructors in particular carry the correct implementation type even when
        // the type arguments nest generics (e.g. Map<String, List<Integer>>()), which the regex path
        // misreads as a boolean comparison on the top-level '<' / '>'.
        return ast instanceof LambdaExpression
                || ast instanceof MethodReferenceExpression
                || (ast instanceof ConstructorExpression && ast.resolvedType().isKnown())
                // Shift (<<, >>, >>>) and numeric bitwise (&, |, ^) expressions carry a numeric result
                // type from buildShift / the bitwise builders. The regex inference below reads a bare
                // '<' / '>' as a relational comparison (boolean) and does not type bitwise at all
                // (Object), so without the AST short-circuit `let x = 1 << 4` emits invalid Java and
                // `let m = 0xFF & x` emits an imprecise Object. The known-type guard keeps this to the
                // numeric cases (boolean operands are rejected earlier and never resolve to a type here).
                || (ast instanceof BinaryExpression binary && isShiftOrBitwiseOperator(binary.operator()) && ast.resolvedType().isKnown())
                // A conditional with mixed numeric branches has the binary-numeric-promoted type
                // (buildTernary's ternaryType): `cond ? 1 : 2.0` is double, `cond ? 1 : 2L` is long.
                // The regex inference picks one branch instead, so `let x = cond ? 1 : 2.0` would emit
                // `final int x = ...` — invalid Java (lossy conversion). The known-type guard means
                // incompatible branches (unknown type) still fall through to the normal checks.
                || (ast instanceof TernaryExpression && ast.resolvedType().isKnown());
    }

    private static boolean isShiftOrBitwiseOperator(String operator) {
        return operator.equals("<<") || operator.equals(">>") || operator.equals(">>>")
                || operator.equals("&") || operator.equals("|") || operator.equals("^");
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
        // Delegates to primitiveNumericType so primitives, fully-qualified boxes
        // (java.lang.Integer) and simple-name boxes (Integer, e.g. the element type returned by
        // List<Integer>.get) are all recognized — they unbox to a numeric primitive in Java.
        return switch (primitiveNumericType(type.javaType())) {
            case "byte", "short", "int", "long", "float", "double", "char" -> true;
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
            case "java.lang.Byte", "Byte" -> "byte";
            case "java.lang.Short", "Short" -> "short";
            case "java.lang.Integer", "Integer" -> "int";
            case "java.lang.Long", "Long" -> "long";
            case "java.lang.Float", "Float" -> "float";
            case "java.lang.Double", "Double" -> "double";
            case "java.lang.Character", "Character" -> "char";
            default -> type;
        };
    }

    // `EnumType.CONSTANT` has the enum's type. Affogato enum constants are intentionally NOT registered
    // as fields (so codegen leaves the `EnumType.CONSTANT` access literal rather than lowering it to a
    // getter), so the type is resolved against the enum symbol's recorded constant list. Without this,
    // `let d = Day.MON` infers Object and emits `final Object d = Day.MON;`, which breaks later uses like
    // `d.name()` or passing `d` where a `Day` is expected (invalid Java).
    private TypeGuess enumConstantAccessType(String value, MethodContext context) {
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot >= value.length() - 1 || value.indexOf('(') >= 0 || value.indexOf('[') >= 0) {
            return TypeGuess.unknown();
        }
        String member = value.substring(dot + 1).trim();
        if (member.isEmpty() || !Character.isJavaIdentifierStart(member.charAt(0))
                || !member.chars().allMatch(Character::isJavaIdentifierPart)) {
            return TypeGuess.unknown();
        }
        ClassSymbol symbol = classSymbol(value.substring(0, dot).trim(), context.unit);
        if (symbol != null && symbol.isEnum && symbol.enumConstants.contains(member)) {
            return TypeGuess.of(symbol.name());
        }
        return TypeGuess.unknown();
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
        AffogatoSymbolResolver.PropertyHop hop = resolvePropertyHopOnType(ownerType.javaType(), property, context);
        return hop == null ? TypeGuess.unknown() : hop.resultType();
    }

    @Override
    public boolean isGetterSetterBackedPropertyAccess(PropertyAccessExpression property, MethodContext context) {
        TypeGuess receiverType = property.receiver().resolvedType().isKnown()
                ? property.receiver().resolvedType()
                : inferExpressionType(property.receiver().source(), context);
        if (!receiverType.isKnown() || receiverType.isNullLiteral()) {
            return false;
        }
        AffogatoSymbolResolver.PropertyHop hop = resolvePropertyHopOnType(receiverType.javaType(), property.property(), context);
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
            int equals = typeChecker.namedArgumentEquals(part);
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
     * is one. Returns {@code UNKNOWN_ARITY} when the shape is unrecognizable.
     */
    private int lambdaParameterArity(String header) {
        if (header == null) return TypeGuess.UNKNOWN_ARITY;
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

    @Override
    public String stripNullableSuffix(String typeName) {
        String type = stripTypeUseAnnotations(typeName.trim());
        if (type.endsWith("?") || type.endsWith("!")) {
            return type.substring(0, type.length() - 1);
        }
        return type;
    }

    private String stripTypeUseAnnotations(String typeName) {
        return typeName.replaceAll("@(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*\\s+", "");
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


    /**
     * Normalizes a Swift-style trailing closure into the parenthesized lambda form that the
     * rest of the pipeline already understands. {@code call(args) { p -> body }} becomes
     * {@code call(args, p -> body)} and {@code receiver.method { p -> body }} becomes
     * {@code receiver.method(p -> body)}. Returns {@code exprText} untouched when there is no
     * trailing closure, preserving full backward compatibility.
     */
    @Override
    public String mergeTrailingClosure(String exprText, String source,
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
        // Keep Affogato syntax here: the merged expression is parsed again by the type checker.
        String bodyText = closureBodyAffogatoText(body, source);
        String lambda = params + " -> " + bodyText;
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
    @Override
    public String lastParameterType(String callName, MethodContext context) {
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
    @Override
    public String supplierListElementType(String typeName) {
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

    /** Affogato source for a closure body, used when re-parsing a trailing-closure merge. */
    private String closureBodyAffogatoText(AffogatoParser.ClosureBodyContext body, String source) {
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
            if (statement.separators() == null) {
                block.append(sourceText(source, statement)).append(System.lineSeparator());
            }
        }
        block.append("}");
        return block.toString();
    }

    /** Renders a closure body as a Java lambda body: a single expression/block, or a generated statement block. */
    String closureBodyText(AffogatoParser.ClosureBodyContext body, String source, MethodContext context) {
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
            if (current == '"' && !isEscapedQuote(text, index)) {
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
            if (current == '"' && !isEscapedQuote(text, index)) {
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
            if (current == '"' && !isEscapedQuote(text, index)) {
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

    /**
     * Returns {@code true} when the {@code '"'} at {@code index} is preceded by an odd number of
     * backslashes, meaning it is an escaped quote and does NOT toggle string-literal mode.
     * Handles the {@code \\"} case: two backslashes then a real quote is NOT escaped.
     */
    private static boolean isEscapedQuote(String text, int index) {
        int backslashes = 0;
        for (int k = index - 1; k >= 0 && text.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
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
            // Extract only the immediate receiver chain ending at the dot, not the whole prefix.
            // For `"p" + a.label()` the receiver is `a`, not `"p" + a` (which infers as String and
            // makes `label()` look like a call on String). receiverStartInBuffer handles identifier
            // chains, call/array suffixes and string literals, stopping at a preceding operator.
            int start = typeChecker.receiverStartInBuffer(new StringBuilder(value.substring(0, dot)));
            return value.substring(start < 0 ? 0 : start, dot).trim();
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

    @Override
    public String simpleTypeName(String type) {
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

    @Override
    public String constructorImplementation(String typeName) {
        return typeChecker.constructorImplementation(typeName);
    }

    @Override
    public String getterName(String fieldName, TypeRef type) {
        String prefix = type.javaType().equals("boolean") || type.javaType().equals("Boolean") ? "is" : "get";
        return prefix + capitalize(fieldName);
    }

    @Override
    public String setterName(String fieldName) {
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


    private TypeRef typeRef(AffogatoParser.TypeRefContext context) {
        return parserRunner.typeRef(context);
    }

    private TypeRef inferType(String initializer) {
        return parserRunner.inferType(initializer);
    }

    @Override
    public String sourceText(String source, ParserRuleContext context) {
        return parserRunner.sourceText(source, context);
    }

    String unescapeAffogatoString(String s) {
        return parserRunner.unescapeAffogatoString(s);
    }

    String escapeForJavaString(String text) {
        return parserRunner.escapeForJavaString(text);
    }

    private static String numericLiteralType(String literal) {
        return AffogatoParserRunner.numericLiteralType(literal);
    }

    private int matchingBracket(String text, int open) {
        return parserRunner.matchingBracket(text, open);
    }

    private void validateDeclaredName(Path sourceFile, String name, String kind, int line, int column) {
        parserRunner.validateDeclaredName(sourceFile, name, kind, line, column);
    }

    AffogatoDiagnostic error(Path sourceFile, int line, int column, String code, String message) {
        return error(sourceFile, line, column, 1, code, message);
    }

    AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length);
    }

    private AffogatoDiagnostic warning(Path sourceFile, int line, int column, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.WARNING, code, message, sourceFile, line, column, 1);
    }







}
