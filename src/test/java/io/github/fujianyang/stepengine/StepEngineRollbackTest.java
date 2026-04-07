package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StepEngineRollbackTest {

    @Test
    void shouldRollbackCompletedStepsInReverseOrderOnServiceException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("rollback-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> ctx.events.add("rollback-step-2"))
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new InvalidRequestException("INVALID", "bad input");
            })
            .retryPolicy(new NoRetryPolicy())
            .build();

        InvalidRequestException exception = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertEquals("bad input", exception.getMessage());
        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "rollback-step-2",
                "rollback-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldRollbackCompletedStepsInReverseOrderOnUnexpectedException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("rollback-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> ctx.events.add("rollback-step-2"))
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new IOException("downstream failure");
            })
            .retryPolicy(new NoRetryPolicy())
            .build();

        assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "rollback-step-2",
                "rollback-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldSkipStepsWithoutRollbackHandler() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("rollback-step-1"))
            .step("step-2", ctx -> ctx.events.add("execute-step-2"))
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new IOException("fail");
            })
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "rollback-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldAttachRollbackFailureAsSuppressedExceptionToServiceException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new InvalidRequestException("INVALID", "bad input");
            })
            .build();

        InvalidRequestException exception = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
    }

    @Test
    void shouldAttachRollbackFailureAsSuppressedExceptionToWorkflowException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("downstream fail");
            })
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
        assertEquals(
            "rollback failed",
            exception.getSuppressed()[0].getMessage()
        );
    }

    @Test
    void shouldPreserveOriginalServiceExceptionWhenRollbackFails() {
        TestContext context = new TestContext();
        InvalidRequestException expected = new InvalidRequestException("INVALID", "bad input");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw expected;
            })
            .build();

        InvalidRequestException actual = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertSame(expected, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, actual.getSuppressed()[0]);
    }

    @Test
    void shouldPreserveOriginalUnexpectedExceptionWhenRollbackFails() {
        TestContext context = new TestContext();
        IOException expected = new IOException("downstream fail");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw expected;
            })
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertSame(expected, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
    }

    private static final class TestContext {
        private final List<String> events = new ArrayList<>();
    }

    private static final class InvalidRequestException extends ServiceException {
        private InvalidRequestException(String errorCode, String message) {
            super(errorCode, message);
        }
    }
}