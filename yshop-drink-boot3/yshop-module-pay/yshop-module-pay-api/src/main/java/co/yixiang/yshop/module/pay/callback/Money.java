package co.yixiang.yshop.module.pay.callback;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact CNY conversion; never rounds a payment amount. */
public final class Money {
    private Money() {}

    public static long cents(BigDecimal yuan) {
        if (yuan == null || yuan.signum() <= 0)
            throw new IllegalArgumentException("INVALID_AMOUNT");
        return yuan.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    public static long positiveCents(String value) {
        if (value == null || !value.matches("[0-9]{1,19}"))
            throw new IllegalArgumentException("INVALID_AMOUNT");
        long cents = Long.parseLong(value);
        if (cents <= 0) throw new IllegalArgumentException("INVALID_AMOUNT");
        return cents;
    }
}
