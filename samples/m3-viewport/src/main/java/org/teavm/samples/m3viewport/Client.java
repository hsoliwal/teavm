/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.html.HTMLButtonElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.TextRectangle;

/**
 * TeaVM browser-side projection of the portable M3 frame window. This is a
 * runnable Java->JS sample, not an embedded Firefox, VNC transport or terminal.
 * A real authenticated host must provide frame bytes and separate input approval.
 */
public final class Client {
    private static final int REMOTE_WIDTH = 1280;
    private static final int REMOTE_HEIGHT = 720;

    private final HTMLDocument document;
    private final FrameWindow frames = new FrameWindow();
    private HTMLElement status;
    private HTMLElement preview;
    private HTMLElement viewport;
    private HTMLElement pointerMessage;
    private HTMLElement localPane;
    private HTMLElement remotePane;
    private HTMLElement semanticPane;
    private long sequence;
    private int viewportWidth = 560;
    private int viewportHeight = 320;

    private Client(HTMLDocument document) {
        this.document = document;
    }

    public static void main(String[] args) {
        new Client(HTMLDocument.current()).start();
    }

    private void start() {
        HTMLElement root = document.getElementById("m3-root");
        if (root == null) {
            return;
        }
        root.clear();
        root.appendChild(document.createElement("h1").withText("M3 Java panel workbench"));
        root.appendChild(document.createElement("p").withText(
                "Java controls this UI. SWT, JNI, remote browser and network privileges stay with their own hosts."));

        HTMLElement commands = panel("controls");
        root.appendChild(commands);
        command(commands, "Java panel", () -> select("local"));
        command(commands, "Remote frame", () -> select("remote"));
        command(commands, "Semantic overlay", () -> select("semantic"));

        localPane = panel("panel");
        localPane.appendChild(document.createElement("h2").withText("Headless Java logic → browser DOM"));
        localPane.appendChild(document.createElement("p").withText(
                "This panel uses TeaVM JSO elements; the same underlying FrameWindow compiles as ordinary Java 21."));
        root.appendChild(localPane);

        remotePane = panel("panel");
        remotePane.appendChild(document.createElement("h2").withText("Remote-browser viewport"));
        remotePane.appendChild(document.createElement("p").withText(
                "Transport disconnected — no RFB, remote browser or screenshot stream is started by this sample."));
        HTMLElement sizing = panel("controls");
        command(sizing, "Landscape", () -> resize(560, 320));
        command(sizing, "Portrait", () -> resize(320, 560));
        command(sizing, "Sample damage", this::nextDamage);
        remotePane.appendChild(sizing);
        viewport = panel("viewport");
        remotePane.appendChild(viewport);
        preview = panel("remote-content");
        preview.appendChild(document.createElement("span").withText("No frame source connected"));
        viewport.appendChild(preview);
        pointerMessage = document.createElement("p").withText("Remote input disabled");
        remotePane.appendChild(pointerMessage);
        status = document.createElement("pre");
        remotePane.appendChild(status);
        viewport.onClick(this::inspectPointer);
        root.appendChild(remotePane);

        semanticPane = panel("panel");
        semanticPane.appendChild(document.createElement("h2").withText("Semantic side channel"));
        semanticPane.appendChild(document.createElement("p").withText(
                "A future authenticated DOM/accessibility channel can populate link and focus IDs here. "
                        + "Do not infer link targets, credentials or click permissions from pixels alone."));
        root.appendChild(semanticPane);
        select("local");
        resize(viewportWidth, viewportHeight);
    }

    private HTMLElement panel(String cssClass) {
        HTMLElement element = document.createElement("section");
        element.setClassName(cssClass);
        return element;
    }

    private void command(HTMLElement parent, String title, Runnable action) {
        HTMLButtonElement button = document.createElement("button").cast();
        button.withText(title);
        button.onClick(event -> action.run());
        parent.appendChild(button);
    }

    private void select(String mode) {
        localPane.setHidden(!"local".equals(mode));
        remotePane.setHidden(!"remote".equals(mode));
        semanticPane.setHidden(!"semantic".equals(mode));
    }

    private void resize(int width, int height) {
        viewportWidth = width;
        viewportHeight = height;
        viewport.setAttribute("style", "width:" + width + "px;height:" + height + "px");
        // A geometry change must invalidate all pixels, even if the host supplied
        // an empty delta. Revision belongs to the panel session, not the DOM.
        display(frames.publish(1, ++sequence, REMOTE_WIDTH, REMOTE_HEIGHT, width, height, new int[0]));
    }

    private void nextDamage() {
        // Illustrative geometry only. There is no fabricated remote page or input authority.
        int x = (int) (sequence * 37 % (REMOTE_WIDTH - 80));
        int y = (int) (sequence * 17 % (REMOTE_HEIGHT - 80));
        display(frames.publish(1, ++sequence, REMOTE_WIDTH, REMOTE_HEIGHT,
                viewportWidth, viewportHeight, new int[] { x, y, 80, 80 }));
    }

    private void display(FrameWindow.Plan plan) {
        if (plan == null) {
            return;
        }
        FrameWindow.Rect rect = plan.content();
        preview.setAttribute("style", "left:" + rect.x() + "px;top:" + rect.y()
                + "px;width:" + rect.width() + "px;height:" + rect.height() + "px");
        status.withText("Session " + plan.session() + ", revision " + plan.revision()
                + ", full=" + plan.full() + ", projected damage rectangles=" + plan.rectangleCount()
                + ", content [" + rect.x() + "," + rect.y() + "," + rect.width()
                + "," + rect.height() + "]");
        pointerMessage.withText("Remote input disabled; click to inspect local coordinate mapping");
    }

    private void inspectPointer(MouseEvent event) {
        TextRectangle bounds = viewport.getBoundingClientRect();
        int x = event.getClientX() - bounds.getLeft();
        int y = event.getClientY() - bounds.getTop();
        int[] point = frames.pointer(1, sequence, x, y);
        pointerMessage.withText(point == null ? "Outside remote content — no remote action"
                : "Mapped to remote pixel " + point[0] + "," + point[1]
                        + " — input still disabled until authorized by the host");
    }
}
