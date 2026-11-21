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

Expected behavior: the document registers `m3.page_title`, and discovery plus
execution return a JSON-serialized string with the page title. Aborting the
registration signal on `pagehide` unregisters it. Real browser qualification
(native vs polyfill, iframe origins, cancellation, JavaScript output, Wasm GC)
remains required. `jso/apis` targets Java 11; the sample targets Java 21.

WebMCP spec draft: https://webmachinelearning.github.io/webmcp/
Official polyfill: https://github.com/w3c-oss/webmcp-polyfill
License: Apache-2.0.
