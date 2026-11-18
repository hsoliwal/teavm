/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.util.Arrays;
import java.util.Random;

/** Display-free Java 21 deterministic contract tests for CDP full-frame transport. */
public final class RemoteFrameInboxProof {
    private static final byte[] JPEG = { (byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9 };
    private static final byte[] PNG = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0 };
    private static long checks;

    private RemoteFrameInboxProof() { }

    public static void main(String[] args) {
        fullFrameAndAck();
        staleAndCoalesced();
        presentationAndAuthorization();
        generationAndValidation();
        cdpEnvelopes();
        randomizedGeometry(50_000);
        System.out.println("M3_CDP_FRAME_INBOX_PASS assertions=" + checks);
    }

    private static void fullFrameAndAck() {
        RemoteFrameInbox box = new RemoteFrameInbox();
        byte[] caller = JPEG.clone();
        RemoteFrameInbox.Reception receipt = box.receive(1, 1, 77, RemoteFrameInbox.Format.JPEG,
                1600, 900, 800, 450, caller);
        yes(receipt.accepted());
        eq(77, receipt.ackId());
        eq(0, receipt.discardedPending());
        caller[2] = 99;
        eq(JPEG.length, box.pendingBytes());
        RemoteFrameInbox.Delivery d = box.take(1000, 800);
        eq((Object) null, box.take(1000, 800));
        yes(d.geometry().full()); // Full compressed frames are not dirty rectangles.
        eq(new FrameWindow.Rect(0, 119, 1000, 562), d.geometry().content());
        eq(JPEG[2], d.frame().compressed()[2]);
        byte[] tmp = d.frame().compressed();
        tmp[2] = 100;
        eq(JPEG[2], d.frame().compressed()[2]);
        eq((Object) null, box.pointer(1, 1, 500, 400)); // No implicit input.
        yes(box.displayed(d));
        box.setInputAuthorized(true);
        eq(new RemoteFrameInbox.CssPoint(0, 0), box.pointer(1, 1, 0, 119));
        eq(new RemoteFrameInbox.CssPoint(799, 449), box.pointer(1, 1, 999, 680));
        eq((Object) null, box.pointer(1, 1, 0, 118));
        eq((Object) null, box.pointer(1, 1, 1000, 300));
        box.close();
        eq((Object) null, box.pointer(1, 1, 0, 120));
        rejectedState(() -> box.receive(1, 2, 79, RemoteFrameInbox.Format.JPEG, 1, 1, 1, 1, JPEG));
        box.close(); // idempotent
    }

    private static void staleAndCoalesced() {
        RemoteFrameInbox box = new RemoteFrameInbox();
        yes(box.receive(3, 0, 1, RemoteFrameInbox.Format.PNG, 4, 4, 4, 4, PNG).accepted());
        yes(box.receive(3, 1, 2, RemoteFrameInbox.Format.PNG, 4, 4, 4, 4, PNG).accepted());
        yes(box.receive(3, 2, 3, RemoteFrameInbox.Format.PNG, 4, 4, 4, 4, PNG).accepted());
        eq(2, box.discardedCount());
        eq(2, box.receive(3, 1, 22, RemoteFrameInbox.Format.PNG, 4, 4, 4, 4, PNG).discardedPending());
        no(box.receive(3, 1, 22, RemoteFrameInbox.Format.PNG, 4, 4, 4, 4, PNG).accepted());
        RemoteFrameInbox.Delivery latest = box.take(4, 4);
        eq(3, latest.frame().acknowledgementId());
        eq(2, latest.frame().revision());
        yes(box.displayed(latest));
        box.setInputAuthorized(true);
        yes(box.receive(3, 3, 4, RemoteFrameInbox.Format.PNG, 4, 4, 4, 4, PNG).accepted());
        // Presentation on the old image is not authorized after a newer frame arrives.
        eq((Object) null, box.pointer(3, 2, 0, 0));
        RemoteFrameInbox.Delivery next = box.take(4, 4);
        no(box.displayed(latest));
        yes(box.displayed(next));
        eq(new RemoteFrameInbox.CssPoint(0, 0), box.pointer(3, 3, 0, 0));
    }

    private static void presentationAndAuthorization() {
        RemoteFrameInbox box = new RemoteFrameInbox();
        box.receive(1, 2, 1, RemoteFrameInbox.Format.JPEG, 800, 400, 400, 200, JPEG);
        RemoteFrameInbox.Delivery scheduled = box.take(400, 400);
        box.setInputAuthorized(true);
        eq((Object) null, box.pointer(1, 2, 200, 200));
        box.receive(1, 3, 2, RemoteFrameInbox.Format.JPEG, 800, 400, 400, 200, JPEG);
        no(box.displayed(scheduled));
        RemoteFrameInbox.Delivery next = box.take(400, 400);
        yes(box.displayed(next));
        eq(new RemoteFrameInbox.CssPoint(200, 100), box.pointer(1, 3, 200, 200));
        box.setInputAuthorized(false);
        eq((Object) null, box.pointer(1, 3, 200, 200));
    }

