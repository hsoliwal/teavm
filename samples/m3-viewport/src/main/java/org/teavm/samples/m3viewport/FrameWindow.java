/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2026 Contributors to the M3 TeaVM viewport sample.
 */
package org.teavm.samples.m3viewport;

import java.util.Arrays;

/**
 * A small, display-free receiver for remote-browser frames. It is intentionally usable
 * on the JVM, JavaScript and Wasm GC without SWT, JNI, sockets or filesystem access.
 * Remote transport/authentication and the browser renderer are separate, host-owned
 * capabilities. One owner calls {@link #publish} serially and hands the returned
 * immutable plan to its renderer on the proper UI thread.
 *
 * <p>Dirty rectangles are frame-local [x,y,width,height] tuples. They are
 * conservatively projected (floor left/top, ceil right/bottom) into a letterboxed
 * viewport, merged with bounded allocation, and promoted to a full redraw when
 * too many or too large. The published sequence is monotonically increasing per
 * session. Clients must not render or send input for a stale session/revision.</p>
 */
public final class FrameWindow {
    public static final int MAX_AXIS = 32_768;
    public static final int MAX_INPUT_RECTS = 4_096;
    public static final int MAX_RECTS = 64;

    /** Geometric value, not an SWT Rectangle or native resource. */
    public record Rect(int x, int y, int width, int height) {
    }

    /** Immutable publication. Only flat damage lanes are copied across boundaries. */
    public static final class Plan {
        private final long session;
        private final long revision;
        private final Rect content;
        private final boolean full;
        private final int[] damage;

        private Plan(long session, long revision, Rect content, boolean full, int[] damage) {
            this.session = session;
            this.revision = revision;
            this.content = content;
            this.full = full;
            this.damage = damage.clone();
        }

        public long session() {
            return session;
        }

        public long revision() {
            return revision;
        }

        public Rect content() {
            return content;
        }

        public boolean full() {
            return full;
        }

        /** Returns a copy: callers cannot mutate the next paint's rectangles. */
        public int[] damage() {
            return damage.clone();
        }

        public int rectangleCount() {
            return damage.length / 4;
        }
    }

    private long session = -1;
    private long revision = -1;
    private int sourceWidth;
    private int sourceHeight;
    private int viewWidth;
    private int viewHeight;
    private Rect content;

    /**
     * Atomically validates and receives a frame publication. A null return is a
     * stale session/frame, not a successful repaint. A null dirty set means that
     * damage is unknown and requires a full repaint. An empty set means no change.
     * Initial frames, session changes and geometry changes always repaint fully.
     * Nothing is retained from the caller's dirty tuple array.
     */
    public synchronized Plan publish(long nextSession, long nextRevision, int frameWidth,
            int frameHeight, int viewportWidth, int viewportHeight, int[] dirty) {
        validateGeometry(frameWidth, frameHeight, viewportWidth, viewportHeight);
        if (nextSession < 0 || nextRevision < 0) {
            throw new IllegalArgumentException("session and revision must be nonnegative");
        }
        if (dirty != null) {
            if ((dirty.length & 3) != 0 || dirty.length / 4 > MAX_INPUT_RECTS) {
                throw new IllegalArgumentException("dirty array must contain at most 4096 rectangles");
            }
            // Validate every input first, even when a geometry change means full paint.
            for (int i = 0; i < dirty.length; i += 4) {
                if (dirty[i + 2] < 0 || dirty[i + 3] < 0) {
                    throw new IllegalArgumentException("negative dirty rectangle size");
                }
            }
        }
        if (nextSession < session || nextSession == session && nextRevision <= revision) {
            return null;
        }
        boolean full = session != nextSession || frameWidth != sourceWidth || frameHeight != sourceHeight
                || viewportWidth != viewWidth || viewportHeight != viewHeight || dirty == null;
        Rect target = fit(frameWidth, frameHeight, viewportWidth, viewportHeight);
        int[] result;
        if (full) {
            result = new int[] { 0, 0, viewportWidth, viewportHeight };
        } else {
            // 64 native rectangles maximum; overflow is a full frame, never a dropped update.
            int[] rectangles = new int[MAX_RECTS * 4];
            int count = 0;
            long area = 0;
            long halfScreen = (long) viewportWidth * viewportHeight / 2;
            for (int i = 0; i < dirty.length; i += 4) {
                Rect projected = project(target, frameWidth, frameHeight,
                        dirty[i], dirty[i + 1], dirty[i + 2], dirty[i + 3]);
                if (projected == null) {
                    continue;
                }
                long size = (long) projected.width * projected.height;
                if (size > halfScreen || area + size > halfScreen || count == MAX_RECTS) {
                    full = true;
                    break;
                }
                area += size;
                // Union overlapping or touching dirty boxes, conservatively.
                int left = projected.x;
                int top = projected.y;
                int right = projected.x + projected.width;
                int bottom = projected.y + projected.height;
                int at = 0;
                while (at < count) {
                    int off = at * 4;
                    int otherLeft = rectangles[off];
                    int otherTop = rectangles[off + 1];
                    int otherRight = otherLeft + rectangles[off + 2];
                    int otherBottom = otherTop + rectangles[off + 3];
                    if ((long) left <= otherRight && (long) otherLeft <= right
                            && (long) top <= otherBottom && (long) otherTop <= bottom) {
                        left = Math.min(left, otherLeft);
                        top = Math.min(top, otherTop);
                        right = Math.max(right, otherRight);
                        bottom = Math.max(bottom, otherBottom);
                        count--;
                        System.arraycopy(rectangles, count * 4, rectangles, off, 4);
                        at = 0; // the extended union may intersect previously visited entries
                    } else {
                        at++;
                    }
                }
                int off = count++ * 4;
                rectangles[off] = left;
                rectangles[off + 1] = top;
                rectangles[off + 2] = right - left;
                rectangles[off + 3] = bottom - top;
            }
            result = full ? new int[] { 0, 0, viewportWidth, viewportHeight }
                    : Arrays.copyOf(rectangles, count * 4);
        }
        // No state transition occurs until geometry and every dirty rectangle validate.
        session = nextSession;
        revision = nextRevision;
        sourceWidth = frameWidth;
        sourceHeight = frameHeight;
        viewWidth = viewportWidth;
        viewHeight = viewportHeight;
        content = target;
        return new Plan(session, revision, target, full, result);
    }

