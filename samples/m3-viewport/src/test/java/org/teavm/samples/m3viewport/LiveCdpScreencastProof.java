/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Optional real-CDP smoke. Caller supplies an already-started page-level
 * Chromium endpoint bound to 127.0.0.1; no credentials, browser startup, CDP
 * discovery, input dispatch or SWT resource creation is performed here.
 * Passing this smoke proves image receipt, not native GC painting.
 */
public final class LiveCdpScreencastProof {
    private LiveCdpScreencastProof() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one loopback page WebSocket URL");
        URI uri = URI.create(args[0]);
        if (!"ws".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())) {
            throw new IllegalArgumentException("Loopback page WebSocket required; never expose the CDP debugger");
        }
        RemoteFrameInbox inbox = new RemoteFrameInbox();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch arrived = new CountDownLatch(1);
        CdpScreencastHost host = new CdpScreencastHost(inbox, 9, 700, 361, () -> {
            try {
                RemoteFrameInbox.Delivery delivery = inbox.take(700, 361);
                if (delivery == null) return;
                RemoteFrameInbox.Frame frame = delivery.frame();
                if (frame.epoch() != 9 || frame.revision() < 1 || frame.byteCount() <= 0
                        || frame.pixelWidth() > RemoteFrameInbox.MAX_AXIS) {
                    throw new AssertionError("Frame envelope rejected");
                }
                if (inbox.pointer(9, frame.revision(), 20, 20) != null) {
                    throw new AssertionError("Remote input enabled before authorized paint");
                }
                try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(
                        new ByteArrayInputStream(frame.compressed()))) {
                    Iterator<ImageReader> decoders = ImageIO.getImageReaders(input);
                    if (!decoders.hasNext()) throw new AssertionError("No decoder for live frame");
                    ImageReader decoder = decoders.next();
                    try {
                        decoder.setInput(input, true, true);
                        var image = decoder.read(0);
                        if (image == null || image.getWidth() != frame.pixelWidth()
                                || image.getHeight() != frame.pixelHeight()) {
                            throw new AssertionError("Full-image decode disagrees with header");
                        }
                    } finally {
                        decoder.dispose();
                    }
                }
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(frame.compressed()));
                System.out.println("LIVE_CHROMIUM_CDP_TO_JAVA_PASS width=" + frame.pixelWidth()
                        + " height=" + frame.pixelHeight() + " bytes=" + frame.byteCount()
                        + " revision=" + frame.revision() + " sha256=" + sha);
                // No call to displayed(): there is no actual SWT GC in this test.
                arrived.countDown();
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                arrived.countDown();
            }
        }, ex -> {
            failure.compareAndSet(null, ex);
            arrived.countDown();
        });
        try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(uri, host).get(10, TimeUnit.SECONDS);
            host.start(700, 361, 80);
            if (!arrived.await(20, TimeUnit.SECONDS)) throw new AssertionError("No screencast received");
            if (failure.get() != null) throw new AssertionError("CDP capture failed", failure.get());
            System.out.println("NO_PAINT_OR_REMOTE_INPUT_CLAIM_PASS");
        } finally {
            host.close();
        }
    }
}
