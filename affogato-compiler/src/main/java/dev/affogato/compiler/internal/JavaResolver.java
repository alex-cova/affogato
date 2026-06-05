package dev.affogato.compiler.internal;

import static dev.affogato.compiler.internal.TranspilerTypes.*;
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
import java.util.HashSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class JavaResolver {
    private final URLClassLoader classLoader;
    private final Map<String, Optional<Class<?>>> classCache = new HashMap<>();
    private boolean lastResolutionAmbiguous;

    JavaResolver(List<Path> classpath) {
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

    boolean getterExists(String ownerType, String property, CompilationUnit unit) {
        return getterReturnType(ownerType, property, unit).isPresent();
    }

    boolean hasMethodNamed(String ownerType, String name, CompilationUnit unit) {
        return loadClass(ownerType, unit)
                .map(type -> methodsForInvocation(type, unit).stream().anyMatch(method -> method.getName().equals(name)))
                .orElse(false);
    }

    boolean typeExists(String typeName, CompilationUnit unit) {
        return classForType(typeName, unit).isPresent();
    }

    boolean isInterface(String typeName, CompilationUnit unit) {
        return classForType(typeName, unit).map(Class::isInterface).orElse(false);
    }

    boolean switchSelectorCompatible(TypeGuess selectorType, CompilationUnit unit) {
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

    boolean throwableCompatible(TypeGuess type, CompilationUnit unit) {
        return classForType(type.javaType(), unit)
                .map(Throwable.class::isAssignableFrom)
                .orElse(true);
    }

    Optional<TypeGuess> getterReturnType(String ownerType, String property, CompilationUnit unit) {
        return loadClass(ownerType, unit)
                .flatMap(type -> getterMethod(type, getterName(property, TypeRef.unspecified("Object")))
                        .or(() -> getterMethod(type, "is" + capitalize(property))))
                .map(method -> TypeGuess.of(typeName(method.getReturnType())));
    }

    Optional<String> getterInvocationName(String ownerType, String property, CompilationUnit unit) {
        return loadClass(ownerType, unit)
                .flatMap(type -> getterMethod(type, getterName(property, TypeRef.unspecified("Object")))
                        .or(() -> getterMethod(type, "is" + capitalize(property))))
                .map(Method::getName);
    }

    boolean fieldExists(String ownerType, String fieldName, CompilationUnit unit) {
        return fieldType(ownerType, fieldName, unit).isPresent();
    }

    Optional<TypeGuess> fieldType(String ownerType, String fieldName, CompilationUnit unit) {
        return loadClass(ownerType, unit)
                .flatMap(type -> fieldForInvocation(type, fieldName, unit))
                .map(field -> TypeGuess.of(genericTypeName(
                        field.getGenericType(),
                        classTypeBindings(field.getDeclaringClass(), ownerType),
                        unit
                )));
    }

    boolean fieldMutable(String ownerType, String fieldName, CompilationUnit unit) {
        return loadClass(ownerType, unit)
                .flatMap(type -> fieldForInvocation(type, fieldName, unit))
                .map(field -> !Modifier.isFinal(field.getModifiers()))
                .orElse(false);
    }

    boolean setterExists(String ownerType, String property, CompilationUnit unit) {
        return setterParameterType(ownerType, property, unit).isPresent();
    }

    Optional<TypeGuess> setterParameterType(String ownerType, String property, CompilationUnit unit) {
        return loadClass(ownerType, unit)
                .flatMap(type -> setterMethod(type, setterName(property), unit))
                .map(method -> TypeGuess.of(genericTypeName(
                        method.getGenericParameterTypes()[0],
                        classTypeBindings(method.getDeclaringClass(), ownerType),
                        unit
                )));
    }

    Optional<ResolvedArguments> resolveConstructorArguments(String className, List<TypedArgument> arguments, CompilationUnit unit) {
        return loadClass(className, unit)
                .flatMap(type -> resolveExecutableArguments(constructorsForInvocation(type, unit), arguments, unit, className)
                        .map(ScoredExecutable::resolved));
    }

    Optional<ResolvedArguments> resolveMethodArguments(String ownerType, String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
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

    Optional<ResolvedArguments> resolveStaticMethodArguments(String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
        return resolveExecutableArguments(staticMethodExecutables(methodName, unit), arguments, unit, "")
                .map(ScoredExecutable::resolved);
    }

    Optional<TypeGuess> methodReturnType(String ownerType, String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
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

    Optional<TypeGuess> staticMethodReturnType(String methodName, List<TypedArgument> arguments, CompilationUnit unit) {
        return resolveExecutableArguments(staticMethodExecutables(methodName, unit), arguments, unit, "")
                .filter(match -> match.executable() instanceof Method)
                .map(match -> TypeGuess.of(genericTypeName(
                        ((Method) match.executable()).getGenericReturnType(),
                        match.typeBindings(),
                        unit
                )));
    }

    List<Executable> staticMethodExecutables(String methodName, CompilationUnit unit) {
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

    List<String> staticMethodOwners(String methodName, CompilationUnit unit) {
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

    List<Constructor<?>> constructorsForInvocation(Class<?> type, CompilationUnit unit) {
        List<Constructor<?>> constructors = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isAccessible(constructor, unit)) {
                constructors.add(constructor);
            }
        }
        return constructors;
    }

    List<Method> methodsForInvocation(Class<?> type, CompilationUnit unit) {
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

    boolean isAccessible(Executable executable, CompilationUnit unit) {
        int modifiers = executable.getModifiers();
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        return executable.getDeclaringClass().getPackageName().equals(unit.packageName());
    }

    boolean isAccessible(Field field, CompilationUnit unit) {
        int modifiers = field.getModifiers();
        if (Modifier.isPrivate(modifiers)) {
            return false;
        }
        if (Modifier.isPublic(modifiers)) {
            return true;
        }
        return field.getDeclaringClass().getPackageName().equals(unit.packageName());
    }

    String methodSignatureKey(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + parameterDescriptor(method.getParameterTypes());
    }

    Optional<ScoredExecutable> resolveExecutableArguments(
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

    boolean lastResolutionAmbiguous() {
        return lastResolutionAmbiguous;
    }

    Optional<ScoredExecutable> chooseMostSpecificExecutable(
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
    Optional<ScoredExecutable> mostSpecificOverrideEquivalent(List<ScoredExecutable> best, int argumentCount) {
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
    int returnTypeSpecificity(ScoredExecutable candidate, List<ScoredExecutable> best) {
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

    Optional<ScoredExecutable> scoreExecutable(
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

    Map<TypeVariable<?>, TypeGuess> inferTypeBindings(
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

    boolean validTypeBindings(Map<TypeVariable<?>, TypeGuess> bindings, CompilationUnit unit) {
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

    Map<TypeVariable<?>, TypeGuess> classTypeBindings(Class<?> ownerClass, String ownerType) {
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

    void inferFromTypes(
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

    void bindTypeVariable(
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

    TypeGuess normalizeTypeVariableBinding(TypeGuess actualType, CompilationUnit unit) {
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
    String captureWildcard(String typeName) {
        String trimmed = typeName.trim();
        if (trimmed.equals("?") || trimmed.startsWith("? super ")) {
            return "java.lang.Object";
        }
        if (trimmed.startsWith("? extends ")) {
            return trimmed.substring("? extends ".length()).trim();
        }
        return typeName;
    }

    TypeGuess commonSuperType(TypeGuess left, TypeGuess right, CompilationUnit unit) {
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

    Class<?> boxedType(Class<?> type) {
        return type.isPrimitive() ? BOXED_PRIMITIVES.getOrDefault(type, type) : type;
    }

    Optional<TypeGuess> arrayType(TypeGuess type) {
        if (!type.isKnown() || type.isNullLiteral()) {
            return Optional.empty();
        }
        String javaType = type.javaType();
        if (!javaType.endsWith("[]")) {
            return Optional.empty();
        }
        return Optional.of(TypeGuess.of(javaType.substring(0, javaType.length() - 2)));
    }

    Type genericComponentType(Type type) {
        if (type instanceof GenericArrayType genericArrayType) {
            return genericArrayType.getGenericComponentType();
        }
        if (type instanceof Class<?> clazz && clazz.isArray()) {
            return clazz.getComponentType();
        }
        return Object.class;
    }

    List<String> genericArguments(String typeName) {
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

    boolean assignmentCompatible(TypeGuess source, String targetType, CompilationUnit unit, InvocationPhase phase) {
        if (conversionScore(source, targetType, unit, phase) >= NO_CONVERSION) {
            return false;
        }
        if (source.isLambda()) {
            return true;
        }
        return genericTypeArgumentsAssignable(source.javaType(), targetType, unit);
    }

    boolean genericTypeArgumentsAssignable(String sourceType, String targetType, CompilationUnit unit) {
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

    boolean genericTypeArgumentAssignable(String sourceArgument, String targetArgument, CompilationUnit unit) {
        if (sourceArgument.contains("@Nullable") && targetArgument.contains("@NotNull")) {
            return false;
        }
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

    int conversionScore(
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

    int conversionScore(
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

    boolean genericArgumentsCompatible(
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

    boolean genericArgumentCompatible(
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

    Optional<Method> getterMethod(Class<?> type, String methodName) {
        try {
            return Optional.of(type.getMethod(methodName));
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        }
    }

    Optional<Field> fieldForInvocation(Class<?> type, String fieldName, CompilationUnit unit) {
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

    Optional<Method> setterMethod(Class<?> type, String methodName, CompilationUnit unit) {
        return methodsForInvocation(type, unit).stream()
                .filter(method -> method.getName().equals(methodName))
                .filter(method -> method.getParameterCount() == 1)
                .findFirst();
    }

    boolean executableMoreSpecific(
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

    List<Class<?>> effectiveParameterTypes(Executable executable, InvocationPhase phase, int argumentCount) {
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

    boolean affogatoParametersMoreSpecific(List<ParamDecl> left, List<ParamDecl> right, CompilationUnit unit) {
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

    boolean typeMoreSpecific(String leftType, String rightType, CompilationUnit unit) {
        Optional<Class<?>> leftClass = classForType(leftType, unit);
        Optional<Class<?>> rightClass = classForType(rightType, unit);
        if (leftClass.isPresent() && rightClass.isPresent()) {
            return typeMoreSpecific(leftClass.get(), rightClass.get());
        }
        String left = rawClassName(leftType);
        String right = rawClassName(rightType);
        return !left.equals(right) && (right.equals("Object") || right.equals("java.lang.Object"));
    }

    boolean typeMoreSpecific(Class<?> leftType, Class<?> rightType) {
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

    boolean castPossible(TypeGuess source, String targetType, CompilationUnit unit) {
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

    boolean numericPrimitive(Class<?> type) {
        return type == byte.class
                || type == short.class
                || type == int.class
                || type == long.class
                || type == float.class
                || type == double.class
                || type == char.class;
    }

    int conversionScore(TypedArgument argument, String targetType, CompilationUnit unit, InvocationPhase phase) {
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

    int conversionScore(TypeGuess source, String targetType, CompilationUnit unit, InvocationPhase phase) {
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

    int conversionScore(TypeGuess source, Class<?> targetType, CompilationUnit unit, InvocationPhase phase) {
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

    boolean isFunctionalInterface(Class<?> targetType) {
        return functionalMethod(targetType).isPresent();
    }

    /** Returns the single abstract method of a functional interface, or empty when {@code targetType} is not one. */
    Optional<Method> functionalMethod(Class<?> targetType) {
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

    boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    String parameterDescriptor(Class<?>[] parameterTypes) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : parameterTypes) {
            descriptor.append(parameterType.getName()).append(';');
        }
        return descriptor.append(')').toString();
    }

    int fallbackConversionScore(String sourceType, Class<?> targetType) {
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

    int primitiveWideningScore(Class<?> sourceType, Class<?> targetType) {
        List<Class<?>> widening = PRIMITIVE_WIDENING.getOrDefault(sourceType, List.of());
        int index = widening.indexOf(targetType);
        return index < 0 ? NO_CONVERSION : 10 + index;
    }

    int inheritanceDistance(Class<?> sourceType, Class<?> targetType) {
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

    int interfaceDistance(Class<?> sourceType, Class<?> targetType, int depth) {
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

    Optional<Class<?>> classForType(String typeName, CompilationUnit unit) {
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

    String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        String canonical = type.getCanonicalName();
        return canonical == null ? type.getName() : canonical;
    }

    String genericTypeName(Type type, Map<TypeVariable<?>, TypeGuess> bindings, CompilationUnit unit) {
        return genericTypeName(type, bindings, unit, new HashSet<>());
    }

    String genericTypeName(Type type, Map<TypeVariable<?>, TypeGuess> bindings, CompilationUnit unit, Set<Type> visiting) {
        if (type instanceof Class<?> clazz) {
            return typeName(clazz);
        }
        if (!visiting.add(type)) {
            if (type instanceof TypeVariable<?> typeVariable) {
                return typeVariable.getName();
            }
            return "java.lang.Object";
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            TypeGuess bound = bindings.get(typeVariable);
            if (bound != null && bound.isKnown() && !bound.isNullLiteral()) {
                return bound.javaType();
            }
            Type[] bounds = typeVariable.getBounds();
            return bounds.length == 0 ? "java.lang.Object" : genericTypeName(bounds[0], bindings, unit, new HashSet<>(visiting));
        }
        if (type instanceof ParameterizedType parameterizedType) {
            String raw = genericTypeName(parameterizedType.getRawType(), bindings, unit, new HashSet<>(visiting));
            Type[] arguments = parameterizedType.getActualTypeArguments();
            if (arguments.length == 0) {
                return raw;
            }
            List<String> rendered = new ArrayList<>();
            for (Type argument : arguments) {
                rendered.add(genericTypeName(argument, bindings, unit, new HashSet<>(visiting)));
            }
            return raw + "<" + String.join(", ", rendered) + ">";
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return genericTypeName(genericArrayType.getGenericComponentType(), bindings, unit, new HashSet<>(visiting)) + "[]";
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] lowerBounds = wildcardType.getLowerBounds();
            if (lowerBounds.length > 0) {
                return "? super " + genericTypeName(lowerBounds[0], bindings, unit, new HashSet<>(visiting));
            }
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] != Object.class) {
                return "? extends " + genericTypeName(upperBounds[0], bindings, unit, new HashSet<>(visiting));
            }
            return "?";
        }
        return "java.lang.Object";
    }

    String simpleType(String type) {
        String cleaned = rawClassName(type);
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }

    Optional<Class<?>> loadClass(String requestedName, CompilationUnit unit) {
        String cleaned = rawClassName(requestedName);
        for (String candidate : classCandidates(cleaned, unit)) {
            if (classCache.containsKey(candidate)) {
                Optional<Class<?>> cached = classCache.get(candidate);
                if (cached.isPresent()) return cached;
                continue; // known miss — try next candidate
            }
            try {
                Optional<Class<?>> result = Optional.of(Class.forName(candidate, false, classLoader));
                classCache.put(candidate, result);
                return result;
            } catch (ClassNotFoundException ignored) {
                classCache.put(candidate, Optional.empty());
            }
        }
        return Optional.empty();
    }

    /** Simple names of all Java superclasses and (transitively) implemented interfaces of {@code type}. */
    List<String> ancestorSimpleNames(String type, CompilationUnit unit) {
        return loadClass(type, unit)
                .map(loaded -> {
                    List<String> names = new ArrayList<>();
                    Set<Class<?>> seen = new LinkedHashSet<>();
                    collectAncestors(loaded, names, seen);
                    return names;
                })
                .orElse(List.of());
    }

    void collectAncestors(Class<?> type, List<String> names, Set<Class<?>> seen) {
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

    String rawClassName(String typeName) {
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

    List<String> classCandidates(String className, CompilationUnit unit) {
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

    List<String> innerClassCandidates(String className) {
        List<String> candidates = new ArrayList<>();
        for (int index = className.lastIndexOf('.'); index > 0; index = className.lastIndexOf('.', index - 1)) {
            candidates.add(className.substring(0, index) + "$" + className.substring(index + 1).replace('.', '$'));
        }
        return candidates;
    }
    private String getterName(String fieldName, TypeRef type) {
        String prefix = type.javaType().equals("boolean") || type.javaType().equals("Boolean") ? "is" : "get";
        return prefix + capitalize(fieldName);
    }
    private String capitalize(String value) {
        if (value.isBlank()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
    private String setterName(String fieldName) {
        return "set" + capitalize(fieldName);
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

}
