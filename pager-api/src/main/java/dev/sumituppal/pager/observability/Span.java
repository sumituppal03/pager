package dev.sumituppal.pager.observability;

/**
 * AutoCloseable wrapper that emits {@code span.start} on construction
 * and {@code span.end} on close, capturing the wall-clock duration in
 * between. Optionally emits {@code error} if the enclosed block threw.
 *
 * <h2>Intended usage</h2>
 * <pre>{@code
 *   try (Span span = Span.open(emitter, ctx)) {
 *       // ... work that should be traced ...
 *       span.setOutcome("completed");
 *   } // span.end emitted here with duration
 * }</pre>
 *
 * <p>If the try-with-resources block throws, {@link #close()} emits an
 * error event with the exception's message before emitting span.end.
 * The exception then propagates normally.
 *
 * <h2>Why AutoCloseable instead of a callback lambda?</h2>
 * <p>try-with-resources is the idiomatic Java pattern for bounded
 * lifetimes. Reviewers see {@code try (Span span = ...)} and instantly
 * understand the scope. A callback ({@code span.wrap(() -> work())})
 * would work but reads worse and interacts awkwardly with checked
 * exceptions in the wrapped work.
 *
 * <h2>Not thread-safe</h2>
 * <p>One thread owns one Span. The parallel-specialist PR (later) will
 * create separate Spans on each worker thread — each with a shared
 * parent context — rather than trying to share one Span across threads.
 */
public final class Span implements AutoCloseable {

    private final AgentEventEmitter emitter;
    private final SpanContext ctx;
    private final long startedNanos;

    private String outcome = "completed";
    private boolean errored = false;
    private String errorMessage;

    private Span(AgentEventEmitter emitter, SpanContext ctx) {
        this.emitter = emitter;
        this.ctx = ctx;
        this.startedNanos = System.nanoTime();
        emitter.spanStart(ctx);
    }

    /**
     * Open a new span and emit its {@code span.start} event.
     */
    public static Span open(AgentEventEmitter emitter, SpanContext ctx) {
        return new Span(emitter, ctx);
    }

    /**
     * Access this span's context — useful for creating child spans.
     */
    public SpanContext context() {
        return ctx;
    }

    /**
     * Override the default outcome ("completed") before close.
     */
    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    /**
     * Record that this span failed. The exception message will be
     * emitted as a separate error event; span.end will still fire.
     */
    public void recordError(Throwable t) {
        this.errored = true;
        this.errorMessage = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        this.outcome = "error";
    }

    @Override
    public void close() {
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (errored) {
            emitter.error(ctx, errorMessage);
        }
        emitter.spanEnd(ctx, latencyMs, outcome);
    }
}