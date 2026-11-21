/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.jso.webmcp;

import org.teavm.jso.JSBody;

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
}
