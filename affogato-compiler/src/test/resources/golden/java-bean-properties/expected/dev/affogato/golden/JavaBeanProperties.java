package dev.affogato.golden;

import dev.affogato.golden.interop.JavaBeanBox;

public class JavaBeanProperties {
    public String run() {
        final JavaBeanBox bean = new JavaBeanBox();
        bean.setName("affogato");
        bean.setActive(true);
        return bean.getName() + ":" + bean.isActive();
    }

}
