/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.util.Arrays;

/**
 * Transport-neutral, bounded mailbox for full-image Chromium/Firefox browser frames.
 * The producer may run off the UI thread. Only the consuming renderer owns images,
 * controls, DOM nodes and GPU/JNI resources; this class owns none of those objects.
 *
 * <p>This is NOT an RFB decoder or a CDP WebSocket implementation. A trusted host
 * parses its chosen protocol and validates browser origin, transport authentication
 * and session ownership before calling {@link #receive}. The CDP screencast path
 * publishes a complete PNG/JPEG image for each event, not pixel-damage rectangles.
 * One latest frame is retained. Acknowledging a frame means that its event was
 * safely consumed by the host, not that it was painted on screen.</p>
 */
public final class RemoteFrameInbox {
    public static final int MAX_COMPRESSED_BYTES = 8 * 1024 * 1024;
    public static final int MAX_AXIS = 8192;
    public static final long MAX_PIXELS = 16_777_216L;

    public enum Format { JPEG, PNG }

    /** The event's source is already trusted; payload bytes are copied on admission. */
    public static final class Frame {
        private final long epoch;
        private final long revision;
        private final int acknowledgementId;
        private final int pixelWidth;
        private final int pixelHeight;
        private final int cssWidth;
        private final int cssHeight;
        private final Format format;
        private final byte[] compressed;

        private Frame(long epoch, long revision, int acknowledgementId, Format format,
                int pixelWidth, int pixelHeight, int cssWidth, int cssHeight, byte[] compressed) {
            this.epoch = epoch;
            this.revision = revision;
            this.acknowledgementId = acknowledgementId;
            this.format = format;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.cssWidth = cssWidth;
            this.cssHeight = cssHeight;
            this.compressed = compressed.clone();
        }

        public long epoch() { return epoch; }
        public long revision() { return revision; }
        public int acknowledgementId() { return acknowledgementId; }
        public Format format() { return format; }
        public int pixelWidth() { return pixelWidth; }
        public int pixelHeight() { return pixelHeight; }
        public int cssWidth() { return cssWidth; }
        public int cssHeight() { return cssHeight; }
        public byte[] compressed() { return compressed.clone(); }
        public int byteCount() { return compressed.length; }
    }

    /** Offer decision; ACK every well-formed protocol frame, including stale ones. */
    public record Reception(boolean accepted, int ackId, long discardedPending) { }

    /** UI-thread publication request; it does not itself assert pixels were painted. */
    public record Delivery(Frame frame, FrameWindow.Plan geometry) { }

    /** Main-frame CSS pixel coordinates, not image/device pixels. */
    public record CssPoint(int x, int y) { }

    private Frame pending;
    private Frame latestScheduled;
    private long epoch = -1;
    private long maxRevision = -1;
    private long discarded;
    private FrameWindow geometry = new FrameWindow();
    private boolean inputAuthorized;
    private long displayedEpoch = -1;
    private long displayedRevision = -1;
    private boolean closed;

    /** Explicit host-owned input authorization; defaults to false on every session. */
    public synchronized void setInputAuthorized(boolean authorized) {
        requireOpen();
        inputAuthorized = authorized;
    }

    /**
     * Accept one decoded compressed image and its independently established CSS viewport.
     * The host must send CDP Page.screencastFrameAck with the returned ID after this call;
     * unlike UI painting, CDP acknowledgement cannot be blocked on a hidden SWT panel.
     * If a newer frame arrives before presentation, the old mailbox entry is dropped.
     */
    public synchronized Reception receive(long nextEpoch, long revision, int ackId, Format format,
            int imageWidth, int imageHeight, int cssWidth, int cssHeight, byte[] bytes) {
        requireOpen();
        validate(nextEpoch, revision, ackId, format, imageWidth, imageHeight, cssWidth, cssHeight, bytes);
        if (nextEpoch < epoch || nextEpoch == epoch && revision <= maxRevision) {
            return new Reception(false, ackId, discarded);
        }
        Frame staged = new Frame(nextEpoch, revision, ackId, format, imageWidth, imageHeight,
                cssWidth, cssHeight, bytes);
        if (nextEpoch != epoch) {
            // Never allow a session switch to reuse focus/input permission or geometry.
            pending = null;
            latestScheduled = null;
            geometry = new FrameWindow();
            inputAuthorized = false;
            displayedEpoch = displayedRevision = -1;
        }
        if (pending != null) discarded++;
        pending = staged;
        epoch = nextEpoch;
        maxRevision = revision;
        return new Reception(true, ackId, discarded);
    }

