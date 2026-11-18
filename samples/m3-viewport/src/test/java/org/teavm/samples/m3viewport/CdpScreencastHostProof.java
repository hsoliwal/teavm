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

/** A fake CDP WebSocket wire; no browser/network processes or credentials are used. */
public final class CdpScreencastHostProof {
    private static int checks;

    private CdpScreencastHostProof() { }

    public static void main(String[] args) throws Exception {
        validScreencastAndInput();
        parserRefusesMalformedOrAmbiguousObjects();
        malformedPayloadStopsHost();
        System.out.println("M3_CDP_HOST_PROOF_PASS checks=" + checks);
    }

    private static void validScreencastAndInput() throws Exception {
        RemoteFrameInbox box = new RemoteFrameInbox();
        FakeSocket fake = new FakeSocket();
        CountDownLatch arrived = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (CdpScreencastHost host = new CdpScreencastHost(box, 9, 80, 40,
                arrived::countDown, failure::set)) {
            host.onOpen(fake.socket);
            host.start(160, 80, 70);
            awaitContains(fake.messages, "Page.startScreencast");
            byte[] image = png(160, 80);
            String encoded = Base64.getEncoder().encodeToString(image);
            String json = "{\"params\":{\"metadata\":{\"deviceWidth\":160.0},\"data\":\"" + encoded
                    + "\",\"sessionId\":23},\"method\":\"Page.screencastFrame\"}";
            int middle = json.length() / 2;
            host.onText(fake.socket, json.substring(0, middle), false);
            host.onText(fake.socket, json.substring(middle), true);
            yes(arrived.getCount() == 1 || awaitCount(arrived, 1));
            awaitContains(fake.messages, "Page.screencastFrameAck");
            eq(160, box.take(200, 200).frame().pixelWidth());
            // The test intentionally consumes a frame before connecting input permission.
            no(host.pointer(1, 100, 100, "mousePressed", "left", 1));
            String json2 = "{\"method\":\"Page.screencastFrame\",\"params\":{\"sessionId\":24,\"data\":\""
                    + encoded + "\",\"metadata\":{\"deviceHeight\":80}}}";
            host.onText(fake.socket, json2, true);
            yes(arrived.await(6, TimeUnit.SECONDS));
            awaitContains(fake.messages, "\"sessionId\":24");
            RemoteFrameInbox.Delivery d = box.take(200, 200);
            yes(d.geometry().full());
            yes(box.displayed(d));
            no(host.pointer(2, 100, 100, "mousePressed", "left", 1));
            box.setInputAuthorized(true);
            no(host.pointer(1, 100, 100, "mousePressed", "left", 1));
            no(host.pointer(2, 100, 49, "mousePressed", "left", 1)); // upper letterbox
            yes(host.pointer(2, 100, 100, "mousePressed", "left", 1));
            awaitContains(fake.messages, "Input.dispatchMouseEvent");
            awaitContains(fake.messages, "\"x\":40,\"y\":20");
            eq((Object) null, failure.get());
            host.stop();
            awaitContains(fake.messages, "Page.stopScreencast");
        }
        yes(fake.aborted.get() > 0);
    }

    private static void parserRefusesMalformedOrAmbiguousObjects() {
        eq((Object) null, CdpFrameJson.frame("{\"id\":1,\"result\":{\"ok\":true}}"));
        for (String invalid : new String[] {
                "{\"method\":\"Page.screencastFrame\",\"method\":\"Page.screencastFrame\"}",
                "{\"method\":\"Page.screencastFrame\",\"params\":{\"sessionId\":-1,\"data\":\"QQ==\"}}",
                "{\"method\":\"Page.screencastFrame\",\"params\":{\"sessionId\":0,\"data\":\"QQ==\"}}",
                "{\"id\":01}", "{\"id\":1}garbage", "[1,2,]", "{\"a\":true,}", "{\"a\":\"\\uDXY1\"}",
                "{\"a\":[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]}"
        }) invalid(() -> CdpFrameJson.frame(invalid));
        invalid(() -> CdpFrameJson.frame(null));
    }

    private static void malformedPayloadStopsHost() throws Exception {
        RemoteFrameInbox box = new RemoteFrameInbox();
        FakeSocket fake = new FakeSocket();
        CountDownLatch error = new CountDownLatch(1);
        try (CdpScreencastHost host = new CdpScreencastHost(box, 3, 20, 20, () -> {
            throw new AssertionError("must not publish invalid image");
        }, t -> error.countDown())) {
            host.onOpen(fake.socket);
            host.onText(fake.socket, "{\"method\":\"Page.screencastFrame\",\"params\":{\"sessionId\":1,\"data\":\"QQ==\"}}", true);
            yes(error.await(6, TimeUnit.SECONDS));
            yes(fake.aborted.get() > 0);
            no(box.hasPending());
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        yes(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    private static boolean awaitCount(CountDownLatch latch, long count) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < end) {
            if (latch.getCount() == count) return true;
            Thread.sleep(5);
        }
        return latch.getCount() == count;
    }

    private static void awaitContains(List<String> lines, String expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < end) {
            synchronized (lines) {
                if (lines.stream().anyMatch(m -> m.contains(expected))) return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("missing CDP command: " + expected);
    }

    private static final class FakeSocket implements InvocationHandler {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger aborted = new AtomicInteger();
        final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, this);

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "request" -> null;
                case "sendText" -> {
                    messages.add((String) args[0]);
                    yield CompletableFuture.completedFuture(socket);
                }
                case "abort" -> { aborted.incrementAndGet(); yield null; }
                case "toString" -> "fake-websocket";
                case "hashCode" -> System.identityHashCode(this);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static void invalid(Runnable r) {
        boolean thrown = false;
        try { r.run(); } catch (IllegalArgumentException expected) { thrown = true; }
        yes(thrown);
    }
    private static void yes(boolean actual) { check(actual); }
    private static void no(boolean actual) { check(!actual); }
    private static void eq(Object expected, Object actual) {
        check(expected == null ? actual == null : expected.equals(actual));
    }
    private static void eq(long expected, long actual) { check(expected == actual); }
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("failed check " + checks);
    }
}
