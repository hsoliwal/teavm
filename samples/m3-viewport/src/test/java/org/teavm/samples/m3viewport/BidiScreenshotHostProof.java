/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Self-contained WebDriver BiDi screenshot protocol and lifecycle proof; no Firefox or SWT dependencies. */
public final class BidiScreenshotHostProof {
    private static int assertions;
    private BidiScreenshotHostProof() { }

    public static void main(String[] args) throws Exception {
        commandsValidateContextAndMime();
        completeViewportSnapshotsPreserveSafeFrameAuthorization();
        jpegCaptureAndCloseRequireExplicitAuthorization();
        unsolicitedAndStaleResponsesAreNotPresentations();
        mismatchAndMalformedRepliesFailClosed();
        System.out.println("M3_BIDI_SCREENSHOT_HOST_PASS assertions=" + assertions);
    }

    private static void commandsValidateContextAndMime() {
        String png = BidiScreenshotCommands.capture(4, "ctx-abc:12", RemoteFrameInbox.Format.PNG, 90);
        yes(png.contains("\"browsingContext.captureScreenshot\""));
        yes(png.contains("\"origin\":\"viewport\""));
        yes(png.contains("\"type\":\"image/png\""));
        no(png.contains("quality"));
        String jpeg = BidiScreenshotCommands.capture(5, "a_b.c-01", RemoteFrameInbox.Format.JPEG, 80);
        yes(jpeg.contains("\"quality\":0.8"));
        yes(jpeg.contains("\"type\":\"image/jpeg\""));
        for (String context : new String[] {"", "hi\"bad", "bad\\path", "x\n", "🔥", "x".repeat(129)}) {
            invalid(() -> BidiScreenshotCommands.capture(1, context, RemoteFrameInbox.Format.PNG, 100));
        }
        invalid(() -> BidiScreenshotCommands.capture(0, "valid", RemoteFrameInbox.Format.PNG, 100));
        invalid(() -> BidiScreenshotCommands.capture(1, "valid", null, 100));
        invalid(() -> BidiScreenshotCommands.capture(1, "valid", RemoteFrameInbox.Format.JPEG, 101));
    }

    private static void completeViewportSnapshotsPreserveSafeFrameAuthorization() throws Exception {
        RemoteFrameInbox inbox = new RemoteFrameInbox();
        FakeSocket wire = new FakeSocket();
        CountDownLatch arrived = new CountDownLatch(2);
        AtomicReference<Throwable> fail = new AtomicReference<>();
        try (BidiScreenshotHost host = new BidiScreenshotHost(inbox, 19, "ctx-111", 200, 100,
                RemoteFrameInbox.Format.PNG, 100, arrived::countDown, fail::set)) {
            host.onOpen(wire.socket);
            eq(0, wire.messages.size()); // No automatic privileged browser capture.
            yes(host.capture());
            no(host.capture()); // Exactly one outstanding request.
            awaitContains(wire.messages, "browsingContext.captureScreenshot");
            byte[] image = png(400, 200);
            String response = reply(1, image);
            int split = response.length() / 2;
            host.onText(wire.socket, response.substring(0, split), false);
            host.onText(wire.socket, response.substring(split), true);
            awaitCount(arrived, 1);
            RemoteFrameInbox.Delivery first = inbox.take(400, 300);
            notNull(first);
            eq(400, first.frame().pixelWidth());
            eq(200, first.frame().pixelHeight());
            eq(19, first.frame().epoch());
            eq(1, first.frame().revision());
            eq(0, first.frame().acknowledgementId()); // BiDi requires no CDP ACK.
            eq(0, wire.messages.stream().filter(m -> m.contains("screencastFrameAck")).count());
            eq(null, inbox.pointer(19, 1, 200, 150));
            yes(inbox.displayed(first));
            eq(null, inbox.pointer(19, 1, 200, 150));
            inbox.setInputAuthorized(true);
            eq(new RemoteFrameInbox.CssPoint(100, 50), inbox.pointer(19, 1, 200, 150));
            eq(null, inbox.pointer(19, 1, 200, 49)); // Letterbox: black gutter.
            yes(host.capture());
            awaitContains(wire.messages, "\"id\":2");
            host.onText(wire.socket, reply(2, image), true);
            awaitCount(arrived, 0);
            // A newer frame has not been presented: it must re-block old input.
            eq(null, inbox.pointer(19, 1, 200, 150));
            RemoteFrameInbox.Delivery second = inbox.take(400, 300);
            notNull(second);
            eq(2, second.frame().revision());
            eq(null, inbox.pointer(19, 2, 200, 150));
            yes(inbox.displayed(second));
            eq(new RemoteFrameInbox.CssPoint(100, 50), inbox.pointer(19, 2, 200, 150));
            eq(null, fail.get());
        }
        yes(wire.aborted.get() > 0);
        no(inbox.hasPending());
    }

