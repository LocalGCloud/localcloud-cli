package com.localcloud.emulators.workflows.engine;

/**
 * Thrown by ReturnStep to unwind the call stack with a return value.
 * NOT a real error — it's a control flow mechanism.
 */
public class ReturnException extends RuntimeException {
    private final Object value;

    public ReturnException(Object value) {
        super("return");
        this.value = value;
    }

    public Object getValue() { return value; }
}
