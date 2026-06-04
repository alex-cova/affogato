package dev.affogato.golden;

public class ReflectionAnnotations {
    public String run() {
        try {
            final java.lang.annotation.Annotation classAnnotation = AnnotatedReflectionTarget.class.getAnnotation(Deprecated.class);
            final java.lang.reflect.Method method = AnnotatedReflectionTarget.class.getMethod("legacy");
            final java.lang.annotation.Annotation methodAnnotation = method.getAnnotation(Deprecated.class);
            final java.lang.Class<? extends java.lang.annotation.Annotation> classAnnotationType = classAnnotation.annotationType();
            final java.lang.Class<? extends java.lang.annotation.Annotation> methodAnnotationType = methodAnnotation.annotationType();
            final java.lang.String className = classAnnotationType.getSimpleName();
            final java.lang.String methodName = methodAnnotationType.getSimpleName();
            return className + ":" + methodName;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

}
