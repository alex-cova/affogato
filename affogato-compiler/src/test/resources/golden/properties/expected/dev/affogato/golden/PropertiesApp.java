package dev.affogato.golden;

public class PropertiesApp {
    public String run() {
        final Account account = new Account("Ada", 7);
        account.setBalance(account.deposit(5));
        return account.getOwner() + account.getId() + account.getBalance();
    }

}
