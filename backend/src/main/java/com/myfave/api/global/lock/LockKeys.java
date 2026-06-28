package com.myfave.api.global.lock;

import java.util.List;

public final class LockKeys {

    private static final String PRODUCT_STOCK_PREFIX = "lock:product:stock:";

    private LockKeys() {}

    public static String productStock(Long productId) {
        return PRODUCT_STOCK_PREFIX + productId;
    }

    public static List<String> productStocks(List<Long> productIds) {
        return productIds.stream()
                .map(LockKeys::productStock)
                .toList();
    }
}
