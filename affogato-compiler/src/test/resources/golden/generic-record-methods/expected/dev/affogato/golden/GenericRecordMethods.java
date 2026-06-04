package dev.affogato.golden;

public class GenericRecordMethods {
    public String run() {
        final GenericPair<String,Integer> pair = new GenericPair<String, Integer>("shots", 2);
        final String first = pair.firstValue();
        final Integer second = pair.secondValue();
        return first + ":" + second;
    }

}
