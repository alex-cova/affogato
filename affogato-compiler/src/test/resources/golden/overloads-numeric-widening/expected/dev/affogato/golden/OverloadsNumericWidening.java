package dev.affogato.golden;

public class OverloadsNumericWidening {
    public String run() {
        final String longValue = NumericOverloads.widen(7);
        final String doubleValue = NumericOverloads.widen(1.5f);
        final String boxedValue = NumericOverloads.boxed(8);
        final String unboxedValue = NumericOverloads.unboxed(Integer.valueOf(9));
        return longValue + ":" + doubleValue + ":" + boxedValue + ":" + unboxedValue;
    }

}
