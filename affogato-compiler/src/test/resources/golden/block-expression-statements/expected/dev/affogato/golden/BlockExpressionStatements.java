package dev.affogato.golden;

public class BlockExpressionStatements {
    public int run() {
        final BlockExpressionCounter counter = new BlockExpressionCounter(0);
        {
            counter.inc();
            {
                counter.setCount(counter.getCount() + 2);
                System.out.println(counter.getCount());
            }
        }
        return counter.getCount();
    }

}
