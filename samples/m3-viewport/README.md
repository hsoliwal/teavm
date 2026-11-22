<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 Java/TeaVM portable panel and remote-browser viewport sample

This is an **additive TeaVM sample**, not a new compiler/runtime, browser engine,
RFB implementation, SWT control, host authentication system, or M3JDK runtime
owner. It demonstrates that one **headless Java 21** frame geometry/dirty-region
contract is suitable for JVM, TeaVM JavaScript and Wasm GC code generation.

The browser-side `Client` creates real HTML panels through TeaVM JSO. It displays
publication metadata and tests mapping of mouse coordinates in letterboxed
content. The image surface deliberately says **No frame source connected**.
It does **not** fabricate a Firefox or Chromium page, start a network socket,
receive a remote frame, or transmit mouse/keyboard input.

## Build

The TeaVM repository has an independently built `samples` Gradle project.
First build/publish TeaVM locally as described in the root README, then:

```sh
cd samples
./gradlew :m3-viewport:m3ViewportProof
./gradlew :m3-viewport:generateJavaScript :m3-viewport:generateWasmGC
./gradlew :m3-viewport:war
```

The exact generated task names and asset staging must be confirmed against the
fork's TeaVM 0.18-SNAPSHOT Gradle API. Open the generated WAR via a static
server. `index.html` expects the `js/m3-viewport.js` artifact, following
upstream TeaVM's other samples. TeaVM browser execution and Wasm GC
compatibility are separate release gates, not implied by JVM compilation.

## Shared M3 contract

`FrameWindow` is intentionally dependency-free:

* UI viewport and remote-frame sizes, integer aspect-fit geometry.
* Session/revision monotonicity; stale frame and stale input rejection.
* Input in black letterbox margins is rejected rather than clamped.
* Source dirty rectangles are clipped, projected outward to view pixels and
  conservatively merged. Up to 64 regions are retained; excessive or broad
  damage becomes one full-view rectangle. `dirty=null` means unknown/full.
* Negative sizes and malformed tuples fail before any receiver state changes.
* Published plans are immutable and defensive-copy the packed damage array.
* Geometry and dirty math use `long` for products; no per-entry object graphs,
  native handles, SWT dependencies or renderer thread assumptions.

The source/target interpolation contract is **nearest-neighbor pixel coverage**.
For bilinear or larger-filter display scaling, the embedding renderer must
expand damage by the filter footprint or repaint the full view. Neither
RFB `CopyRect` nor browser compositor movement can be inferred by the protocol;
apply complete ordered source mutations before publishing a frame.

### Real product mapping

* **JVM desktop:** existing Nebula `DrawnBrowser`, `VncViewer`, Eclipse Terminal
  and SWT `LayeredViewport`; only SWT UI thread creates/disposes native images.
* **Browser:** existing Synexia TeaVM Java workbench and deferred-module host
  own the compiled client; authenticated pixel transport is a separate host API.
* **Remote engine:** Firefox/Chromium (or an RFB server) performs browser
  rendering, JS execution, networking, origin checks, fonts and input processing.
* **Semantic plane:** optional explicitly authorized DOM/accessibility snapshot
  provides text, link IDs, focus order and selection; never derive credentials
  or click permission from pixels alone.
* **Interop:** do not transplant JNI or SWT APIs to TeaVM; keep `*.core` headless
  and select platform-specific `*.bridge.eclipse/native/teavm` adapters.

## Testing and limits

`FrameWindowProof` uses randomized independent destination->source sampling,
invalid inputs, stale epochs, exact large-integer boundaries, letterbox input,
immutable outputs, merging and full-redraw fallback. Run it under Java 21 with
`-Xlint:all -Werror` if Gradle is unavailable. Add TeaVM JS/Wasm GC
cross-runtime vector comparison and actual browser screenshot tests before
claiming runtime parity.

No third-party source is copied. TeaVM and this sample remain Apache-2.0.
Upstream TeaVM `samples/hello` and `samples/kotlin` provide the Gradle/JSO
build patterns. The canonical M3 compiler bridge in `hsoliwal/com.synexia` is
already implemented by PRs #2953, #2965, #3809 and #3851; **do not duplicate it**.

## Bounded CDP remote-browser transport (second slice)

This sample now includes `RemoteFrameInbox` (portable JVM/TeaVM Java) and
`CdpScreencastCommands` (validated command envelopes). These additions are **not** a
new JavaScript VM, a full browser, a socket server, or an RFB protocol clone.
They consume and publish compressed *full-image* browser frames. No unsupported
pixel-damage optimization is claimed for CDP screenshots.

The **JVM-only** reference adapter lives under `src/test/java`:
`CdpFrameJson` parses bounded CDP event JSON, `CdpScreencastHost` is a
`java.net.http.WebSocket.Listener`, and `CdpScreencastHostProof` uses a fake
WebSocket and real JDK PNG encoding/headers. This adapter is deliberately
excluded from TeaVM JavaScript/Wasm targets because `java.net.http`, `ImageIO`,
network authorisation and native image decoding belong on the host JVM.
Applications should port the reviewed adapter into an existing authenticated
Synexia workbench host, rather than link test-only classes directly.

