/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

/**
 * Small, deterministic CDP command envelopes. A host-owned authenticated WebSocket
 * is responsible for sending these and parsing response/events. No networking,
 * filesystem or browser startup occurs here. Input dispatch requires a trusted
 * host authorization gate; this class never grants one.
 */
public final class CdpScreencastCommands {
    private CdpScreencastCommands() { }

    /** Enable the CDP Page domain before starting a screencast. The caller's
     * ordered WebSocket writer must send this before Page.startScreencast. */
    public static String enablePage(long id) {
        if (id < 1) throw new IllegalArgumentException("invalid CDP command ID");
        return "{\"id\":" + id + ",\"method\":\"Page.enable\"}";
    }

    public static String start(long id, int width, int height, int quality) {
        if (id < 1 || width < 1 || height < 1 || width > RemoteFrameInbox.MAX_AXIS
                || height > RemoteFrameInbox.MAX_AXIS || quality < 0 || quality > 100) {
            throw new IllegalArgumentException("invalid screencast bounds");
        }
        return "{\"id\":" + id + ",\"method\":\"Page.startScreencast\",\"params\":{"
                + "\"format\":\"jpeg\",\"quality\":" + quality
                + ",\"maxWidth\":" + width + ",\"maxHeight\":" + height
                + ",\"everyNthFrame\":1,\"maxFramesInFlight\":1}}";
    }

    public static String acknowledge(long id, int ackId) {
        if (id < 1 || ackId < 0) throw new IllegalArgumentException("invalid CDP acknowledgement");
        return "{\"id\":" + id + ",\"method\":\"Page.screencastFrameAck\",\"params\":{\"sessionId\":"
                + ackId + "}}";
    }

    public static String stop(long id) {
        if (id < 1) throw new IllegalArgumentException("invalid command ID");
        return "{\"id\":" + id + ",\"method\":\"Page.stopScreencast\"}";
    }

    public static String pointer(long id, RemoteFrameInbox.CssPoint point,
            String type, String button, int buttons) {
        if (id < 1 || point == null || point.x() < 0 || point.y() < 0
                || buttons < 0 || buttons > 7
                || !("mousePressed".equals(type) || "mouseReleased".equals(type)
                    || "mouseMoved".equals(type))
                || !("left".equals(button) || "middle".equals(button)
                    || "right".equals(button) || "none".equals(button))) {
            throw new IllegalArgumentException("invalid CDP pointer command");
        }
        return "{\"id\":" + id + ",\"method\":\"Input.dispatchMouseEvent\",\"params\":{"
                + "\"type\":\"" + type + "\",\"x\":" + point.x() + ",\"y\":"
                + point.y() + ",\"button\":\"" + button + "\",\"buttons\":" + buttons + "}}";
    }
}
