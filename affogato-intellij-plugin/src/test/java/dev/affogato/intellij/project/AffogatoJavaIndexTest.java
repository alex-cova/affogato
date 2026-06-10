package dev.affogato.intellij.project;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public final class AffogatoJavaIndexTest extends BasePlatformTestCase {
    public void testFindsProjectJavaSourceClass() {
        myFixture.addFileToProject("com/example/util/Util.java", """
                package com.example.util;

                public class Util {
                }
                """);
        List<PsiClass> classes = AffogatoJavaIndex.classesMatchingPrefix(
                getProject(),
                GlobalSearchScope.allScope(getProject()),
                "com.example.util",
                "Util"
        );

        assertEquals(1, classes.size());
        assertEquals("com.example.util.Util", classes.get(0).getQualifiedName());
    }

    public void testFindsProjectJavaSourceClassWithCompletionScope() {
        myFixture.addFileToProject("com/example/util/Util.java", """
                package com.example.util;

                public class Util {
                }
                """);
        PsiFile file = myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import com.example.util.<caret>

                class App {
                }
                """);

        List<PsiClass> classes = AffogatoJavaIndex.classesMatchingPrefix(
                getProject(),
                AffogatoJavaIndex.scopeFor(file),
                "com.example.util",
                ""
        );

        assertFalse(classes.isEmpty());
        assertEquals("com.example.util.Util", classes.get(0).getQualifiedName());
    }

    public void testFindsMultipleProjectJavaSourceClassesWithCompletionScope() {
        myFixture.addFileToProject("com/example/util/Alpha.java", """
                package com.example.util;

                public class Alpha {
                }
                """);
        myFixture.addFileToProject("com/example/util/Beta.java", """
                package com.example.util;

                public class Beta {
                }
                """);
        PsiFile file = myFixture.configureByText("App.aff", """
                package dev.affogato.test

                import com.example.util.<caret>

                class App {
                }
                """);

        List<PsiClass> classes = AffogatoJavaIndex.classesMatchingPrefix(
                getProject(),
                AffogatoJavaIndex.scopeFor(file),
                "com.example.util",
                ""
        );

        assertEquals(2, classes.size());
    }

    public void testResolvesProjectJavaClassesByQualifiedName() {
        myFixture.addFileToProject("java/util/ArrayList.java", """
                package java.util;

                public class ArrayList {
                }
                """);
        GlobalSearchScope scope = GlobalSearchScope.allScope(getProject());
        PsiClass arrayList = AffogatoJavaIndex.findClassByQualifiedName(
                getProject(),
                scope,
                "java.util.ArrayList"
        );
        assertNotNull(arrayList);
        assertEquals("java.util.ArrayList", arrayList.getQualifiedName());
    }
}
