package com.example.flashsale.model;

public class Product {

    private final Long id;
    private final String name;
    private final long price;
    private final int stock;
    private final boolean flashSale;

    public Product(Long id, String name, long price, int stock, boolean flashSale) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.flashSale = flashSale;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public boolean isFlashSale() {
        return flashSale;
    }

    public Product copy() {
        return new Product(id, name, price, stock, flashSale);
    }
}
