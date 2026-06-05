package dev.affogato.compiler.internal;

import static dev.affogato.compiler.internal.TranspilerTypes.ClassSymbol;
import static dev.affogato.compiler.internal.TranspilerTypes.CompilationUnit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Affogato type symbols keyed by fully-qualified name. Simple-name lookups are resolved only when
 * unambiguous (same package, import, or a single registered type).
 */
final class ClassSymbolTable {
    private final Map<String, ClassSymbol> byFqn = new LinkedHashMap<>();

    boolean containsFqn(String fqn) {
        return byFqn.containsKey(fqn);
    }

    void register(String packageName, ClassSymbol symbol) {
        byFqn.put(fqn(packageName, symbol.name()), symbol);
    }

    ClassSymbol lookup(String typeName, CompilationUnit unit) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        String raw = stripGenericsAndArrays(typeName.trim());
        ClassSymbol direct = byFqn.get(raw);
        if (direct != null) {
            return direct;
        }
        String simple = simpleTypeName(raw);
        if (!unit.packageName().isBlank()) {
            ClassSymbol samePackage = byFqn.get(unit.packageName() + "." + simple);
            if (samePackage != null) {
                return samePackage;
            }
        }
        for (String importName : unit.imports()) {
            if (importName.endsWith(".*")) {
                String packagePrefix = importName.substring(0, importName.length() - 2);
                ClassSymbol imported = byFqn.get(packagePrefix + "." + simple);
                if (imported != null) {
                    return imported;
                }
                continue;
            }
            if (importName.equals(simple) || importName.endsWith("." + simple)) {
                ClassSymbol imported = byFqn.get(importName);
                if (imported != null) {
                    return imported;
                }
            }
        }
        ClassSymbol unique = null;
        for (Map.Entry<String, ClassSymbol> entry : byFqn.entrySet()) {
            if (matchesSimpleName(entry.getKey(), simple)) {
                if (unique != null && unique != entry.getValue()) {
                    return null;
                }
                unique = entry.getValue();
            }
        }
        return unique;
    }

    private static boolean matchesSimpleName(String fqn, String simple) {
        return fqn.equals(simple) || fqn.endsWith("." + simple);
    }

    private static String fqn(String packageName, String simpleName) {
        return packageName.isBlank() ? simpleName : packageName + "." + simpleName;
    }

    private static String simpleTypeName(String type) {
        String cleaned = stripGenericsAndArrays(type);
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }

    private static String stripGenericsAndArrays(String type) {
        String cleaned = type;
        int generic = cleaned.indexOf('<');
        if (generic >= 0) {
            cleaned = cleaned.substring(0, generic);
        }
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        return cleaned;
    }
}
