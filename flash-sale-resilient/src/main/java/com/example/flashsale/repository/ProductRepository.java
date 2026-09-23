package com.example.flashsale.repository;

import com.example.flashsale.model.Product;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findById(Long id);

    List<Product> findAllFlashSale();

    long queryCount();

    long flashSaleSize();

    void setQueryDelay(Duration delay);

    void reset();
}
