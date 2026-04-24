package com.localcloud.emulators.workflows.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {

    @Test
    void cancelAndInterrupt_setsStateToCancelled() {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setExecutingThread(Thread.currentThread());
        ctx.cancelAndInterrupt();
        assertTrue(ctx.isCancelled());
        assertEquals("CANCELLED", ctx.getState());
        Thread.interrupted(); // clear flag
    }

    @Test
    void cancelAndInterrupt_interruptsThread() {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setExecutingThread(Thread.currentThread());
        ctx.cancelAndInterrupt();
        assertTrue(Thread.interrupted(), "Thread should be interrupted");
    }

    @Test
    void cancelAndInterrupt_withNullThread_doesNotThrow() {
        ExecutionContext ctx = new ExecutionContext();
        assertDoesNotThrow(() -> ctx.cancelAndInterrupt());
        assertTrue(ctx.isCancelled());
    }

    @Test
    void executingThread_getterAndSetter() {
        ExecutionContext ctx = new ExecutionContext();
        assertNull(ctx.getExecutingThread());
        ctx.setExecutingThread(Thread.currentThread());
        assertEquals(Thread.currentThread(), ctx.getExecutingThread());
    }

    @Test
    void isCancelled_childSeesParentCancellation() {
        ExecutionContext parent = new ExecutionContext();
        ExecutionContext child = parent.createChildContext(null);
        assertFalse(child.isCancelled());
        parent.setState("CANCELLED");
        assertTrue(child.isCancelled(), "Child should see parent cancellation");
    }
}
