/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.webmcp;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.abort.AbortController;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSUndefined;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.webmcp.ModelContext;
import org.teavm.jso.webmcp.WebMcp;

/** Read-only page-owned WebMCP sample; no local inference or extension privileges. */
public final class Client {
    private static AbortController lifetime;

    private Client() {
    }

    public static void main(String[] args) {
        ModelContext context = WebMcp.currentOrNull();
        if (context == null) {
            status("WebMCP unavailable; install the pinned polyfill before this script, "
                    + "or use native support.");
            return;
        }
        lifetime = new AbortController();
        ModelContext.Definition definition = JSObjects.create();
        definition.setName("m3.page_title");
        definition.setTitle("Page title");
        definition.setDescription("Read the title of this document without changing the page.");
        definition.setInputSchema(emptyObjectSchema());
        definition.setOutputSchema(titleOutputSchema());
        definition.setExecute((input, options) -> titleResult(HTMLDocument.current().getTitle()));

        ModelContext.RegistrationOptions options = JSObjects.create();
        options.setSignal(lifetime.getSignal());
        context.registerTool(definition, options).then(value -> {
            ModelContext.DiscoveryOptions discovery = JSObjects.create();
            context.getTools(discovery).then(tools -> {
                for (int i = 0; i < tools.getLength(); i++) {
                    ModelContext.Tool tool = tools.get(i);
                    if (!"m3.page_title".equals(tool.getName())
                            || WebMcp.ownerWindow(tool) != Window.current()) {
                        continue;
                    }
                    ModelContext.ExecutionOptions execution = JSObjects.create();
                    context.executeTool(tool, JSObjects.create(), execution).then(json -> {
                        status("m3.page_title result: " + json.stringValue());
                        return JSUndefined.instance();
                    }, failure -> {
                        status("WebMCP tool invocation failed");
                        return JSUndefined.instance();
                    });
                    return JSUndefined.instance();
                }
                status("m3.page_title was not discovered in this document");
                return JSUndefined.instance();
            }, failure -> {
                status("WebMCP tool discovery failed");
                return JSUndefined.instance();
            });
            return JSUndefined.instance();
        }, error -> {
            status("WebMCP registration failed");
            return JSUndefined.instance();
        });
        Window.current().addEventListener("pagehide", event -> lifetime.abort());
    }

    @JSBody(script = "return { type: 'object', properties: {}, additionalProperties: false };")
    private static native JSObject emptyObjectSchema();

    @JSBody(script = "return { type: 'object', required: ['title'], "
            + "properties: { title: { type: 'string' } }, additionalProperties: false };")
    private static native JSObject titleOutputSchema();

    @JSBody(params = "title", script = "return { title: title };")
    private static native JSObject titleResult(String title);

    private static void status(String message) {
        HTMLElement text = HTMLDocument.current().getElementById("status");
        if (text != null) {
            text.setInnerText(message);
        }
    }
}