    private static void generationAndValidation() {
        RemoteFrameInbox box = new RemoteFrameInbox();
        box.receive(10, 9, 2, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5, PNG);
        var first = box.take(100, 100);
        yes(box.displayed(first));
        box.setInputAuthorized(true);
        eq(new RemoteFrameInbox.CssPoint(0, 0), box.pointer(10, 9, 0, 0));
        invalid(() -> box.receive(10, 10, 3, RemoteFrameInbox.Format.PNG, 0, 10, 5, 5, PNG));
        invalid(() -> box.receive(10, 10, 3, RemoteFrameInbox.Format.JPEG, 10, 10, 5, 5, PNG));
        invalid(() -> box.receive(10, 10, 3, RemoteFrameInbox.Format.PNG, 8192, 8192, 5, 5, PNG));
        invalid(() -> box.receive(10, 10, 3, RemoteFrameInbox.Format.PNG, 10, 10, 0, 5, PNG));
        invalid(() -> box.receive(10, 10, 3, RemoteFrameInbox.Format.JPEG, 10, 10, 5, 5, new byte[4]));
        invalid(() -> box.receive(-1, 10, 3, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5, PNG));
        invalid(() -> box.receive(10, 10, -1, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5, PNG));
        invalid(() -> box.receive(10, 10, 3, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5,
                new byte[RemoteFrameInbox.MAX_COMPRESSED_BYTES + 1]));
        eq(new RemoteFrameInbox.CssPoint(0, 0), box.pointer(10, 9, 0, 0));
        no(box.receive(9, 9999, 4, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5, PNG).accepted());
        yes(box.receive(11, 0, 5, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5, PNG).accepted());
        eq((Object) null, box.pointer(10, 9, 0, 0));
        var second = box.take(100, 100);
        yes(box.displayed(second));
        eq((Object) null, box.pointer(11, 0, 0, 0)); // permission reset on epoch
        box.setInputAuthorized(true);
        eq(new RemoteFrameInbox.CssPoint(0, 0), box.pointer(11, 0, 0, 0));
        yes(box.receive(12, 0, 6, RemoteFrameInbox.Format.PNG, 10, 10, 5, 5, PNG).accepted());
        invalid(() -> box.take(0, 100));
        yes(box.hasPending()); // validation cannot eat a queued frame
    }

    private static void cdpEnvelopes() {
        eq("{\"id\":17,\"method\":\"Page.startScreencast\",\"params\":{\"format\":\"jpeg\",\"quality\":75,\"maxWidth\":800,\"maxHeight\":600,\"everyNthFrame\":1,\"maxFramesInFlight\":1}}",
                CdpScreencastCommands.start(17, 800, 600, 75));
        eq("{\"id\":18,\"method\":\"Page.screencastFrameAck\",\"params\":{\"sessionId\":23}}",
                CdpScreencastCommands.acknowledge(18, 23));
        eq("{\"id\":19,\"method\":\"Page.stopScreencast\"}", CdpScreencastCommands.stop(19));
        eq("{\"id\":20,\"method\":\"Input.dispatchMouseEvent\",\"params\":{\"type\":\"mousePressed\",\"x\":40,\"y\":20,\"button\":\"left\",\"buttons\":1}}",
                CdpScreencastCommands.pointer(20, new RemoteFrameInbox.CssPoint(40, 20),
                        "mousePressed", "left", 1));
        invalid(() -> CdpScreencastCommands.start(0, 300, 100, 80));
        invalid(() -> CdpScreencastCommands.start(4, 300, 100, 101));
        invalid(() -> CdpScreencastCommands.pointer(5, new RemoteFrameInbox.CssPoint(1, 2),
                "mouseMoved\"},\"bad\":true", "none", 0));
        invalid(() -> CdpScreencastCommands.pointer(5, null, "mouseMoved", "none", 0));
        invalid(() -> CdpScreencastCommands.acknowledge(-1, 23));
    }

    private static void randomizedGeometry(int rounds) {
        Random random = new Random(0x12349861);
        RemoteFrameInbox box = new RemoteFrameInbox();
        long lastDiscard = 0;
        for (int i = 0; i < rounds; i++) {
            int pw = random.nextInt(300) + 1;
            int ph = random.nextInt(300) + 1;
            int cw = random.nextInt(300) + 1;
            int ch = random.nextInt(300) + 1;
            int vw = random.nextInt(501) + 1;
            int vh = random.nextInt(501) + 1;
            var receipt = box.receive(42, i, i, RemoteFrameInbox.Format.JPEG,
                    pw, ph, cw, ch, JPEG);
            yes(receipt.accepted());
            yes(receipt.discardedPending() >= lastDiscard);
            lastDiscard = receipt.discardedPending();
            if ((i & 7) != 0) continue;
            var delivery = box.take(vw, vh);
            yes(delivery.geometry().full());
            yes(box.displayed(delivery));
            box.setInputAuthorized(true);
            FrameWindow.Rect b = delivery.geometry().content();
            int x = b.x() + random.nextInt(b.width());
            int y = b.y() + random.nextInt(b.height());
            int sx = (int) ((long) (x - b.x()) * pw / b.width());
            int sy = (int) ((long) (y - b.y()) * ph / b.height());
            var css = box.pointer(42, i, x, y);
            eq(new RemoteFrameInbox.CssPoint((int) ((long) sx * cw / pw),
                    (int) ((long) sy * ch / ph)), css);
        }
    }

    private static void eq(Object e, Object a) {
        check(e == null ? a == null : e.equals(a), "expected=" + e + " actual=" + a);
    }
    private static void eq(long e, long a) { check(e == a, "expected=" + e + " actual=" + a); }
    private static void eq(byte e, byte a) { check(e == a, "expected=" + e + " actual=" + a); }
    private static void yes(boolean x) { check(x, "expected true"); }
    private static void no(boolean x) { check(!x, "expected false"); }
    private static void invalid(Runnable action) {
        boolean thrown = false;
        try { action.run(); } catch (IllegalArgumentException expected) { thrown = true; }
        yes(thrown);
    }
    private static void rejectedState(Runnable action) {
        boolean thrown = false;
        try { action.run(); } catch (IllegalStateException expected) { thrown = true; }
        yes(thrown);
    }
    private static void check(boolean okay, String message) {
        checks++;
        if (!okay) throw new AssertionError(message);
    }
}
