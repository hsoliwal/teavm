<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 remote-browser viewport: WebDriver BiDi screenshot receiver

This is an additive **JVM-only screenshot protocol reference** that feeds the
existing `RemoteFrameInbox` shared by TeaVM's Java viewport and Nebula's
remote-frame presenter. It does not embed Firefox, use SWT, run JavaScript,
start a browser, discover a debugging URL, or create network connections.

## Contract

The Java-only `BidiScreenshotCommands.capture(id, context, format, quality)`
builds an explicit WebDriver BiDi `browsingContext.captureScreenshot` request
with `origin: "viewport"`; `format` is PNG or JPEG (JPEG `quality` is 0..1).

The JVM-only `BidiScreenshotHost` implements `java.net.http.WebSocket.Listener`.
The host application supplies an already authenticated, origin-restricted,
caller-approved WebSocket and a browser-minted browsing-context ID. `onOpen`
never captures anything. A caller calls `capture()` explicitly, at most once
until the matching result is processed. Responses carry `type: "success"`,
`id: <request id>` and `result.data: <base64 screenshot>`; unrelated events and
stale response IDs do not advance the frame inbox. Browser errors, oversized
messages, corrupt JSON, bad image envelopes and pixel-budget violations close
this reference host. No remote input API is exposed by this adapter.

The screenshot is one **complete frame**, not an RFB rectangle update or CDP
push event. WebDriver BiDi has no `Page.screencastFrameAck`; the existing
`RemoteFrameInbox` uses `acknowledgementId=0` as a local placeholder, not a
wire protocol token. CSS viewport dimensions come from the authenticated host,
independently of the image's device-pixel size. One in-flight request bounds
request pressure; the existing inbox retains only the latest frame. The
painting thread must call `displayed(delivery)` only after real presentation,
and remote input stays disabled until a separate explicit host authorization.

## Existing owners and limits

- `CdpFrameJson.object` reuses the **existing** bounded, duplicate-key-refusing
  JSON parser for CDP and BiDi rather than adding a second parser.
- `CdpScreencastHost.imageSize` reuses the existing in-memory ImageIO header
  guard; it was changed from private to package-private for the sibling host.
- `BidiScreenshotCommands` alone is in `src/main` and is TeaVM-compatible;
  WebSocket/ImageIO host code lives in `src/test` and must never be compiled
  into JavaScript/Wasm GC browser bundles.
- Input is intentionally not forwarded: WebDriver BiDi `input.performActions`
  requires an independent, reviewed, user-authorized capability and release
  semantics. Do not infer permission from successful screenshot receipt.
- 8 MiB compressed bytes, 8192 pixels/axis, 16,777,216 pixels total;
  bounded JSON, one decoding worker and at most one pending network reply.
- Screenshot quality, browser-native pixel equality, cross-origin content,
  Firefox version specifics, accessibility/IME and RFB/TLS/SSH transport remain
  separately qualified capabilities.

## Java 21 proof

The existing `.github/workflows/m3-remote-cdp-jvm.yml` compiles this reference
with `--release 21 -Xlint:all -Werror` and runs `RemoteCdpProofSuite`.
For local headless verification, run that exact Java compiler step on the
`samples/m3-viewport/src` source and execute the proof suite. Tests use an
in-memory fake WebSocket, actual JDK ImageIO PNG/JPEG encoders, fragmented BiDi
messages, mismatch/error/duplicate-JSON refusal, stale command IDs, explicit
capture and nonauthorization, pixel geometry and close lifecycle.

**Evidence boundary:** these are real Java 21 JVM protocol tests, not a live
Firefox session or native SWT screenshot. Before promotion, run an authenticated
loopback Firefox WebDriver BiDi session, compare on-screen SWT pixels and
resource disposal across GTK/Windows/macOS, and independently verify the
sample's TeaVM JS/Wasm targets. Do not claim `browsingContext.captureScreenshot`
is a push streaming transport: it is per-request polling.

Specification: https://www.w3.org/TR/webdriver-bidi/#command-browsingContext-captureScreenshot
MDN: https://developer.mozilla.org/en-US/docs/Web/WebDriver/Reference/BiDi/Modules/browsingContext/captureScreenshot

## JVM-only live Firefox BiDi acceptance probe

The opt-in `FirefoxBiDiLiveProof` now implements the missing **direct Firefox
BiDi session handshake** without starting a browser or selecting a tab
implicitly. Run Firefox yourself in a separate, isolated browser profile with
remote debugging explicitly bound to loopback. On Firefox's direct endpoint:

```text
java -cp <compiled-Java21-proof-classes> \
  org.teavm.samples.m3viewport.FirefoxBiDiLiveProof \
  ws://127.0.0.1:9222/session list
```

After reviewing the emitted browser-minted `context=` identifier, explicitly
select one for a one-shot PNG capture, supplying the trusted independently
established CSS viewport size:

```text
java -cp <compiled-Java21-proof-classes> \
  org.teavm.samples.m3viewport.FirefoxBiDiLiveProof \
  ws://127.0.0.1:9222/session capture CONTEXT_ID CSS_WIDTH CSS_HEIGHT
```

`list` performs `session.new`, validates Firefox browser capabilities and
performs `browsingContext.getTree`. It prints context IDs **without** retrieving
URLs, sending navigation commands or capturing pixels. The separate `capture`
invocation requires the selected context to exist in the returned tree before
issuing `browsingContext.captureScreenshot` via the existing Java host. The
accepted PNG is independently fully decoded using a memory-only ImageIO input,
with bounds checked before allocation. No `displayed()` or remote input
permission is ever granted by this probe. No transport credentials or raw
debugger addresses are embedded in TeaVM JavaScript/Wasm.

`FirefoxBiDiLiveProofTest` exercises the exact handshake, nested context tree,
explicit context authorization, PNG capture and foreign-socket refusal using a
mock Java WebSocket. The original `BidiScreenshotHost` also rejects events,
closes and errors arriving from a different WebSocket; a second onOpen cannot
replace its session. The test is **not** live Firefox acceptance.

Only `ws://127.0.0.1:PORT/session` with no URL credentials, query or fragment
is accepted in the opt-in acceptance probe. Loopback does not authenticate a
local process; isolate the Firefox profile and never expose debugging over
untrusted networks. Do not use an existing personal browser profile with
sensitive tabs for this probe.

MDN: https://developer.mozilla.org/en-US/docs/Web/WebDriver/How_to/Create_BiDi_connection
WebDriver BiDi `session.new`: https://developer.mozilla.org/en-US/docs/Web/WebDriver/Reference/BiDi/Modules/session/new
