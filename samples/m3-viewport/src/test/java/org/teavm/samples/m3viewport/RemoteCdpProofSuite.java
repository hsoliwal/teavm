/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

/** Gradle entry point: portable frame inbox and independent Java 21 CDP-host proof. */
public final class RemoteCdpProofSuite {
    private RemoteCdpProofSuite() { }

    public static void main(String[] args) throws Exception {
        RemoteFrameInboxProof.main(args);
        CdpScreencastHostProof.main(args);
    }
}
