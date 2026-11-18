/* SPDX-License-Identifier: Apache-2.0 */
plugins {
    java
    war
    id("org.teavm")
}

dependencies {
    teavm(teavm.libs.jsoApis)
}

teavm {
    all {
        mainClass = "org.teavm.samples.m3viewport.Client"
    }
    js {
        addedToWebApp = true
    }
    wasmGC {
        addedToWebApp = true
    }
}

/** Display-free JVM proof: no JUnit or native SWT dependency. */
tasks.register<JavaExec>("m3ViewportProof") {
    group = "verification"
    description = "Run randomized M3 remote viewport geometry and revision proof on the JVM"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.teavm.samples.m3viewport.FrameWindowProof"
}


/** JDK-only reference host: Java networking/ImageIO never enters TeaVM JS/Wasm. */
tasks.register<JavaExec>("m3CdpHostProof") {
    group = "verification"
    description = "Run bounded CDP/TeaVM full-frame and input contract proofs on Java 21"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "org.teavm.samples.m3viewport.RemoteCdpProofSuite"
}
