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

import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;

/** Feature detection only. Never installs a polyfill or starts an agent. */
public final class WebMcp {
    private WebMcp() {
    }

    @JSBody(script = "return typeof document !== 'undefined' && globalThis.isSecureContext === true "
            + "&& !!document.modelContext && typeof document.modelContext.registerTool === 'function' "
            + "&& typeof document.modelContext.getTools === 'function' "
            + "&& typeof document.modelContext.executeTool === 'function';")
    public static native boolean isAvailable();

    /** Returns null for absent/incomplete contexts; never replaces native instances. */
    @JSBody(script = "return typeof document !== 'undefined' && globalThis.isSecureContext === true "
            + "&& document.modelContext && typeof document.modelContext.registerTool === 'function' "
            + "&& typeof document.modelContext.getTools === 'function' "
            + "&& typeof document.modelContext.executeTool === 'function' ? document.modelContext : null;")
    public static native ModelContext currentOrNull();

    /** Normalizes native Tool.source and polyfill 0.1.1 RegisteredTool.window. */
    @JSBody(params = "tool", script = "return tool && (tool.source ?? tool.window) || null;")
    public static native Window ownerWindow(ModelContext.Tool tool);
}
