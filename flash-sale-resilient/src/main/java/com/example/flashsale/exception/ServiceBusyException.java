package com.example.flashsale.exception;

/** Nem ra khi da cham tran Rate Limiter va khong co ban sao nao o cache cuc bo de phuc vu. */
public class ServiceBusyException extends RuntimeException {

    public ServiceBusyException(Long productId) {
        super("He thong dang qua tai, tam thoi khong phuc vu duoc san pham id = " + productId);
    }
}
