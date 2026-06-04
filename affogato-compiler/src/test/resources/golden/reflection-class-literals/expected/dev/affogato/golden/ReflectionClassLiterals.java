package dev.affogato.golden;

public class ReflectionClassLiterals {
    public String run() {
        final java.lang.Class stringType = String.class;
        final java.lang.Class widgetType = ReflectionWidget.class;
        final java.lang.Class primitiveType = int.class;
        final java.lang.String stringName = stringType.getName();
        final java.lang.String widgetName = widgetType.getSimpleName();
        final java.lang.String primitiveName = primitiveType.getName();
        return stringName + ":" + widgetName + ":" + primitiveName;
    }

}
