/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.util.Arrays;
import java.util.Random;

/** Strict JVM contract proof; also uses only portable Java library calls. */
public final class FrameWindowProof {
    private static int checks;

    private FrameWindowProof() {
    }

    public static void main(String[] args) {
        aspectAndInput();
        sessionAndStaleInput();
        immutableDamageAndClipping();
        overflowAndThreshold();
        randomizedProjection(20_000);
        System.out.println("M3_FRAME_WINDOW_PASS assertions=" + checks);
    }

    private static void aspectAndInput() {
        eq(new FrameWindow.Rect(300, 0, 200, 400), FrameWindow.fit(100, 200, 800, 400));
        eq(new FrameWindow.Rect(0, 75, 400, 225), FrameWindow.fit(16, 9, 400, 375));
        FrameWindow window = new FrameWindow();
        FrameWindow.Plan first = window.publish(1, 0, 100, 200, 800, 400, new int[0]);
        check(first.full(), "first frame always repaints");
        eq(new int[] {0, 0, 800, 400}, first.damage());
        eq((Object) null, window.pointer(1, 0, 299, 50));
        eq((Object) null, window.pointer(1, 0, 500, 50));
        eq(new int[] {0, 0}, window.pointer(1, 0, 300, 0));
        eq(new int[] {99, 199}, window.pointer(1, 0, 499, 399));
        eq((Object) null, window.pointer(1, 1, 300, 20));
    }

    private static void sessionAndStaleInput() {
        FrameWindow win = new FrameWindow();
        eq((Object) null, win.pointer(0, 0, 4, 4));
        FrameWindow.Plan first = win.publish(0, 5, 100, 100, 100, 100, null);
        check(first.full(), "initial unknown damage full");
        eq((Object) null, win.publish(0, 5, 100, 100, 100, 100, new int[0]));
        eq((Object) null, win.publish(0, 4, 100, 100, 100, 100, new int[0]));
        FrameWindow.Plan changed = win.publish(1, 0, 100, 100, 100, 100, null);
        check(changed.full(), "new session full");
        FrameWindow.Plan noDamage = win.publish(1, 1, 100, 100, 100, 100, new int[0]);
        check(!noDamage.full(), "same size incremental");
        eq(0, noDamage.rectangleCount());
        FrameWindow.Plan geometry = win.publish(1, 2, 100, 100, 120, 100, new int[0]);
        check(geometry.full(), "resize must repaint letterboxes");
        eq(new int[] {0, 0, 120, 100}, geometry.damage());
        FrameWindow.Plan source = win.publish(1, 3, 100, 150, 120, 100, new int[0]);
        check(source.full(), "source resize must repaint letterboxes");
        eq((Object) null, win.pointer(1, 2, 60, 50));
        eq(new int[] {0, 0}, win.pointer(1, 3, 27, 0));
        eq((Object) null, win.pointer(1, 3, 26, 0));
        eq((Object) null, win.pointer(0, 99, 60, 50));
    }

    private static void immutableDamageAndClipping() {
        FrameWindow win = new FrameWindow();
        win.publish(3, 0, 100, 100, 200, 200, null);
        int[] dirty = {10, 10, 5, 5, 14, 14, 8, 8, 500, 500, 10, 10};
        FrameWindow.Plan plan = win.publish(3, 1, 100, 100, 200, 200, dirty);
        check(!plan.full(), "small changes incremental");
        eq(1, plan.rectangleCount());
        eq(new int[] {20, 20, 24, 24}, plan.damage());
        dirty[0] = 99;
        int[] mutated = plan.damage();
        mutated[0] = -2;
        eq(new int[] {20, 20, 24, 24}, plan.damage());
        eq(3, plan.session());
        eq(1, plan.revision());
        invalid(() -> win.publish(3, 2, 100, 100, 200, 200, new int[] {0, 0, -1, 2}));
        invalid(() -> win.publish(3, 2, 100, 100, 200, 200, new int[] {0, 0, 1}));
        invalid(() -> win.publish(3, 2, 0, 100, 200, 200, null));
        invalid(() -> win.publish(-1, 2, 100, 100, 200, 200, null));
        eq((Object) null, win.publish(3, 1, 100, 100, 200, 200, null));
        FrameWindow.Plan after = win.publish(3, 2, 100, 100, 200, 200, new int[] {-1, -1, 2, 2});
        eq(new int[] {0, 0, 2, 2}, after.damage());
    }

