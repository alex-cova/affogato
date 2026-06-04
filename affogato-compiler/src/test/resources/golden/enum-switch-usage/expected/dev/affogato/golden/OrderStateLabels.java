package dev.affogato.golden;

public class OrderStateLabels {
    public String label(OrderState state) {
        if (state == OrderState.NEW) {
            return "new";
        } else if (state == OrderState.PAID) {
    return "paid";
} else {
    return "done";
}
    }

    public void print(OrderState state) {
        System.out.println(state.toString());
    }

}
