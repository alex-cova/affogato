package dev.affogato.golden;

public class CStyleFor {
    public int sumToN(int n) {
        int total = 0;
        for (var i = 0; i < n; i++) {
            total = total + i;
        }
        return total;
    }

    public int countdown(int n) {
        int result = 0;
        for (int i = n; i > 0; i--) {
            result = result + i;
        }
        return result;
    }

}
