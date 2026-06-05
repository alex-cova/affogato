package dev.affogato.compiler.internal;

import static dev.affogato.compiler.internal.TranspilerTypes.ClassSymbol;
import static dev.affogato.compiler.internal.TranspilerTypes.CompilationUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public final class ClassSymbolTableTest {
    @Test
    public void lookupPrefersSamePackageOverOtherPackageSimpleNameCollision() {
        ClassSymbolTable table = new ClassSymbolTable();
        table.register("dev.affogato.exec.collision.a", new ClassSymbol("dev.affogato.exec.collision.a", "Marker", "", false, List.of()));
        table.register("dev.affogato.exec.collision.b", new ClassSymbol("dev.affogato.exec.collision.b", "Marker", "", false, List.of()));

        CompilationUnit unitB = new CompilationUnit(
                Path.of("App.aff"),
                "",
                "dev.affogato.exec.collision.b",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        ClassSymbol resolved = table.lookup("Marker", unitB);
        assertNotNull(resolved);
        assertEquals("dev.affogato.exec.collision.b", resolved.packageName);
    }

    @Test
    public void ambiguousSimpleNameReturnsNullWithoutPackageOrImport() {
        ClassSymbolTable table = new ClassSymbolTable();
        table.register("pkg.a", new ClassSymbol("pkg.a", "Widget", "", false, List.of()));
        table.register("pkg.b", new ClassSymbol("pkg.b", "Widget", "", false, List.of()));

        CompilationUnit defaultPackage = new CompilationUnit(
                Path.of("App.aff"),
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertNull(table.lookup("Widget", defaultPackage));
    }
}