    /**
     * Input mapping is revision-bound. Coordinates in letterbox margins are
     * rejected, not clamped onto the remote desktop. Remote input permission is
     * the embedding application's independent responsibility.
     */
    public synchronized int[] pointer(long expectedSession, long expectedRevision, int x, int y) {
        if (session != expectedSession || revision != expectedRevision || content == null) {
            return null;
        }
        Rect r = content;
        if ((long) x < r.x || (long) y < r.y
                || (long) x >= (long) r.x + r.width || (long) y >= (long) r.y + r.height) {
            return null;
        }
        return new int[] {
                (int) ((long) (x - r.x) * sourceWidth / r.width),
                (int) ((long) (y - r.y) * sourceHeight / r.height)
        };
    }

    /** Aspect-fit a remote framebuffer inside the client, without floating point. */
    public static Rect fit(int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight) {
        validateGeometry(sourceWidth, sourceHeight, viewportWidth, viewportHeight);
        int w;
        int h;
        if ((long) viewportWidth * sourceHeight <= (long) viewportHeight * sourceWidth) {
            w = viewportWidth;
            h = (int) Math.max(1, (long) viewportWidth * sourceHeight / sourceWidth);
        } else {
            h = viewportHeight;
            w = (int) Math.max(1, (long) viewportHeight * sourceWidth / sourceHeight);
        }
        return new Rect((viewportWidth - w) / 2, (viewportHeight - h) / 2, w, h);
    }

    /** Outward projection: every changed source pixel can affect only painted target pixels. */
    public static Rect project(Rect content, int sourceWidth, int sourceHeight,
            int x, int y, int w, int h) {
        if (content == null) {
            throw new IllegalArgumentException("content is null");
        }
        if (sourceWidth < 1 || sourceHeight < 1 || w < 0 || h < 0 || content.width < 1 || content.height < 1) {
            throw new IllegalArgumentException("invalid projection geometry");
        }
        long left = Math.max(0L, x);
        long top = Math.max(0L, y);
        long right = Math.min(sourceWidth, (long) x + w);
        long bottom = Math.min(sourceHeight, (long) y + h);
        if (right <= left || bottom <= top) {
            return null;
        }
        long xl = content.x + left * content.width / sourceWidth;
        long yt = content.y + top * content.height / sourceHeight;
        long xr = content.x + ceilDiv(right * content.width, sourceWidth);
        long yb = content.y + ceilDiv(bottom * content.height, sourceHeight);
        return new Rect((int) xl, (int) yt, (int) (xr - xl), (int) (yb - yt));
    }

    private static long ceilDiv(long numerator, long divisor) {
        return numerator / divisor + (numerator % divisor == 0 ? 0 : 1);
    }

    private static void validateGeometry(int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight) {
        if (sourceWidth < 1 || sourceHeight < 1 || sourceWidth > MAX_AXIS || sourceHeight > MAX_AXIS
                || viewportWidth < 1 || viewportHeight < 1) {
            throw new IllegalArgumentException("invalid source/viewport dimensions");
        }
    }
}
