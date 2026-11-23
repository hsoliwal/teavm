/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Explicit, JVM-only Firefox/WebDriver BiDi loopback acceptance candidate.
 * No Firefox startup, endpoint discovery, navigation, script, input, clipboard,
 * authentication bypass, SWT object, browser-compiled entrypoint or network
 * connection occurs before the operator invokes {@link #main(String[])}.
 *
 * <p>Direct Firefox BiDi requires session.new then browsingContext.getTree.
 * The caller explicitly chooses a browser-minted context ID from the list
 * before any screenshot request. Only the test-owned 127.0.0.1 /session
 * endpoint is accepted. Loopback alone is NOT an authentication guarantee.
 * No frame is marked displayed; no remote input is authorized.</p>
 */
public final class FirefoxBiDiLiveProof implements WebSocket.Listener, AutoCloseable {
    static final long SESSION_ID = 1_000_000_001L;
    static final long TREE_ID = 1_000_000_002L;
    private static final int MAX_CONTEXTS = 128;
    private static final int MAX_ID_LENGTH = 128;

    private enum Phase { NEW, SESSION, TREE, FRAME, COMPLETE, CLOSED }

    public record Result(boolean captured, List<String> contextIds, int imageWidth, int imageHeight, int bytes) { }

    private final String selectedContext;
    private final int cssWidth;
    private final int cssHeight;
    private final RemoteFrameInbox inbox = new RemoteFrameInbox();
    private final CompletableFuture<Result> result = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();
    private volatile WebSocket socket;
    private BidiScreenshotHost screenshot;
    private Phase phase = Phase.NEW;
    private List<String> contexts = List.of();

    /** List mode (selectedContext null) never captures a screenshot. */
    FirefoxBiDiLiveProof(String selectedContext, int cssWidth, int cssHeight) {
        if (selectedContext != null) {
            // The command builder implements the same bounded context-ID admission.
            BidiScreenshotCommands.capture(1, selectedContext, RemoteFrameInbox.Format.PNG, 100);
            if (cssWidth < 1 || cssHeight < 1 || cssWidth > FrameWindow.MAX_AXIS
                    || cssHeight > FrameWindow.MAX_AXIS) {
                throw new IllegalArgumentException("invalid explicit CSS viewport");
            }
        }
        this.selectedContext = selectedContext;
        this.cssWidth = cssWidth;
        this.cssHeight = cssHeight;
    }

    /** This is a test acceptance surface, never a production network host. */
    public static void main(String[] args) throws Exception {
        if (!(args.length == 2 && "list".equals(args[1])
                || args.length == 5 && "capture".equals(args[1]))) {
            throw new IllegalArgumentException("Usage: FirefoxBiDiLiveProof ws://127.0.0.1:PORT/session list"
                    + " OR ... capture CONTEXT_ID CSS_WIDTH CSS_HEIGHT");
        }
        URI endpoint = requireLoopbackSession(args[0]);
        String selected = args.length == 5 ? args[2] : null;
        int width = selected == null ? 0 : Integer.parseInt(args[3]);
        int height = selected == null ? 0 : Integer.parseInt(args[4]);
        try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof(selected, width, height)) {
            HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(endpoint, probe).get(12, TimeUnit.SECONDS);
            Result checked = probe.result.get(30, TimeUnit.SECONDS);
            if (checked.captured()) {
                System.out.println("FIREFOX_BIDI_JAVA_CAPTURE_PASS width=" + checked.imageWidth()
                        + " height=" + checked.imageHeight() + " bytes=" + checked.bytes()
                        + " remoteInput=false nativePaint=false");
            } else {
                System.out.println("FIREFOX_BIDI_CONTEXTS_READY count=" + checked.contextIds().size());
                // The user chooses a browser-minted context explicitly for a second invocation.
                for (String context : checked.contextIds()) System.out.println("context=" + context);
            }
        }
    }

    /** Refuse credentials, remote hosts, query fragments and browser-target URLs. */
    static URI requireLoopbackSession(String supplied) {
        URI uri = URI.create(Objects.requireNonNull(supplied, "endpoint"));
        if (!"ws".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535 || !"/session".equals(uri.getPath())
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Firefox BiDi test requires ws://127.0.0.1:PORT/session");
        }
        return uri;
    }

    @Override
    public synchronized void onOpen(WebSocket webSocket) {
        Objects.requireNonNull(webSocket, "webSocket");
        if (phase != Phase.NEW) { webSocket.abort(); return; }
        socket = webSocket;
        phase = Phase.SESSION;
        send("{\"id\":" + SESSION_ID + ",\"method\":\"session.new\",\"params\":{\"capabilities\":{}}}");
        webSocket.request(1);
    }

    @Override
    public synchronized CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (webSocket != socket || phase == Phase.CLOSED) {
            if (webSocket != null && webSocket != socket) webSocket.abort();
            return CompletableFuture.completedFuture(null);
        }
        if (phase == Phase.FRAME) {
            // The already-qualified host owns screenshot response buffering,
            // request-id checks and bounded image decoding from here on.
            return screenshot.onText(webSocket, data, last);
        }
        if (phase == Phase.COMPLETE) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        if ((long) fragments.length() + data.length() > CdpFrameJson.MAX_TEXT) {
            fail(new IOException("oversized Firefox BiDi bootstrap reply"));
            return CompletableFuture.completedFuture(null);
        }
        fragments.append(data);
        if (last) {
            String json = fragments.toString();
            fragments.setLength(0);
            try { consumeBootstrap(CdpFrameJson.object(json)); }
            catch (IOException | RuntimeException bad) { fail(bad); }
        }
        if (phase != Phase.FRAME && phase != Phase.CLOSED) webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    private void consumeBootstrap(Map<?, ?> reply) throws IOException {
        if (!(reply.get("id") instanceof Long id)) return; // ignore unsolicited browser events
        if (!"success".equals(reply.get("type"))) throw new IOException("Firefox BiDi bootstrap rejected");
        if (!(reply.get("result") instanceof Map<?, ?> data)) {
            throw new IOException("missing Firefox BiDi result");
        }
        if (phase == Phase.SESSION && id == SESSION_ID) {
            if (!(data.get("sessionId") instanceof String sessionId) || sessionId.isEmpty()
                    || sessionId.length() > MAX_ID_LENGTH
                    || !(data.get("capabilities") instanceof Map<?, ?> caps)
                    || !(caps.get("browserName") instanceof String browser)
                    || !"firefox".equalsIgnoreCase(browser)) {
                throw new IOException("not a confirmed Firefox BiDi session");
            }
            phase = Phase.TREE;
            send("{\"id\":" + TREE_ID + ",\"method\":\"browsingContext.getTree\",\"params\":{}}");
        } else if (phase == Phase.TREE && id == TREE_ID) {
            contexts = parseContextIds(data.get("contexts"));
            if (contexts.isEmpty()) throw new IOException("Firefox returned no browsing contexts");
            if (selectedContext == null) {
                phase = Phase.COMPLETE;
                result.complete(new Result(false, contexts, 0, 0, 0));
                return;
            }
            if (!contexts.contains(selectedContext)) {
                throw new IOException("Explicit browser-minted context not found in session");
            }
            screenshot = new BidiScreenshotHost(inbox, 1, selectedContext, cssWidth, cssHeight,
                    RemoteFrameInbox.Format.PNG, 100, this::completeScreenshot, this::fail);
            phase = Phase.FRAME;
            screenshot.onOpen(socket);
            if (!screenshot.capture()) throw new IOException("Firefox capture request not admitted");
        } else {
            throw new IOException("unexpected Firefox BiDi bootstrap response ID");
        }
    }

    /** Resolve nested browsing contexts without recursion or unbounded allocation. */
    private static List<String> parseContextIds(Object raw) throws IOException {
        if (!(raw instanceof List<?> roots)) throw new IOException("invalid Firefox browsing context tree");
        ArrayDeque<Object> queue = new ArrayDeque<>(roots);
        ArrayList<String> ids = new ArrayList<>();
        while (!queue.isEmpty()) {
            if (ids.size() >= MAX_CONTEXTS) throw new IOException("too many Firefox browsing contexts");
            if (!(queue.removeFirst() instanceof Map<?, ?> node)
                    || !(node.get("context") instanceof String id)) {
                throw new IOException("invalid Firefox context descriptor");
            }
            // Validate against command-injection and unreasonable identifiers.
            try { BidiScreenshotCommands.capture(1, id, RemoteFrameInbox.Format.PNG, 100); }
            catch (IllegalArgumentException bad) { throw new IOException("invalid context ID", bad); }
            if (ids.contains(id)) throw new IOException("repeated Firefox context ID");
            ids.add(id);
            Object children = node.get("children");
            if (children != null) {
                if (!(children instanceof List<?> descendants) || descendants.size() > MAX_CONTEXTS
                        || (long) queue.size() + descendants.size() > MAX_CONTEXTS) {
                    throw new IOException("invalid Firefox context descendants");
                }
                queue.addAll(descendants);
            }
        }
        return List.copyOf(ids);
    }

    private void completeScreenshot() {
        try {
            RemoteFrameInbox.Delivery delivery = inbox.take(cssWidth, cssHeight);
            if (delivery == null) throw new IOException("no screenshot delivery");
            RemoteFrameInbox.Frame frame = delivery.frame();
            if (frame.epoch() != 1 || frame.revision() != 1 || frame.format() != RemoteFrameInbox.Format.PNG
                    || frame.byteCount() > RemoteFrameInbox.MAX_COMPRESSED_BYTES
                    || inbox.pointer(1, 1, 0, 0) != null) {
                throw new IOException("unexpected or remotely authorized screenshot");
            }
            // Independently decode the exact bytes in memory. Header-only ImageIO
            // checks are not enough to prove that a PNG has decodable pixels.
            try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(
                    new ByteArrayInputStream(frame.compressed()))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException("no PNG reader");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    var pixels = reader.read(0);
                    if (pixels == null || pixels.getWidth() != frame.pixelWidth()
                            || pixels.getHeight() != frame.pixelHeight()) {
                        throw new IOException("PNG full decode/header mismatch");
                    }
                } finally { reader.dispose(); }
            }
            // Intentionally NO displayed(delivery): this is not an SWT paint.
            synchronized (this) { phase = Phase.COMPLETE; }
            result.complete(new Result(true, contexts, frame.pixelWidth(), frame.pixelHeight(),
                    frame.byteCount()));
        } catch (IOException | RuntimeException failure) {
            fail(failure);
        }
    }

    private synchronized void send(String json) {
        WebSocket current = socket;
        if (current == null || phase == Phase.CLOSED) throw new IllegalStateException("no Firefox BiDi socket");
        current.sendText(json, true).whenComplete((ignored, error) -> {
            if (error != null) fail(new IOException("Firefox BiDi command send rejected", error));
        });
    }

    private synchronized void fail(Throwable error) {
        if (phase == Phase.CLOSED || phase == Phase.COMPLETE) return;
        phase = Phase.CLOSED;
        result.completeExceptionally(error);
        if (screenshot != null) screenshot.close();
        else inbox.close();
        if (socket != null) socket.abort();
    }

    @Override
    public synchronized CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (webSocket == socket && phase != Phase.COMPLETE) fail(new IOException("Firefox BiDi disconnected"));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized void onError(WebSocket webSocket, Throwable error) {
        if (webSocket == socket) fail(error);
    }

    public CompletableFuture<Result> result() { return result; }

    @Override
    public synchronized void close() {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        if (screenshot != null) screenshot.close();
        else inbox.close();
        if (socket != null) socket.abort();
        if (!result.isDone()) result.completeExceptionally(new IOException("Firefox BiDi probe closed"));
    }
}
