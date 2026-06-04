package dev.affogato.golden;

public class Account {
    private String owner;
    private final int id;
    public int balance = 0;

    public Account(String owner, int id) {
        this.owner = owner;
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String value) {
        this.owner = value;
    }

    public int getId() {
        return id;
    }

    public int getBalance() {
        return balance;
    }

    public void setBalance(int value) {
        this.balance = value;
    }

    public int deposit(int amount) {
        balance = balance + amount;
        return balance;
    }

}
