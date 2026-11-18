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
