package com.ocgp.server;

public class HttpStatusException extends RuntimeException {
    private final int status;

    public HttpStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