    private static void overflowAndThreshold() {
        FrameWindow.Rect large = FrameWindow.fit(32_768, 32_768, Integer.MAX_VALUE, Integer.MAX_VALUE);
        eq(Integer.MAX_VALUE, large.width());
        FrameWindow.Rect last = FrameWindow.project(large, 32_768, 32_768, 32_767, 32_767, 1, 1);
        check((long) last.x() + last.width() == Integer.MAX_VALUE, "last right edge exact");
        check((long) last.y() + last.height() == Integer.MAX_VALUE, "last bottom edge exact");
        FrameWindow win = new FrameWindow();
        win.publish(0, 0, 100, 100, 100, 100, null);
        FrameWindow.Plan big = win.publish(0, 1, 100, 100, 100, 100, new int[] {0, 0, 80, 80});
        check(big.full(), "large region must promote to full");
        int[] disjoint = new int[80 * 4];
        for (int i = 0; i < 80; i++) {
            disjoint[i * 4] = (i % 10) * 10;
            disjoint[i * 4 + 1] = (i / 10) * 10;
            disjoint[i * 4 + 2] = 1;
            disjoint[i * 4 + 3] = 1;
        }
        FrameWindow.Plan many = win.publish(0, 2, 100, 100, 100, 100, disjoint);
        check(many.full(), "too many disjoint rectangles must promote to full");
        int[] oversized = new int[(FrameWindow.MAX_INPUT_RECTS + 1) * 4];
        invalid(() -> win.publish(0, 3, 100, 100, 100, 100, oversized));
        eq((Object) null, win.publish(0, 2, 100, 100, 100, 100, new int[0]));
    }

    private static void randomizedProjection(int rounds) {
        Random random = new Random(0xC0FFEE);
        for (int i = 0; i < rounds; i++) {
            int sw = 1 + random.nextInt(40);
            int sh = 1 + random.nextInt(40);
            int vw = 1 + random.nextInt(65);
            int vh = 1 + random.nextInt(65);
            FrameWindow.Rect bounds = FrameWindow.fit(sw, sh, vw, vh);
            check((long) bounds.x() + bounds.width() <= vw && (long) bounds.y() + bounds.height() <= vh,
                    "fitted content inside viewport");
            FrameWindow win = new FrameWindow();
            win.publish(0, 0, sw, sh, vw, vh, null);
            int dx = random.nextInt(sw + 4) - 2;
            int dy = random.nextInt(sh + 4) - 2;
            int dw = random.nextInt(sw + 1);
            int dh = random.nextInt(sh + 1);
            FrameWindow.Plan p = win.publish(0, 1, sw, sh, vw, vh, new int[] {dx, dy, dw, dh});
            int[] packed = p.damage();
            for (int cy = bounds.y(); cy < bounds.y() + bounds.height(); cy++) {
                for (int cx = bounds.x(); cx < bounds.x() + bounds.width(); cx++) {
                    // Independent destination->source nearest sampler. The projected
                    // outward rectangle must cover every changed sampled pixel.
                    int sx = (int) ((long) (cx - bounds.x()) * sw / bounds.width());
                    int sy = (int) ((long) (cy - bounds.y()) * sh / bounds.height());
                    if ((long) sx >= dx && (long) sx < (long) dx + dw
                            && (long) sy >= dy && (long) sy < (long) dy + dh) {
                        boolean covered = p.full();
                        for (int j = 0; !covered && j < packed.length; j += 4) {
                            covered |= cx >= packed[j] && (long) cx < (long) packed[j] + packed[j + 2]
                                    && cy >= packed[j + 1] && (long) cy < (long) packed[j + 1] + packed[j + 3];
                        }
                        check(covered, "changed source sample was omitted from projected damage " + i);
                    }
                }
            }
        }
    }

    private static void invalid(Runnable runnable) {
        boolean rejected = false;
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "invalid value must refuse before publication");
    }

    private static void eq(Object expected, Object actual) {
        check(expected == null ? actual == null : expected.equals(actual),
                "expected " + expected + " but was " + actual);
    }

    private static void eq(long expected, long actual) {
        check(expected == actual, "expected " + expected + " but was " + actual);
    }

    private static void eq(int expected, int actual) {
        check(expected == actual, "expected " + expected + " but was " + actual);
    }

    private static void eq(int[] expected, int[] actual) {
        check(Arrays.equals(expected, actual), "expected " + Arrays.toString(expected)
                + " but was " + Arrays.toString(actual));
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
