package dev.affogato.golden;

public class Flow {
    public String classify(int value) {
        if (value > 0) {
            return "positive";
        } else if (value == 0) {
    return "zero";
} else {
    return "negative";
}
    }

    public void loop() {
        java.util.ArrayList<String> names = new java.util.ArrayList<String>();
        names.add("a");
        for (var name : names) {
            System.out.println(name);
        }
        int counter = 0;
        while (counter < 3) {
            counter = counter + 1;
        }
        System.out.println(counter);
    }

    public String pick(boolean flag) {
        return flag ? "yes" : "no";
    }

    public void check(Object value) {
        if (!(value instanceof String)) {
            return;
        }
        System.out.println(((String) value));
    }

}