    /** Bounded latest-wins transfer to the presentation thread. */
    public synchronized Delivery take(int viewWidth, int viewHeight) {
        requireOpen();
        if (pending == null) return null;
        // Preserve the pending entry if viewport geometry is invalid.
        Frame f = pending;
        FrameWindow.Plan plan = geometry.publish(f.epoch(), f.revision(),
                f.pixelWidth(), f.pixelHeight(), viewWidth, viewHeight, null);
        if (plan == null) return null;
        pending = null;
        latestScheduled = f;
        return new Delivery(f, plan);
    }

    /**
     * Call only after the exact scheduled frame has been decoded and presented.
     * A stale/overwritten frame can never re-enable remote input.
     */
    public synchronized boolean displayed(Delivery delivery) {
        requireOpen();
        if (delivery == null || latestScheduled == null) return false;
        Frame frame = delivery.frame();
        if (frame != latestScheduled || frame.epoch() != epoch || frame.revision() != maxRevision) {
            return false;
        }
        displayedEpoch = frame.epoch();
        displayedRevision = frame.revision();
        return true;
    }

    /** Never generate an input coordinate from a stale or unpainted surface. */
    public synchronized CssPoint pointer(long expectedEpoch, long expectedRevision, int x, int y) {
        if (closed || !inputAuthorized || epoch != expectedEpoch || maxRevision != expectedRevision
                || displayedEpoch != expectedEpoch || displayedRevision != expectedRevision) {
            return null;
        }
        Frame frame = latestScheduled;
        if (frame == null || frame.epoch() != expectedEpoch || frame.revision() != expectedRevision) {
            return null;
        }
        int[] source = geometry.pointer(expectedEpoch, expectedRevision, x, y);
        if (source == null) return null;
        return new CssPoint((int) ((long) source[0] * frame.cssWidth() / frame.pixelWidth()),
                (int) ((long) source[1] * frame.cssHeight() / frame.pixelHeight()));
    }

    public synchronized long discardedCount() { return discarded; }
    public synchronized boolean hasPending() { return pending != null; }
    public synchronized int pendingBytes() { return pending == null ? 0 : pending.byteCount(); }

    /** Tear down publication and authorization; safe to call repeatedly. */
    public synchronized void close() {
        closed = true;
        pending = null;
        latestScheduled = null;
        displayedEpoch = displayedRevision = -1;
        inputAuthorized = false;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("frame inbox is closed");
    }

    private static void validate(long epoch, long revision, int ack, Format format,
            int width, int height, int cssWidth, int cssHeight, byte[] bytes) {
        if (epoch < 0 || revision < 0 || ack < 0 || format == null
                || width < 1 || height < 1 || width > MAX_AXIS || height > MAX_AXIS
                || (long) width * height > MAX_PIXELS
                || cssWidth < 1 || cssHeight < 1 || cssWidth > FrameWindow.MAX_AXIS
                || cssHeight > FrameWindow.MAX_AXIS || bytes == null
                || bytes.length < 4 || bytes.length > MAX_COMPRESSED_BYTES) {
            throw new IllegalArgumentException("invalid remote browser frame envelope");
        }
        if (format == Format.PNG) {
            final byte[] signature = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
            if (bytes.length < signature.length || !Arrays.equals(Arrays.copyOf(bytes, 8), signature)) {
                throw new IllegalArgumentException("invalid PNG signature");
            }
        } else if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216
                || (bytes[bytes.length - 2] & 255) != 255 || (bytes[bytes.length - 1] & 255) != 217) {
            throw new IllegalArgumentException("invalid JPEG markers");
        }
        // Decoder must still independently validate the actual decoded image size and pixel budget.
    }
}
