/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.io.IOException;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JVM-only, explicitly driven WebDriver BiDi screenshot adapter using the
 * existing bounded {@link RemoteFrameInbox}. It does not open a socket or
 * discover a Firefox debugging endpoint. The application supplies a connected,
 * authenticated/origin-restricted WebSocket and a browser-minted context ID.
 *
 * <p>At most one screenshot request is in flight. Each successful BiDi reply
 * is a complete PNG/JPEG snapshot. No browser input is forwarded, no CDP ACK
 * is generated, and a frame is never marked displayed by this host.</p>
 */
public final class BidiScreenshotHost implements WebSocket.Listener, AutoCloseable {
    private final RemoteFrameInbox inbox;
    private final long epoch;
    private final int cssWidth;
    private final int cssHeight;
    private final String context;
    private final RemoteFrameInbox.Format format;
    private final int quality;
    private final Runnable onFrameAvailable;
    private final Consumer<Throwable> onFailure;
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "M3-BiDi-image-header");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong commandId = new AtomicLong();
    private final AtomicLong pendingRequest = new AtomicLong();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final StringBuilder fragments = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean closed;

    public BidiScreenshotHost(RemoteFrameInbox inbox, long epoch, String context,
            int cssWidth, int cssHeight, RemoteFrameInbox.Format format, int quality,
            Runnable onFrameAvailable, Consumer<Throwable> onFailure) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        // Validate the context and capture policy once, before any connection.
        BidiScreenshotCommands.capture(1, context, format, quality);
        if (epoch < 0 || cssWidth < 1 || cssHeight < 1 || cssWidth > FrameWindow.MAX_AXIS
                || cssHeight > FrameWindow.MAX_AXIS) {
            throw new IllegalArgumentException("invalid BiDi viewport envelope");
        }
        this.epoch = epoch;
        this.context = context;
        this.cssWidth = cssWidth;
        this.cssHeight = cssHeight;
        this.format = format;
        this.quality = quality;
        this.onFrameAvailable = Objects.requireNonNull(onFrameAvailable, "onFrameAvailable");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
    }

    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        Objects.requireNonNull(webSocket, "webSocket");
        // A listener is bound to exactly one approved connection. A second
        // socket must not replace the session or supply a forged frame.
        if (closed || socket != null) { webSocket.abort(); return; }
        socket = webSocket;
        webSocket.request(1);
    }

    /** An explicit, nonblocking request. False means one capture is still in flight. */
    public boolean capture() {
        WebSocket ws = socket;
        if (ws == null || closed) throw new IllegalStateException("BiDi connection is not open");
        if (pendingRequest.get() != 0) return false;
        long id = commandId.incrementAndGet();
        if (id <= 0) throw new IllegalStateException("BiDi command IDs exhausted");
        if (!pendingRequest.compareAndSet(0, id)) return false;
        String request = BidiScreenshotCommands.capture(id, context, format, quality);
        try {
            ws.sendText(request, true).whenComplete((ignored, error) -> {
                if (error != null) fail(new IOException("BiDi screenshot send failed", error));
            });
        } catch (RuntimeException error) {
            fail(error);
            throw error;
        }
        return true;
    }

    @Override
    public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (closed) return CompletableFuture.completedFuture(null);
        if (webSocket != socket) {
            // Ignore callbacks from a superseded/foreign WebSocket. Neither its
            // frames nor its close/error callbacks own this host's authority.
            if (webSocket != null) webSocket.abort();
            return CompletableFuture.completedFuture(null);
        }
        if ((long) fragments.length() + data.length() > CdpFrameJson.MAX_TEXT) {
            fail(new IOException("oversized BiDi WebSocket message"));
            return CompletableFuture.completedFuture(null);
        }
        fragments.append(data);
        if (last) {
            String message = fragments.toString();
            fragments.setLength(0);
            try { decoder.execute(() -> receive(message)); }
            catch (RejectedExecutionException e) { fail(new IOException("BiDi decode queue saturated", e)); }
        }
        if (!closed) webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    private void receive(String text) {
        if (closed) return;
        try {
            Map<?, ?> root = CdpFrameJson.object(text);
            Object rawId = root.get("id");
            if (rawId == null) return; // unrelated asynchronous event
            if (!(rawId instanceof Long id) || id < 1) {
                throw new IOException("invalid BiDi command response ID");
            }
            if (id != pendingRequest.get()) return; // unrelated or stale command
            if ("error".equals(root.get("type"))) {
                throw new IOException("BiDi screenshot command rejected");
            }
            if (!"success".equals(root.get("type")) || !(root.get("result") instanceof Map<?, ?> result)) {
                throw new IOException("invalid BiDi screenshot reply");
            }
            if (!(result.get("data") instanceof String base64) || base64.isEmpty()
                    || (long) base64.length() > (long) RemoteFrameInbox.MAX_COMPRESSED_BYTES * 4 / 3 + 8) {
                throw new IOException("BiDi screenshot data outside compressed budget");
            }
            byte[] image;
            try { image = Base64.getDecoder().decode(base64); }
            catch (IllegalArgumentException e) { throw new IOException("invalid BiDi screenshot base64", e); }
            // The existing inbox validates image signature/format when admitting.
            int[] dimensions = CdpScreencastHost.imageSize(image);
            if (closed || pendingRequest.get() != id) return;
            long next = revision.incrementAndGet();
            // WebDriver BiDi has no Page.screencastFrameAck; zero is a local placeholder.
            RemoteFrameInbox.Reception r = inbox.receive(epoch, next, 0, format,
                    dimensions[0], dimensions[1], cssWidth, cssHeight, image);
            pendingRequest.compareAndSet(id, 0);
            if (r.accepted() && !closed) onFrameAvailable.run();
        } catch (IOException | RuntimeException error) {
            fail(error);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (webSocket == socket) close();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket == socket && !closed) fail(error);
    }

    private void fail(Throwable error) {
        if (failed.compareAndSet(false, true)) {
            close();
            onFailure.accept(error);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        pendingRequest.set(0);
        fragments.setLength(0);
        decoder.shutdownNow();
        inbox.close();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.abort();
    }
}
