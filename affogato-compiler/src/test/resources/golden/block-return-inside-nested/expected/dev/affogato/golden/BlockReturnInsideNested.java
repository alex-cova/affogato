package dev.affogato.golden;

public class BlockReturnInsideNested {
    public String fromIf(int value) {
        {
            if (value > 0) {
                return "if";
            }
        }
        return "after-if";
    }

    public String fromWhile() {
        while (true) {
            {
                return "while";
            }
        }
    }

    public String fromTry(String value) {
        try {
            {
                return Integer.parseInt(value) + "";
            }
        } catch (NumberFormatException e) {
            {
                return "catch";
            }
        } finally {
            System.out.println("finally");
        }
    }

}
