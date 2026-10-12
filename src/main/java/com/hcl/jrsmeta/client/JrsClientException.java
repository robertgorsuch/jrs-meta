package com.hcl.jrsmeta.client;

import java.io.IOException;

public class JrsClientException extends IOException {
    private final int status;
    public JrsClientException(int status, String message) { super(message); this.status = status; }
    public JrsClientException(int status, String message, Throwable cause) { super(message, cause); this.status = status; }
    public int getStatus() { return status; }
}