### Protocol mapping

* Chromium: `Page.startScreencast` with JPEG quality/max-size and a
  `maxFramesInFlight=1` backpressure request; event
  `Page.screencastFrame` carries compressed Base64 and **protocol**
  `sessionId`, which must be echoed in `Page.screencastFrameAck` after bounded
  intake, even if a stale/replaced frame is not displayed. The M3 **epoch** and
  **revision** are unrelated to CDP's `sessionId`.
* CDP `Input.dispatchMouseEvent` coordinates are **CSS viewport pixels**.
  The host must establish the CSS viewport size independently from a trusted
  browser session, and restart/reconfigure upon resize. A screenshot's pixel
  dimensions must be decoded/validated separately from reported metadata.
* Firefox WebDriver BiDi `browsingContext.captureScreenshot` is a one-shot
  command rather than this Chromium screencast event. A Firefox host adapter
  can feed its decoded PNG/JPEG into `RemoteFrameInbox` under its own monotonically
  increasing revisions. Do not send Chromium-specific acknowledgements to BiDi.
* RFB/VNC continues to use Nebula's existing framebuffer and damage rectangles,
  not these full PNG/JPEG messages. TeaVM and SWT can share `FrameWindow` geometry
  without duplicating RFB authentication or protocol parsing.

### Safety and data ownership

* **No implicit remote input**. Only a trusted application, after appropriate
  authorization, may call `RemoteFrameInbox.setInputAuthorized(true)`. The inbox
  denies stale/unpainted frames, letterbox margins and all input after reconnect
  unless reauthorized. The JSO browser demo does not grant input authorization.
* Host-supplied `WebSocket` must already target an authenticated, policy-approved
  Chromium debugging connection (prefer an isolated loopback session / secured
  tunnel). No debugging endpoint is discovered, browser launched, or socket
  opened by these classes. CDP debugging exposes powerful browser privileges;
  never forward its raw WebSocket endpoint to a public TeaVM web page.
* One latest compressed frame is retained in the inbox, capped at 8 MiB.
  JVM-side WebSocket messages are capped at 12 million characters, with a
  two-entry worker queue and strict JSON nesting/member limits. JPEG/PNG header
  dimensions and a 16M-pixel limit are verified before admission. An SWT/browser
  renderer must **also** validate the decoded image and release native resources
  on its own UI thread. The parser and decoder never run on SWT's event thread.
* The UI calls `take(viewWidth, viewHeight)` and then `displayed(delivery)` only
  after successfully painting that precise frame. `pointer(epoch, revision, x, y)`
  refuses newer/unshown revisions even if an older image remains visible.
  Network event ACK is deliberately separate from native paint completion.
* CDP full snapshots always trigger full-image redraw. For later dirty-tile
  streaming, compare bounded decoded tiles on the CPU and deliver exact changed
  regions through the existing Nebula/SWT `ViewportDamage` paths; never infer
  damage from compressed byte differences.

### Reproduce Java 21 validation

Run the original JVM proof and the new portable/CDP reference-host proofs:

```sh
cd samples
./gradlew :m3-viewport:m3ViewportProof :m3-viewport:m3CdpHostProof
```

The `m3CdpHostProof` task runs deterministic input, queue, session,
malformed JSON, real ImageIO PNG, command serialization, and randomized mapping
checks. An isolated JDK 21 `javac --release 21 -Xlint:all -Werror` execution
of the same classes is part of the source packet. Browser JS/Wasm codegen,
real CDP session interoperability and GTK/Win32/Cocoa SWT painting remain
**NOT_RUN** until their own integration and acceptance tests are executed.

## Live local Chromium screencast proof (optional, explicit)

The Java 21 JVM-only reference listener now sends `Page.enable` before
`Page.startScreencast` on its serialized host-owned WebSocket. This is
required by some fresh headless Chromium sessions; the earlier direct
start-only path connected successfully but produced no frame in the
reproduced local test. Image header inspection uses a
`MemoryCacheImageInputStream` and never opts into ImageIO disk caching.

The opt-in `LiveCdpScreencastProof` accepts **only a loopback page-level**
`ws://127.0.0.1:PORT/devtools/page/...` URL supplied by the operator.
Launch an isolated, locally trusted Chromium profile with a loopback-only
debugging endpoint, then run it with the exact selected Java 21 sources:
`org.teavm.samples.m3viewport.LiveCdpScreencastProof <page-websocket-url>`.
It neither launches Chromium nor discovers a debugging URL, and does not
send mouse, keyboard, clipboard, credentials or JavaScript to the page.

Verified on an isolated local Chromium (November 22, 2026): a previously
failing start-only capture emitted one JPEG after ordered `Page.enable`,
decoded to 700 x 361 pixels. This is CDP/Java frame acceptance only:
no browser JavaScript/Wasm output, native SWT GC paint, OS compositor scanout,
or Firefox BiDi acceptance is claimed. Production hosts must not expose
CDP remote debugging to untrusted networks; explicit trusted local or
tunnel-protected transport and session authorization remain mandatory.
