package com.example.flashsale.dto;

import com.example.flashsale.model.Product;

public record FlashSaleProductDTO(Long productId, String name, long price, int stock, boolean flashSale) {

    public static FlashSaleProductDTO from(Product product) {
        return new FlashSaleProductDTO(product.getId(), product.getName(), product.getPrice(),
                product.getStock(), product.isFlashSale());
    }
}
