package dev.affogato.compiler.internal;

import dev.affogato.compiler.AffogatoDiagnostic;
import dev.affogato.compiler.SourceLocations;
import static dev.affogato.compiler.internal.TranspilerTypes.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class AffogatoSymbolResolver implements AutoCloseable {
    private final List<AffogatoDiagnostic> diagnostics;
    private final JavaResolver javaResolver;
    private final ClassSymbolTable classSymbols = new ClassSymbolTable();
    private final Map<String, List<ExtensionSymbol>> extensionSymbols = new LinkedHashMap<>();

    AffogatoSymbolResolver(List<AffogatoDiagnostic> diagnostics, List<Path> classpath, Path javaMetadataCacheDirectory) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.javaResolver = new JavaResolver(classpath, javaMetadataCacheDirectory);
    }

    @Override
    public void close() {
        javaResolver.close();
    }

    JavaResolver javaResolver() {
        return javaResolver;
    }

    ClassSymbolTable classSymbols() {
        return classSymbols;
    }

    Map<String, List<ExtensionSymbol>> extensionSymbols() {
        return extensionSymbols;
    }

    void registerSymbols(CompilationUnit unit) {
        for (ParsedClass clazz : unit.classes()) {
            registerClassSymbol(unit, clazz.name(), clazz.declarationLine(), clazz.declarationColumn(), () -> {
                String extendsType = clazz.superTypes().isEmpty() ? "" : clazz.superTypes().get(0);
                ClassSymbol symbol = new ClassSymbol(unit.packageName(), clazz.name(), extendsType, false,
                        clazz.typeParameters().stream().map(TypeParamDecl::name).toList());
                for (FieldDecl field : clazz.fields()) {
                    symbol.fields.put(field.name(), new FieldSymbol(field.name(), field.type(), field.mutable(), field.isStatic()));
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
            registerEnumSymbol(unit, parsedEnum);
        }
        // Nested enums are registered by simple name alongside top-level types so `Day.MON` resolves
        // from inside the enclosing class; they are emitted nested in the class's generated Java.
        for (ParsedClass clazz : unit.classes()) {
            for (ParsedEnum nested : clazz.nestedEnums()) {
                registerEnumSymbol(unit, nested);
            }
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
                    symbol.fields.put(component.name(), new FieldSymbol(component.name(), component.type(), false, false));
                    symbol.methods.computeIfAbsent(component.name(), ignored -> new ArrayList<>())
                            .add(new MethodSymbol(component.name(), component.type(), List.of(), false));
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

    private void registerEnumSymbol(CompilationUnit unit, ParsedEnum parsedEnum) {
        registerClassSymbol(unit, parsedEnum.name(), parsedEnum.declarationLine(), parsedEnum.declarationColumn(), () -> {
            ClassSymbol symbol = new ClassSymbol(unit.packageName(), parsedEnum.name(), "", false, List.of());
            symbol.isEnum = true;
            symbol.enumConstants.addAll(parsedEnum.constants());
            symbol.constructors.add(new ConstructorSymbol(List.of()));
            // java.lang.Enum instance methods, so `value.name()` / `value.ordinal()` resolve on an
            // enum-typed receiver (the symbol has no extends chain the resolver could walk to Enum).
            registerEnumMethod(symbol, "name", "String");
            registerEnumMethod(symbol, "ordinal", "int");
            registerEnumMethod(symbol, "toString", "String");
            symbol.methods.computeIfAbsent("values", ignored -> new ArrayList<>())
                    .add(new MethodSymbol("values", TypeRef.unspecified(parsedEnum.name() + "[]"), List.of(), true));
            symbol.methods.computeIfAbsent("valueOf", ignored -> new ArrayList<>())
                    .add(new MethodSymbol("valueOf", TypeRef.unspecified(parsedEnum.name()),
                            List.of(new ParamDecl("name", TypeRef.unspecified("String"), PropertyKind.NONE, List.of())), true));
            return symbol;
        });
    }

    private void registerEnumMethod(ClassSymbol symbol, String name, String returnType) {
        symbol.methods.computeIfAbsent(name, ignored -> new ArrayList<>())
                .add(new MethodSymbol(name, TypeRef.unspecified(returnType), List.of(), false));
    }

    ClassSymbol lookupClass(String name, CompilationUnit unit) {
        return classSymbols.lookup(name, unit);
    }

    ClassSymbol classSymbol(String type, CompilationUnit unit) {
        return lookupClass(type, unit);
    }

    void validateTypeRef(TypeRef type, CompilationUnit unit, int line, int column, Set<String> activeTypeParams) {
        validateTypeName(type.javaType(), unit, line, column, activeTypeParams);
    }

    void validateTypeName(String typeName, CompilationUnit unit, int line, int column, Set<String> activeTypeParams) {
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
                    validateTypeName(stripWildcardBound(argument), unit, line, column, activeTypeParams);
                }
            }
            raw = raw.substring(0, generic);
        }
        if (PRIMITIVES.contains(raw) || activeTypeParams.contains(raw) || lookupClass(raw, unit) != null || javaResolver.typeExists(raw, unit)) {
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

    String stripWildcardBound(String typeName) {
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

    record PropertyHop(String accessor, boolean call, TypeGuess resultType) {
    }

    // Resolves a single `.property` read on a known owner type to its Java accessor, mirroring the
    // four-path order used for the first hop: Affogato field (getter, or direct for records), array
    // `length`, Java getter, then a directly-accessible Java field. Returns null when unresolvable.
    PropertyHop resolvePropertyHopOnType(String ownerType, String property, MethodContext context) {
        String resolvedOwnerType = context.activeTypeParams.contains(ownerType) ? "java.lang.Object" : ownerType;
        FieldSymbol field = fieldForOwnerType(resolvedOwnerType, property, context);
        if (field != null) {
            ClassSymbol ownerSymbol = lookupClass(resolvedOwnerType, context.unit);
            if (field.isStatic()) {
                return new PropertyHop(property, false, TypeGuess.of(field.type().javaType()));
            }
            String accessor = ownerSymbol != null && ownerSymbol.isRecord() ? property : getterName(property, field.type());
            return new PropertyHop(accessor, true, TypeGuess.of(field.type().javaType()));
        }
        ClassSymbol ownerSymbol = lookupClass(resolvedOwnerType, context.unit);
        if (ownerSymbol != null && ownerSymbol.isEnum && ownerSymbol.enumConstants.contains(property)) {
            return new PropertyHop(property, false, TypeGuess.of(ownerSymbol.name()));
        }
        if (isArrayLengthAccess(resolvedOwnerType, property)) {
            return new PropertyHop(property, false, TypeGuess.of("int"));
        }
        if (context.javaResolver.getterExists(resolvedOwnerType, property, context.unit)) {
            String getter = context.javaResolver.getterInvocationName(resolvedOwnerType, property, context.unit)
                    .orElse(getterName(property, TypeRef.unspecified("Object")));
            TypeGuess resultType = context.javaResolver.getterReturnType(resolvedOwnerType, property, context.unit)
                    .orElse(TypeGuess.unknown());
            return new PropertyHop(getter, true, resultType);
        }
        if (context.javaResolver.fieldExists(resolvedOwnerType, property, context.unit)) {
            TypeGuess resultType = context.javaResolver.fieldType(resolvedOwnerType, property, context.unit)
                    .orElse(TypeGuess.unknown());
            return new PropertyHop(property, false, resultType);
        }
        return null;
    }

    FieldSymbol resolveField(String owner, String property, MethodContext context) {
        String type = context.variableTypes.get(owner);
        if (type == null) {
            return null;
        }
        ClassSymbol symbol = lookupClass(type, context.unit);
        if (symbol == null) {
            return null;
        }
        return symbol.fields.get(property);
    }

    FieldSymbol fieldForOwnerType(String ownerType, String property, MethodContext context) {
        Set<String> seen = new LinkedHashSet<>();
        String current = ownerType;
        while (current != null && !current.isBlank()) {
            ClassSymbol symbol = lookupClass(current, context.unit);
            if (symbol == null || !seen.add(symbol.name())) {
                break;
            }
            FieldSymbol field = symbol.fields.get(property);
            if (field != null) {
                return field;
            }
            current = symbol.extendsType();
        }
        return null;
    }

    private static String extensionsHolderName(CompilationUnit unit) {
        String fileName = unit.sourceFile().getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return base + "Extensions";
    }

    private static String simpleTypeName(String type) {
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

    private static boolean isArrayLengthAccess(String ownerType, String property) {
        return ownerType.endsWith("[]") && property.equals("length");
    }

    private static String getterName(String fieldName, TypeRef type) {
        String prefix = type.javaType().equals("boolean") || type.javaType().equals("Boolean") ? "is" : "get";
        return prefix + capitalize(fieldName);
    }

    private static String capitalize(String value) {
        if (value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    private static String stripNullableSuffix(String typeName) {
        String type = stripTypeUseAnnotations(typeName.trim());
        if (type.endsWith("?") || type.endsWith("!")) {
            return type.substring(0, type.length() - 1);
        }
        return type;
    }

    private static String stripTypeUseAnnotations(String typeName) {
        return typeName.replaceAll("@(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*\\s+", "");
    }

    private static List<String> splitTopLevel(String text, char delimiter) {
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

    private static AffogatoDiagnostic error(Path sourceFile, int line, int column, String code, String message) {
        return error(sourceFile, line, column, 1, code, message);
    }

    private static AffogatoDiagnostic error(Path sourceFile, int line, int column, int length, String code, String message) {
        return new AffogatoDiagnostic(AffogatoDiagnostic.Severity.ERROR, code, message, sourceFile, line, column, length);
    }
}
