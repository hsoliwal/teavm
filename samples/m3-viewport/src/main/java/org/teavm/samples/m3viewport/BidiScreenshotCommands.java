/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

/**
 * Deterministic WebDriver BiDi viewport screenshot commands. This pure Java
 * contract is TeaVM JS/Wasm-compatible but grants no debugger connection,
 * browser startup, credentials, JavaScript evaluation or input permission.
 *
 * <p>Unlike CDP Page.screencastFrame, BiDi captureScreenshot is an explicit
 * request/response snapshot; clients must NOT mistake it for a push stream or
 * reuse a CDP frame-ack ID.</p>
 */
public final class BidiScreenshotCommands {
    private BidiScreenshotCommands() { }

    /**
     * Capture the visible viewport only. Contexts are browser-minted opaque IDs;
     * this deliberately accepts ASCII identifier tokens, not arbitrary scripts,
     * URLs, Unicode escapes or caller-constructed JSON fragments.
     *
     * @param quality an integer percentage [0..100], used only for JPEG
     */
    public static String capture(long id, String context, RemoteFrameInbox.Format format, int quality) {
        if (id < 1 || format == null || quality < 0 || quality > 100 || !validContext(context)) {
            throw new IllegalArgumentException("invalid BiDi screenshot request");
        }
        String mime = format == RemoteFrameInbox.Format.PNG ? "image/png" : "image/jpeg";
        String options = format == RemoteFrameInbox.Format.JPEG
                ? ",\"quality\":" + (quality / 100.0d) : "";
        return "{\"id\":" + id + ",\"method\":\"browsingContext.captureScreenshot\",\"params\":{"
                + "\"context\":\"" + context + "\",\"origin\":\"viewport\",\"format\":{\"type\":\""
                + mime + "\"" + options + "}}}";
    }

    /** Avoid JSON injection and unbounded identifiers at the remote protocol boundary. */
    private static boolean validContext(String context) {
        if (context == null || context.isEmpty() || context.length() > 128) return false;
        for (int i = 0; i < context.length(); i++) {
            char c = context.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == ':')) {
                return false;
            }
        }
        return true;
    }
}
