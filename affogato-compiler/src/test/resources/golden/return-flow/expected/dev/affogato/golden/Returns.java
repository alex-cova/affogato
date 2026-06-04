package dev.affogato.golden;

public class Returns {
    public String branch(int value) {
        if (value > 0) {
            return "positive";
        } else if (value == 0) {
    return "zero";
} else {
    return "negative";
}
    }

    public String fromTry(String value) {
        try {
            return Integer.parseInt(value) + "";
        } catch (NumberFormatException e) {
            return "nan";
        }
    }

    public String fromBlock() {
        {
            return "block";
        }
    }

}
