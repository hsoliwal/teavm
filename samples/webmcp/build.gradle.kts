/* SPDX-License-Identifier: Apache-2.0 */
plugins {
    java
    war
    id("org.teavm")
}

dependencies {
    teavm(teavm.libs.jsoApis)
}

teavm.js {
    addedToWebApp = true
    mainClass = "org.teavm.samples.webmcp.Client"
}