    private static void jpegCaptureAndCloseRequireExplicitAuthorization() throws Exception {
        RemoteFrameInbox inbox = new RemoteFrameInbox();
        FakeSocket wire = new FakeSocket();
        CountDownLatch arrived = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BidiScreenshotHost host = new BidiScreenshotHost(inbox, 12, "jpeg-session", 80, 40,
                RemoteFrameInbox.Format.JPEG, 75, arrived::countDown, failure::set);
        host.onOpen(wire.socket);
        yes(host.capture());
        awaitContains(wire.messages, "\"quality\":0.75");
        host.onText(wire.socket, reply(1, jpeg(160, 80)), true);
        awaitCount(arrived, 0);
        RemoteFrameInbox.Delivery delivery = inbox.take(160, 80);
        notNull(delivery);
        eq(RemoteFrameInbox.Format.JPEG, delivery.frame().format());
        eq(160, delivery.frame().pixelWidth());
        eq(80, delivery.frame().pixelHeight());
        eq(null, inbox.pointer(12, 1, 80, 40));
        eq(null, failure.get());
        host.close();
        host.close();
        yes(wire.aborted.get() > 0);
        illegalState(host::capture);
        FakeSocket late = new FakeSocket();
        host.onOpen(late.socket);
        yes(late.aborted.get() > 0);
    }

    private static void unsolicitedAndStaleResponsesAreNotPresentations() throws Exception {
        RemoteFrameInbox inbox = new RemoteFrameInbox();
        FakeSocket wire = new FakeSocket();
        CountDownLatch arrived = new CountDownLatch(1);
        AtomicReference<Throwable> fail = new AtomicReference<>();
        try (BidiScreenshotHost host = new BidiScreenshotHost(inbox, 3, "xyz", 150, 50,
                RemoteFrameInbox.Format.PNG, 100, arrived::countDown, fail::set)) {
            host.onOpen(wire.socket);
            host.onText(wire.socket, "{\"type\":\"event\",\"method\":\"log.entryAdded\"}", true);
            yes(host.capture());
            host.onText(wire.socket, reply(99, png(150, 50)), true);
            Thread.sleep(80);
            no(inbox.hasPending());
            no(host.capture()); // A mismatched reply must not consume the outstanding request.
            host.onText(wire.socket, reply(1, png(150, 50)), true);
            awaitCount(arrived, 0);
            yes(inbox.hasPending());
            eq(null, fail.get());
        }
    }

