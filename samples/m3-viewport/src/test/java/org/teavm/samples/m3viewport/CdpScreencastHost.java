/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.WebSocket;
import java.util.Iterator;
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
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * JVM-side Chromium CDP WebSocket listener for full-image screencast events.
 * This listener never creates a browser, opens a socket, handles credentials,
 * executes script or touches SWT/TeaVM browser DOM. The embedding host MUST
 * supply an authenticated/origin-restricted browser debugging connection.
 * This class must never be compiled into the TeaVM browser target.
 *
 * <p>Network callbacks enqueue at most two texts for an image-header worker.
 * The worker validates compressed image dimensions, then admits one latest
 * image to a bounded {@link RemoteFrameInbox}. Acknowledge CDP after the
 * mailbox admission (or stale discard), not after UI paint. UI painting is
 * explicitly signaled by the owning renderer through the inbox.</p>
 */
public final class CdpScreencastHost implements WebSocket.Listener, AutoCloseable {
    private final RemoteFrameInbox inbox;
    private final long epoch;
    private final int cssWidth;
    private final int cssHeight;
    private final Runnable onFrameAvailable;
    private final Consumer<Throwable> onFailure;
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "M3-CDP-image-header");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong commandId = new AtomicLong();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final StringBuilder fragments = new StringBuilder();
    private CompletableFuture<Void> sending = CompletableFuture.completedFuture(null);
    private volatile WebSocket socket;
    private volatile boolean closed;

    public CdpScreencastHost(RemoteFrameInbox inbox, long epoch, int cssWidth, int cssHeight,
            Runnable onFrameAvailable, Consumer<Throwable> onFailure) {
        this.inbox = Objects.requireNonNull(inbox);
        if (epoch < 0 || cssWidth < 1 || cssHeight < 1 || cssWidth > FrameWindow.MAX_AXIS
                || cssHeight > FrameWindow.MAX_AXIS) throw new IllegalArgumentException("invalid capture session");
        this.epoch = epoch;
        this.cssWidth = cssWidth;
        this.cssHeight = cssHeight;
        this.onFrameAvailable = Objects.requireNonNull(onFrameAvailable);
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    /** Called by Java HttpClient only after the application connects to an approved endpoint. */
    @Override
    public void onOpen(WebSocket webSocket) {
        socket = Objects.requireNonNull(webSocket);
        webSocket.request(1);
    }

    /** Explicit start: opening a debugger connection alone never starts a capture. */
    public void start(int maxWidth, int maxHeight, int jpegQuality) {
        send(CdpScreencastCommands.start(nextCommandId(), maxWidth, maxHeight, jpegQuality));
    }

    public void stop() {
        if (!closed) send(CdpScreencastCommands.stop(nextCommandId()));
    }

    /**
     * May be called only following a user- and host-authorized interaction.
     * Frame/session/pixel bounds are checked by the shared inbox, which starts
     * without remote input permission, and rejects stale or letterbox clicks.
     */
    public boolean pointer(long expectedRevision, int viewX, int viewY,
            String type, String button, int buttons) {
        RemoteFrameInbox.CssPoint point = inbox.pointer(epoch, expectedRevision, viewX, viewY);
        if (point == null || closed) return false;
        send(CdpScreencastCommands.pointer(nextCommandId(), point, type, button, buttons));
        return true;
    }

    @Override
    public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (closed) return CompletableFuture.completedFuture(null);
        if ((long) fragments.length() + data.length() > CdpFrameJson.MAX_TEXT) {
            fail(new IOException("oversized CDP WebSocket text message"));
            return CompletableFuture.completedFuture(null);
        }
        fragments.append(data);
        if (last) {
            String message = fragments.toString();
            fragments.setLength(0);
            try { decoder.execute(() -> receive(message)); }
            catch (RejectedExecutionException e) { fail(new IOException("CDP decode queue saturated", e)); }
        }
        if (!closed) webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        close();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        fail(error);
    }

    private void receive(String json) {
        if (closed) return;
        try {
            CdpFrameJson.Event event = CdpFrameJson.frame(json);
            if (event == null) return;
            byte[] frame = event.image();
            int[] size = imageSize(frame);
            long nextRevision = revision.incrementAndGet();
            RemoteFrameInbox.Reception r = inbox.receive(epoch, nextRevision,
                    event.acknowledgementId(), event.format(), size[0], size[1],
                    cssWidth, cssHeight, frame);
            // CDP's ackId is independent of M3 revision and must not be renumbered.
            send(CdpScreencastCommands.acknowledge(nextCommandId(), r.ackId()));
            if (r.accepted()) onFrameAvailable.run();
        } catch (RuntimeException | IOException error) {
            fail(error);
        }
    }

    private static int[] imageSize(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new IOException("no ImageIO input");
            Iterator<ImageReader> decoders = ImageIO.getImageReaders(input);
            if (!decoders.hasNext()) throw new IOException("unsupported screenshot image");
            ImageReader reader = decoders.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > RemoteFrameInbox.MAX_AXIS
                        || height > RemoteFrameInbox.MAX_AXIS
                        || (long) width * height > RemoteFrameInbox.MAX_PIXELS) {
                    throw new IOException("remote screenshot pixel limit exceeded");
                }
                return new int[] {width, height};
            } finally { reader.dispose(); }
        }
    }

    private long nextCommandId() {
        long next = commandId.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("CDP command ID exhausted");
        return next;
    }

    /** Serialized outbound commands: java.net.http.WebSocket permits no interleaved text sends. */
    private synchronized void send(String json) {
        WebSocket webSocket = socket;
        if (closed || webSocket == null) throw new IllegalStateException("CDP connection not open");
        sending = sending.thenCompose(ignored -> webSocket.sendText(json, true).thenApply(sent -> null));
        sending.whenComplete((ignored, failure) -> { if (failure != null) fail(failure); });
    }

    private void fail(Throwable reason) {
        if (failed.compareAndSet(false, true)) {
            close();
            onFailure.accept(reason);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        fragments.setLength(0);
        decoder.shutdownNow();
        inbox.close();
        WebSocket current = socket;
        socket = null;
        if (current != null) current.abort();
    }
}
