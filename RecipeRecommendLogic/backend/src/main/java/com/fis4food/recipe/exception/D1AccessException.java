package com.fis4food.recipe.exception;

public class D1AccessException extends RuntimeException {
    private final boolean timeout;

    public D1AccessException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }


    public boolean isTimeout() {

        return timeout;
    }
}
