package dev.affogato.golden;

public class LoopBreakContinue {
    public int sumOddsUntilLimit(int[] values) {
        int total = 0;
        for (var value : values) {
            if (value % 2 == 0) {
                continue;
            }
            if (value > 9) {
                break;
            }
            total = total + value;
        }
        return total;
    }

    public int countUntil(int stop) {
        int index = 0;
        while (true) {
            if (index >= stop) {
                break;
            }
            index = index + 1;
        }
        return index;
    }

}
