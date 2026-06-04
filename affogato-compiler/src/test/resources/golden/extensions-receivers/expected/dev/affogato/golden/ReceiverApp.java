package dev.affogato.golden;

public class ReceiverApp {
    public String run() {
        final ReceiverPerson person = new ReceiverPerson("Ada", 42);
        final String greeting = dev.affogato.golden.ReceiversExtensions.greeting(person, "Hi");
        final String described = dev.affogato.golden.ReceiversExtensions.describe(person);
        final int length = dev.affogato.golden.ReceiversExtensions.lengthPlus("abc");
        return greeting + described + length;
    }

}
