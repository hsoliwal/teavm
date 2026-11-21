/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.jso.webmcp;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.abort.AbortSignal;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSUndefined;
import org.teavm.jso.dom.events.EventTarget;

/**
 * Window-only WebMCP interface. A browser exposes this from document.modelContext.
 * The APIs are experimental; the Tool overlay intentionally retains its opaque
 * native/polyfill identity and is never reduced to the name string for execution.
 */
public interface ModelContext extends EventTarget {
    JSPromise<JSUndefined> registerTool(Definition definition, RegistrationOptions options);

    JSPromise<JSArrayReader<Tool>> getTools(DiscoveryOptions options);

    /** The result is a JSON-serialized string, not a JavaScript object. */
    JSPromise<String> executeTool(Tool tool, JSObject inputObject, ExecutionOptions options);

    /** Mutable JavaScript dictionary owned by the registering document. */
    interface Definition extends JSObject {
        @JSProperty void setName(String name);
        @JSProperty void setTitle(String title);
        @JSProperty void setDescription(String description);
        @JSProperty void setInputSchema(JSObject schema);
        @JSProperty void setOutputSchema(JSObject schema);
        @JSProperty void setAnnotations(JSObject annotations);
        @JSProperty void setExecute(Executor callback);
    }

    /** Discovered opaque tool. Supports the draft Tool interface and polyfill dictionaries. */
    interface Tool extends JSObject {
        @JSProperty String getName();
        @JSProperty String getTitle();
        @JSProperty String getDescription();
        @JSProperty String getOrigin();
        @JSProperty JSObject getInputSchema();
        @JSProperty JSObject getOutputSchema();
        @JSProperty JSObject getAnnotations();
    }

    /** A callback may return a JSON value or a JSPromise of a JSON value. */
    @JSFunctor
    interface Executor extends JSObject {
        JSObject execute(JSObject inputObject, CallbackOptions options);
    }

    interface CallbackOptions extends JSObject {
        @JSProperty AbortSignal getSignal();
    }

    interface RegistrationOptions extends JSObject {
        @JSProperty void setSignal(AbortSignal signal);
        @JSProperty void setExposedTo(JSArrayReader<String> origins);
    }

    interface DiscoveryOptions extends JSObject {
        @JSProperty void setFromOrigins(JSArrayReader<String> origins);
    }

    interface ExecutionOptions extends JSObject {
        @JSProperty void setSignal(AbortSignal signal);
    }
}
