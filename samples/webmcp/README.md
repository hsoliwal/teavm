# TeaVM typed WebMCP JSO proof fixture

This optional sample registers one read-only page-owned tool, `m3.page_title`,
using typed interfaces under `jso/apis/.../webmcp`. It does not embed ChromeLLM,
launch a local agent, install a polyfill or give the document extension privileges.
It runs only on secure origins (HTTPS or localhost) with a compatible native
`document.modelContext` or an already installed polyfill.

From the TeaVM `samples/` build, run `./gradlew :webmcp:generateJavaScript
:webmcp:war` after publishing the fork's TeaVM dependencies to local Maven.
Serve the resulting webapp over HTTPS/localhost. For the official polyfill,
install `@w3c/webmcp-polyfill@0.1.1` separately and load its `dist/polyfill.js`
before the generated TeaVM script. The polyfill does not supply a browser agent.

Expected behavior: the document registers `m3.page_title`, then its TeaVM
Java code discovers the tool by name, checks the source/owning-window identity,
invokes `executeTool()` with an empty JSON object and displays the serialized
JSON result in the page's status element. Aborting the
registration signal on `pagehide` unregisters it. Real browser qualification
(native vs polyfill, iframe origins, cancellation, JavaScript output, Wasm GC)
remains required. `jso/apis` targets Java 11; the sample targets Java 21.

WebMCP spec draft: https://webmachinelearning.github.io/webmcp/
Official polyfill: https://github.com/w3c-oss/webmcp-polyfill
License: Apache-2.0.


## Typed API/version boundary

`ModelContext.Tool` models the November 19 WebMCP draft's opaque `Tool`
descriptor, not a string-based MCP transport tool. The official `0.1.1`
polyfill still returns a `RegisteredTool` dictionary with `window`; the
newer draft uses `source`. `WebMcp.ownerWindow(tool)` handles both while
the Java client preserves the discovered descriptor in `executeTool()`.
Absent or foreign owners fail the sample's source check. Schemas and tool
annotations are metadata; they are not authorization or a full validator.
Production agents must independently verify schemas and seek consent.

The source was checked with Java 21's `javac --release 11 -Xlint:all -Werror`
against contract-shaped stubs. ChromeAIAgent's opt-in CI now checks out this
exact TeaVM source revision `86192d9b9cb818f4689bf4f8d9345fefb4019640`
for a **real** Gradle JSO/API + generated JavaScript test. Treat the result
as pending until the workflow finishes. The sample does not connect a live
browser agent, infer permissions, enable ChromeLLM-in-browser, or implement
declarative WebMCP form tools.

## Compiled-Java browser qualification

After `./gradlew :webmcp:generateJavaScript :webmcp:war` in `samples/`,
the test `samples/webmcp/src/test/browser/runtime.test.mjs` unpacks the
actual generated WAR, injects the exact `@w3c/webmcp-polyfill@0.1.1`
ahead of the generated JavaScript (test fixture only), and serves it from
a secure localhost context in Playwright Chromium with native WebMCP disabled.
It asserts that **compiled Java** performs registration, discovery, ownership
verification, and execution, then publishes the expected JSON title response.
The temporary WAR extraction and local HTTP server are removed after testing.

The test requires Node 24, Playwright 1.55 and Chromium installed in the
runner. It does not imply native Chrome WebMCP, WPT conformance, external
browser-agent communication, cross-origin capabilities or production tool
authorization. Run it with:

```sh
npm install --no-save --ignore-scripts @w3c/webmcp-polyfill@0.1.1 playwright@1.55.0
npx playwright install --with-deps chromium
node --test samples/webmcp/src/test/browser/runtime.test.mjs
```