    private static void mismatchAndMalformedRepliesFailClosed() throws Exception {
        for (String bad : new String[] {
                "{\"type\":\"success\",\"id\":1,\"result\":{\"data\":\"!\"}}",
                "{\"type\":\"error\",\"id\":1,\"error\":\"invalid argument\"}",
                "{\"type\":\"success\",\"id\":1,\"result\":{\"data\":\"QQ==\"}}",
                "{\"type\":\"success\",\"id\":1,\"id\":1,\"result\":{\"data\":\"AA==\"}}",
                "{\"type\":\"success\",\"id\":1,\"result\":{\"data\":99}}"
        }) {
            RemoteFrameInbox inbox = new RemoteFrameInbox();
            FakeSocket wire = new FakeSocket();
            CountDownLatch failed = new CountDownLatch(1);
            try (BidiScreenshotHost host = new BidiScreenshotHost(inbox, 1, "id", 120, 60,
                    RemoteFrameInbox.Format.PNG, 100,
                    () -> { throw new AssertionError("malformed screenshot was published"); },
                    ignored -> failed.countDown())) {
                host.onOpen(wire.socket);
                yes(host.capture());
                host.onText(wire.socket, bad, true);
                awaitCount(failed, 0);
                no(inbox.hasPending());
                yes(wire.aborted.get() > 0);
            }
        }
        // Valid JPEG is not a valid response to a PNG-only capture.
        byte[] jpeg = jpeg(20, 10);
        RemoteFrameInbox inbox = new RemoteFrameInbox();
        FakeSocket wire = new FakeSocket();
        CountDownLatch failed = new CountDownLatch(1);
        try (BidiScreenshotHost host = new BidiScreenshotHost(inbox, 1, "id", 20, 10,
                RemoteFrameInbox.Format.PNG, 100,
                () -> { throw new AssertionError("JPEG accepted for PNG request"); },
                ignored -> failed.countDown())) {
            host.onOpen(wire.socket);
            yes(host.capture());
            host.onText(wire.socket, reply(1, jpeg), true);
            awaitCount(failed, 0);
            no(inbox.hasPending());
        }
    }

    private static String reply(long id, byte[] image) {
        return "{\"type\":\"success\",\"id\":" + id + ",\"result\":{\"data\":\""
                + Base64.getEncoder().encodeToString(image) + "\"}}";
    }

    private static byte[] png(int w, int h) throws IOException { return write(w, h, "png"); }
    private static byte[] jpeg(int w, int h) throws IOException { return write(w, h, "jpeg"); }
    private static byte[] write(int w, int h, String format) throws IOException {
        BufferedImage source = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(source, format, bytes)) throw new IOException("No decoder for " + format);
        return bytes.toByteArray();
    }

    private static final class FakeSocket implements InvocationHandler {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger aborted = new AtomicInteger();
        final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, this);
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "request" -> null;
                case "sendText" -> { messages.add((String) args[0]); yield CompletableFuture.completedFuture(socket); }
                case "abort" -> { aborted.incrementAndGet(); yield null; }
                case "toString" -> "fake-bidi-websocket";
                case "hashCode" -> System.identityHashCode(this);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static void awaitCount(CountDownLatch latch, long count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (latch.getCount() > count && System.nanoTime() < end) Thread.sleep(5);
        eq(count, latch.getCount());
    }
    private static void awaitContains(List<String> list, String expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < end) {
            synchronized (list) { if (list.stream().anyMatch(s -> s.contains(expected))) return; }
            Thread.sleep(5);
        }
        throw new AssertionError("missing BiDi command: " + expected);
    }
    private static void illegalState(Runnable op) {
        try { op.run(); throw new AssertionError("closed host accepted a command"); }
        catch (IllegalStateException expected) { assertions++; }
    }
    private static void invalid(Runnable op) {
        try { op.run(); throw new AssertionError("invalid input accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void notNull(Object a) { yes(a != null); }
    private static void yes(boolean v) { assertions++; if (!v) throw new AssertionError("failed assertion #" + assertions); }
    private static void no(boolean v) { yes(!v); }
    private static void eq(Object a, Object b) { yes(ObjectsEqual.equals(a, b)); }
    private static void eq(long a, long b) { yes(a == b); }
    private static final class ObjectsEqual {
        static boolean equals(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    }
}
