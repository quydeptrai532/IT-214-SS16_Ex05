package com.example.flashsale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class FlashSaleResilientApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSaleResilientApplication.class, args);
    }
}
