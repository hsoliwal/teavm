/*
 *  Copyright 2026 Synexia Contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.jso.webmcp;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.abort.AbortSignal;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSUndefined;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.EventTarget;
import org.teavm.jso.dom.events.Registration;

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

    /** Attach without overriding the browser's own event dispatch semantics. */
    default Registration onToolChange(EventListener<Event> listener) {
        return onEvent("toolchange", listener);
    }

    default Registration onToolActivated(EventListener<ToolActivatedEvent> listener) {
        return onEvent("toolactivated", listener);
    }

    default Registration onToolCancel(EventListener<ToolCancelEvent> listener) {
        return onEvent("toolcancel", listener);
    }

    /** Mutable JavaScript dictionary owned by the registering document. */
    interface Definition extends JSObject {
        @JSProperty
        void setName(String name);
        @JSProperty
        void setTitle(String title);
        @JSProperty
        void setDescription(String description);
        @JSProperty
        void setInputSchema(JSObject schema);
        @JSProperty
        void setOutputSchema(JSObject schema);
        @JSProperty
        void setAnnotations(JSObject annotations);
        @JSProperty
        void setExecute(Executor callback);
    }

    /** Discovered opaque tool. Supports the draft Tool interface and polyfill dictionaries. */
    interface Tool extends JSObject {
        @JSProperty
        String getName();
        @JSProperty
        String getTitle();
        @JSProperty
        String getDescription();
        @JSProperty
        String getOrigin();
        @JSProperty
        JSObject getInputSchema();
        @JSProperty
        JSObject getOutputSchema();
        @JSProperty
        JSObject getAnnotations();
        @JSProperty("annotations")
        ToolAnnotations getTypedAnnotations();
        @JSProperty
        Window getSource();
    }

    /** Browser-defined hints, never authorization grants. */
    interface ToolAnnotations extends JSObject {
        @JSProperty
        boolean isReadOnlyHint();
        @JSProperty
        void setReadOnlyHint(boolean value);
        @JSProperty
        boolean isUntrustedContentHint();
        @JSProperty
        void setUntrustedContentHint(boolean value);
        @JSProperty
        boolean isConsequentialHint();
        @JSProperty
        void setConsequentialHint(boolean value);
        @JSProperty
        boolean isDebugging();
        @JSProperty
        void setDebugging(boolean value);
    }

    interface ToolActivatedEvent extends Event {
        @JSProperty
        String getToolName();
    }

    interface ToolCancelEvent extends Event {
        @JSProperty
        String getToolName();
    }

    /** A callback may return a JSON value or a JSPromise of a JSON value. */
    @JSFunctor
    interface Executor extends JSObject {
        JSObject execute(JSObject inputObject, CallbackOptions options);
    }

    interface CallbackOptions extends JSObject {
        @JSProperty
        AbortSignal getSignal();
    }

    interface RegistrationOptions extends JSObject {
        @JSProperty
        void setSignal(AbortSignal signal);
        @JSProperty
        void setExposedTo(JSArrayReader<String> origins);
    }

    interface DiscoveryOptions extends JSObject {
        @JSProperty
        void setFromOrigins(JSArrayReader<String> origins);
    }

    interface ExecutionOptions extends JSObject {
        @JSProperty
        void setSignal(AbortSignal signal);
    }
}
