package com.aicodehelper.agent;

/** Typed adapter failure. Never retain a raw provider exception message or response body. */
public class ToolFailureException extends RuntimeException {
    private final ToolError.Code code;
    public ToolFailureException(ToolError.Code code) {
        super(ToolError.of(code).message());
        this.code = code;
    }
    public ToolError.Code code() { return code; }
}
