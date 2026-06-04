package dev.affogato.golden;

public class ReflectionConstructorsMethods {
    public String run() {
        try {
            final java.lang.reflect.Constructor constructor = ReflectiveGreeter.class.getDeclaredConstructor(String.class);
            final java.lang.Object target = constructor.newInstance("Affogato");
            final java.lang.reflect.Method method = ReflectiveGreeter.class.getMethod("greet", String.class);
            final java.lang.Object result = method.invoke(target, "Hello");
            return result.toString();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

}
