package dev.affogato.golden;

import dev.affogato.golden.interop.JavaWildcardNumbers;
import java.util.List;

public class JavaWildcardsExtends {
    public String run() {
        final java.util.List<java.lang.Integer> numbers = List.of(Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3));
        final java.lang.Number total = JavaWildcardNumbers.sum(numbers);
        return total.toString();
    }

}
