package com.example.flashsale.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long productId) {
        super("Khong tim thay san pham id = " + productId);
    }
}
