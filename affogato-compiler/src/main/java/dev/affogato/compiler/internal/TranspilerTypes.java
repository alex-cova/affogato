package dev.affogato.compiler.internal;

import dev.affogato.compiler.parser.AffogatoParser;
import java.nio.file.Path;
import java.lang.reflect.Executable;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TranspilerTypes {
    /** Arity sentinel for poly expressions whose parameter count is not statically known (e.g. method references). */
    static final int UNKNOWN_ARITY = TypeGuess.UNKNOWN_ARITY;
    static final Set<String> PRIMITIVES = Set.of(
            "byte", "short", "int", "long", "float", "double", "boolean", "char"
    );
    static final Map<String, Class<?>> PRIMITIVE_CLASSES = Map.of(
            "byte", byte.class,
            "short", short.class,
            "int", int.class,
            "long", long.class,
            "float", float.class,
            "double", double.class,
            "boolean", boolean.class,
            "char", char.class
    );
    static final Map<Class<?>, Class<?>> BOXED_PRIMITIVES = Map.of(
            byte.class, Byte.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            boolean.class, Boolean.class,
            char.class, Character.class
    );
    static final Map<Class<?>, Class<?>> UNBOXED_PRIMITIVES = Map.of(
            Byte.class, byte.class,
            Short.class, short.class,
            Integer.class, int.class,
            Long.class, long.class,
            Float.class, float.class,
            Double.class, double.class,
            Boolean.class, boolean.class,
            Character.class, char.class
    );
    static final Map<Class<?>, List<Class<?>>> PRIMITIVE_WIDENING = Map.of(
            byte.class, List.of(short.class, int.class, long.class, float.class, double.class),
            short.class, List.of(int.class, long.class, float.class, double.class),
            char.class, List.of(int.class, long.class, float.class, double.class),
            int.class, List.of(long.class, float.class, double.class),
            long.class, List.of(float.class, double.class),
            float.class, List.of(double.class)
    );
    static final int NO_CONVERSION = 1_000_000;

    private TranspilerTypes() {}

    record SourceLocation(int line, int column) {
    }

    record CompilationUnit(Path sourceFile, String source, String packageName, List<String> imports, List<ParsedClass> classes, List<ParsedEnum> enums, List<ParsedInterface> interfaces, List<ParsedRecord> records, List<ExtensionFuncDecl> extensions) {
    }

    record ParsedClass(
            String access,
            String name,
            List<TypeParamDecl> typeParameters,
            List<String> superTypes,
            List<ParamDecl> compactParameters,
            List<FieldDecl> fields,
            List<ConstructorDecl> constructors,
            List<MethodDecl> methods,
            List<String> annotations,
            List<ParsedEnum> nestedEnums,
            int declarationLine,
            int declarationColumn
    ) {
        // Synthetic/shape classes (extension holders, interface and record shapes) carry no nested types.
        ParsedClass(
                String access,
                String name,
                List<TypeParamDecl> typeParameters,
                List<String> superTypes,
                List<ParamDecl> compactParameters,
                List<FieldDecl> fields,
                List<ConstructorDecl> constructors,
                List<MethodDecl> methods,
                List<String> annotations,
                int declarationLine,
                int declarationColumn
        ) {
            this(access, name, typeParameters, superTypes, compactParameters, fields, constructors, methods,
                    annotations, List.of(), declarationLine, declarationColumn);
        }
    }

    record ParsedEnum(String access, String name, List<String> constants, List<String> annotations, int declarationLine, int declarationColumn) {
    }

    record ParsedRecord(
            String access,
            String name,
            List<TypeParamDecl> typeParameters,
            List<ParamDecl> components,
            List<String> superTypes,
            List<MethodDecl> methods,
            List<ConstructorDecl> constructors,
            List<String> annotations,
            int declarationLine,
            int declarationColumn
    ) {
    }

    record InterfaceMethod(
            boolean isDefault,
            TypeRef returnType,
            String name,
            List<ParamDecl> parameters,
            AffogatoParser.BlockContext body,
            int line
    ) {
    }

    record ParsedInterface(String access, String name, List<TypeParamDecl> typeParameters, List<InterfaceMethod> methods, List<String> annotations, int declarationLine, int declarationColumn) {
    }

    record FieldDecl(String access, boolean isStatic, boolean mutable, String name, TypeRef type, String initializer, int line, List<String> annotations) {
    }

    record ConstructorDecl(String access, List<ParamDecl> parameters, AffogatoParser.BlockContext body, int line, List<String> annotations) {
    }

    record MethodDecl(
            String access,
            boolean isStatic,
            boolean isOverride,
            boolean isAbstract,
            List<TypeParamDecl> typeParameters,
            TypeRef returnType,
            String name,
            List<ParamDecl> parameters,
            AffogatoParser.BlockContext body,
            int line,
            List<String> annotations
    ) {
    }

    record ExtensionFuncDecl(
            TypeRef receiverType,
            String name,
            TypeRef returnType,
            List<ParamDecl> parameters,
            AffogatoParser.BlockContext body,
            int line,
            List<String> annotations
    ) {
    }

    record ExtensionSymbol(
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

    record TypeParamDecl(String name, String bound) {
        String declaration() {
            return bound.isBlank() ? name : name + " extends " + bound;
        }
    }

    record ParamDecl(String name, TypeRef type, PropertyKind propertyKind, List<String> annotations) {
    }

    record ConstructorSymbol(List<ParamDecl> parameters) {
    }

    record MethodSymbol(String name, TypeRef returnType, List<ParamDecl> parameters, boolean isStatic) {
    }

    record ResolvedArguments(List<String> expressions) {
    }

    record ScoredAffogatoArguments(int score, ResolvedArguments resolved, List<ParamDecl> parameters, InvocationPhase phase) {
    }

    record ScoredReturn(int score, TypeGuess returnType) {
    }

    record ExtensionMatch(ExtensionSymbol symbol, ResolvedArguments resolved) {
    }

    record ScoredExecutable(
            int score,
            ResolvedArguments resolved,
            Executable executable,
            InvocationPhase phase,
            Map<TypeVariable<?>, TypeGuess> typeBindings
    ) {
    }

    enum PropertyKind {
        NONE,
        VAR,
        LET
    }

    enum Nullability {
        UNSPECIFIED,
        NULLABLE,
        NOT_NULL
    }

    enum InvocationPhase {
        STRICT,
        LOOSE,
        VARARGS
    }

    record TypeRef(String javaType, Nullability nullability) {
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

    record Modifiers(String access, boolean isStatic, boolean isOverride, boolean isAbstract) {
    }

    static final class ClassSymbol {
        final String packageName;
        final String name;
        final String extendsType;
        final boolean isInterface;
        boolean isRecord;
        boolean isEnum;
        final List<String> enumConstants = new ArrayList<>();
        final List<String> typeParamNames;
        final Map<String, FieldSymbol> fields = new LinkedHashMap<>();
        final Map<String, List<MethodSymbol>> methods = new LinkedHashMap<>();
        final List<ConstructorSymbol> constructors = new ArrayList<>();

        ClassSymbol(String packageName, String name, String extendsType, boolean isInterface, List<String> typeParamNames) {
            this.packageName = packageName;
            this.name = name;
            this.extendsType = extendsType;
            this.isInterface = isInterface;
            this.typeParamNames = typeParamNames;
        }

        String name() {
            return name;
        }

        String extendsType() {
            return extendsType;
        }

        boolean isInterface() {
            return isInterface;
        }

        boolean isRecord() {
            return isRecord;
        }
    }

    record FieldSymbol(String name, TypeRef type, boolean mutable, boolean isStatic) {
    }

    record ParsedUnit(Path sourceFile, CompilationUnit unit) {
        static ParsedUnit empty(Path sourceFile, String source) {
            return new ParsedUnit(sourceFile, new CompilationUnit(sourceFile, source, "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        }
    }
}
