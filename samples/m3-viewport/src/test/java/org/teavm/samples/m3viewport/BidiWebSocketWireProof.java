/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * A real JDK WebSocket transport test for the Firefox BiDi session state machine.
 * The server is a tightly scoped, deterministic local fixture, NOT Firefox or a
 * general BiDi endpoint. It never starts a browser, connects externally or
 * forwards any input, credentials, clipboard or JavaScript.
 */
public final class BidiWebSocketWireProof {
    private BidiWebSocketWireProof() { }

    private static final AtomicInteger CHECKS = new AtomicInteger();
    private enum Scenario { LIST, CAPTURE, WRONG_SESSION, WRONG_CONTEXT, STALE_ID, MALFORMED_IMAGE }

    public static void main(String[] args) throws Exception {
        run(Scenario.LIST);
        run(Scenario.CAPTURE);
        run(Scenario.WRONG_SESSION);
        run(Scenario.WRONG_CONTEXT);
        run(Scenario.STALE_ID);
        run(Scenario.MALFORMED_IMAGE);
        System.out.println("M3_FIREFOX_BIDI_LOOPBACK_WIRE_PASS scenarios=6 checks=" + CHECKS.get());
    }

    private static void run(Scenario scenario) throws Exception {
        byte[] png = image();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            Thread worker = Thread.ofPlatform().daemon().name("bidi-test-loopback").start(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(8000);
                    handshake(client);
                    String session = readText(client.getInputStream());
                    yes(session.contains("\"method\":\"session.new\""));
                    String firefox = "{\"id\":1000000001,\"type\":\"success\",\"result\":{\"sessionId\":\"fixture\",\"capabilities\":{\"browserName\":\""
                            + (scenario == Scenario.WRONG_SESSION ? "chrome" : "firefox") + "\"}}}";
                    sendText(client.getOutputStream(), firefox, false);
                    if (scenario == Scenario.WRONG_SESSION) { release.await(8, TimeUnit.SECONDS); return; }
                    String tree = readText(client.getInputStream());
                    yes(tree.contains("\"method\":\"browsingContext.getTree\""));
                    sendText(client.getOutputStream(), "{\"id\":1000000002,\"type\":\"success\",\"result\":{\"contexts\":[{\"context\":\"fixture-tab\",\"children\":[]}]}}", true);
                    if (scenario == Scenario.LIST || scenario == Scenario.WRONG_CONTEXT) {
                        release.await(8, TimeUnit.SECONDS); return;
                    }
                    String capture = readText(client.getInputStream());
                    yes(capture.contains("\"method\":\"browsingContext.captureScreenshot\""));
                    yes(capture.contains("\"context\":\"fixture-tab\""));
                    yes(capture.contains("\"origin\":\"viewport\""));
                    if (scenario == Scenario.STALE_ID) {
                        sendText(client.getOutputStream(), screenshot(99, png), true);
                    }
                    String frame = scenario == Scenario.MALFORMED_IMAGE
                            ? "{\"id\":1,\"type\":\"success\",\"result\":{\"data\":\"!\"}}"
                            : screenshot(1, png);
                    sendText(client.getOutputStream(), frame, true);
                    release.await(8, TimeUnit.SECONDS);
                } catch (Exception | AssertionError error) {
                    serverFailure.compareAndSet(null, error);
                }
            });
            URI endpoint = URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/session");
            String selected = switch (scenario) {
                case LIST, WRONG_SESSION -> null;
                case WRONG_CONTEXT -> "missing-context";
                default -> "fixture-tab";
            };
            try (FirefoxBiDiLiveProof probe = new FirefoxBiDiLiveProof(selected, 200, 100)) {
                WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                        .buildAsync(endpoint, probe).get(8, TimeUnit.SECONDS);
                yes(ws != null);
                if (scenario == Scenario.WRONG_SESSION || scenario == Scenario.WRONG_CONTEXT
                        || scenario == Scenario.MALFORMED_IMAGE) {
                    try {
                        probe.result().get(8, TimeUnit.SECONDS);
                        throw new AssertionError("Invalid BiDi session/capture unexpectedly admitted");
                    } catch (java.util.concurrent.ExecutionException expected) {
                        yes(expected.getCause() instanceof IOException);
                    }
                } else {
                    FirefoxBiDiLiveProof.Result response = probe.result().get(8, TimeUnit.SECONDS);
                    yes(response.contextIds().contains("fixture-tab"));
                    yes(response.captured() == (scenario != Scenario.LIST));
                    if (scenario != Scenario.LIST) {
                        yes(response.imageWidth() == 200 && response.imageHeight() == 100);
                        yes(response.bytes() == png.length);
                    }
                }
            }
            release.countDown();
            worker.join(8000);
            yes(!worker.isAlive());
            if (serverFailure.get() != null) throw new AssertionError("Wire fixture failure", serverFailure.get());
        }
    }

    private static void handshake(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int match = 0;
        while (header.size() < 8192 && match < 4) {
            int value = in.read();
            if (value < 0) throw new IOException("WebSocket handshake ended before headers");
            header.write(value);
            match = switch (match) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                default -> value == '\n' ? 4 : 0;
            };
        }
        if (match != 4) throw new IOException("Oversized WebSocket handshake");
        String raw = header.toString(StandardCharsets.US_ASCII);
        if (!raw.startsWith("GET /session HTTP/1.1\r\n")) throw new IOException("Wrong test WS path");
        String key = null;
        for (String line : raw.split("\r\n")) {
            if (line.regionMatches(true, 0, "Sec-WebSocket-Key: ", 0, 19)) key = line.substring(19).strip();
        }
        if (key == null) throw new IOException("Missing WebSocket key");
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                    .getBytes(StandardCharsets.US_ASCII));
            String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(digest)
                    + "\r\n\r\n";
            client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String readText(InputStream in) throws IOException {
        int first = readByte(in), second = readByte(in);
        if ((first & 15) != 1 || (second & 128) == 0) throw new IOException("Expected masked client text frame");
        long n = second & 127;
        if (n == 126) n = ((long) readByte(in) << 8) | readByte(in);
        else if (n == 127) {
            n = 0;
            for (int i = 0; i < 8; i++) n = (n << 8) | readByte(in);
        }
        if (n < 0 || n > 8192) throw new IOException("Oversized test request");
        byte[] mask = in.readNBytes(4);
        if (mask.length != 4) throw new IOException("Truncated client mask");
        byte[] payload = in.readNBytes((int) n);
        if (payload.length != n) throw new IOException("Truncated client frame");
        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void sendText(OutputStream out, String text, boolean split) throws IOException {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (split && encoded.length > 20) {
            int half = encoded.length / 2;
            sendFrame(out, 1, false, Arrays.copyOfRange(encoded, 0, half));
            sendFrame(out, 0, true, Arrays.copyOfRange(encoded, half, encoded.length));
        } else sendFrame(out, 1, true, encoded);
    }

    private static void sendFrame(OutputStream out, int opcode, boolean fin, byte[] data) throws IOException {
        out.write((fin ? 0x80 : 0) | opcode);
        if (data.length <= 125) out.write(data.length);
        else if (data.length <= 65535) {
            out.write(126); out.write((data.length >> 8) & 255); out.write(data.length & 255);
        } else throw new IOException("Fixture response too large");
        out.write(data);
        out.flush();
    }

    private static String screenshot(long id, byte[] bytes) {
        return "{\"id\":" + id + ",\"type\":\"success\",\"result\":{\"data\":\""
                + Base64.getEncoder().encodeToString(bytes) + "\"}}";
    }

    private static byte[] image() throws IOException {
        BufferedImage bitmap = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        bitmap.setRGB(0, 0, 0xff22aa99);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(bitmap, "png", out)) throw new IOException("PNG unavailable");
        return out.toByteArray();
    }

    private static int readByte(InputStream input) throws IOException {
        int ch = input.read();
        if (ch == -1) throw new IOException("Truncated WebSocket input");
        return ch;
    }

    private static void yes(boolean value) { int index = CHECKS.incrementAndGet(); if (!value) throw new AssertionError("BiDi wire check #" + index); }
}
