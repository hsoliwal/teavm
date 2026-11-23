/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/** Exact simulated Firefox BiDi connection + screenshot test; no Firefox or SWT required. */
public final class FirefoxBiDiLiveProofTest {
    private static int assertions;
    private FirefoxBiDiLiveProofTest() { }

    public static void main(String[] args) throws Exception {
        originAndEndpointAdmission();
        listExplicitBrowserContexts();
        captureOnlyExplicitExistingContext();
        unsafeOrIncompleteBootstrapFailsClosed();
        System.out.println("M3_FIREFOX_BIDI_BOOTSTRAP_PASS assertions=" + assertions);
    }

    private static void originAndEndpointAdmission() {
        String base = "ws://127.0.0.1:9222/session";
        yes(base.equals(FirefoxBiDiLiveProof.requireLoopbackSession(base).toString()));
        for (String url : new String[] {
                "ws://example.com:9222/session", "ws://localhost:9222/session",
                "wss://127.0.0.1:9222/session", "ws://127.0.0.1:9222/devtools/page/1",
                "ws://user:secret@127.0.0.1:9222/session", "ws://127.0.0.1:9222/session?token=x",
                "ws://127.0.0.1:9222/session#fragment", "ws://127.0.0.1/session"
        }) {
            try {
                FirefoxBiDiLiveProof.requireLoopbackSession(url);
                throw new AssertionError("Admitted unwanted debugger endpoint: " + url);
            } catch (IllegalArgumentException good) { assertions++; }
        }
    }

    private static void listExplicitBrowserContexts() throws Exception {
        FakeSocket ws = new FakeSocket();
        try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof(null, 0, 0)) {
            probe.onOpen(ws.socket);
            yes(ws.sent.size() == 1 && ws.sent.get(0).contains("session.new"));
            probe.onText(ws.socket, sessionReply(true), true);
            yes(ws.sent.size() == 2 && ws.sent.get(1).contains("browsingContext.getTree"));
            probe.onText(ws.socket, treeReply("ctx-root", "ctx-child"), true);
            FirefoxBiDiLiveProof.Result result = probe.result().get(3, TimeUnit.SECONDS);
            no(result.captured());
            eq(result.contextIds(), List.of("ctx-root", "ctx-child"));
            eq(2, ws.sent.size()); // No screenshot in listing mode.
        }
        yes(ws.aborted.get() > 0);
    }

    private static void captureOnlyExplicitExistingContext() throws Exception {
        FakeSocket ws = new FakeSocket();
        try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof("ctx-child", 200, 100)) {
            probe.onOpen(ws.socket);
            probe.onText(ws.socket, sessionReply(true), true);
            probe.onText(ws.socket, treeReply("ctx-root", "ctx-child"), true);
            yes(ws.sent.size() == 3);
            yes(ws.sent.get(2).contains("browsingContext.captureScreenshot"));
            yes(ws.sent.get(2).contains("ctx-child"));
            FakeSocket foreign = new FakeSocket();
            probe.onText(foreign.socket, screenshotReply(1, png(200, 100)), true);
            yes(foreign.aborted.get() > 0);
            no(probe.result().isDone());
            probe.onText(ws.socket, screenshotReply(1, png(200, 100)), true);
            FirefoxBiDiLiveProof.Result result = probe.result().get(4, TimeUnit.SECONDS);
            yes(result.captured());
            eq(200, result.imageWidth());
            eq(100, result.imageHeight());
            yes(result.bytes() > 0);
        }
        yes(ws.aborted.get() > 0);
    }

    private static void unsafeOrIncompleteBootstrapFailsClosed() throws Exception {
        { // A non-Firefox response must not be accepted as a Gecko session.
            FakeSocket wire = new FakeSocket();
            try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof("ctx-1", 100, 100)) {
                probe.onOpen(wire.socket);
                probe.onText(wire.socket, sessionReply(false), true);
                yes(probe.result().isCompletedExceptionally());
                yes(wire.aborted.get() > 0);
                eq(1, wire.sent.size());
            }
        }
        { // No implicit screenshot of the first unrelated tab.
            FakeSocket wire = new FakeSocket();
            try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof("missing-ctx", 100, 100)) {
                probe.onOpen(wire.socket);
                probe.onText(wire.socket, sessionReply(true), true);
                probe.onText(wire.socket, treeReply("ctx-root", "ctx-child"), true);
                yes(probe.result().isCompletedExceptionally());
                eq(2, wire.sent.size());
                yes(wire.aborted.get() > 0);
            }
        }
        { // Unexpected reply ID must not advance the bootstrap.
            FakeSocket wire = new FakeSocket();
            try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof(null, 0, 0)) {
                probe.onOpen(wire.socket);
                probe.onText(wire.socket, "{\"id\":2,\"type\":\"success\",\"result\":{}}", true);
                yes(probe.result().isCompletedExceptionally());
                eq(1, wire.sent.size());
            }
        }
        { // A second socket cannot take control of the original session.
            FakeSocket wire = new FakeSocket();
            FakeSocket foreign = new FakeSocket();
            try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof(null, 0, 0)) {
                probe.onOpen(wire.socket);
                probe.onOpen(foreign.socket);
                yes(foreign.aborted.get() > 0);
                probe.onText(wire.socket, sessionReply(true), true);
                probe.onText(wire.socket, treeReply("ctx-root", "ctx-child"), true);
                yes(probe.result().isDone());
            }
        }
    }

    private static String sessionReply(boolean firefox) {
        return "{\"id\":" + FirefoxBiDiLiveProof.SESSION_ID
                + ",\"type\":\"success\",\"result\":{\"sessionId\":\"session-test\","
                + "\"capabilities\":{\"browserName\":\"" + (firefox ? "firefox" : "chrome") + "\"}}}";
    }

    private static String treeReply(String root, String child) {
        return "{\"id\":" + FirefoxBiDiLiveProof.TREE_ID
                + ",\"type\":\"success\",\"result\":{\"contexts\":["
                + "{\"context\":\"" + root + "\",\"children\":[{\"context\":\"" + child
                + "\",\"children\":[]}]}]}}";
    }

    private static String screenshotReply(long id, byte[] image) {
        return "{\"type\":\"success\",\"id\":" + id + ",\"result\":{\"data\":\""
                + Base64.getEncoder().encodeToString(image) + "\"}}";
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff00aacc);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new AssertionError("PNG writer unavailable");
        return bytes.toByteArray();
    }

    private static final class FakeSocket implements InvocationHandler {
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger aborted = new AtomicInteger();
        final WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[] {WebSocket.class}, this);
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "request" -> null;
                case "sendText" -> { sent.add((String) args[0]); yield CompletableFuture.completedFuture(socket); }
                case "abort" -> { aborted.incrementAndGet(); yield null; }
                case "toString" -> "fake-firefox-websocket";
                case "hashCode" -> System.identityHashCode(this);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static void yes(boolean ok) {
        assertions++;
        if (!ok) throw new AssertionError("Firefox BiDi proof assertion #" + assertions + " failed");
    }
    private static void no(boolean bad) { yes(!bad); }
    private static void eq(long a, long b) { yes(a == b); }
    private static void eq(Object a, Object b) { yes(a == null ? b == null : a.equals(b)); }
}
