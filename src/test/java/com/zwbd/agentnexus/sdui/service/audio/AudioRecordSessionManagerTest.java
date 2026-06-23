package com.zwbd.agentnexus.sdui.service.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRecordSessionManagerTest {

    @Test
    void suspendedSessionCanResumeAndKeepBufferedAudio() {
        AudioRecordSessionManager manager = new AudioRecordSessionManager(120_000);

        manager.startSession("dev-1");
        manager.appendChunk("dev-1", new byte[]{1, 2});

        assertTrue(manager.suspendSession("dev-1"));
        assertTrue(manager.resumeSession("dev-1"));

        manager.appendChunk("dev-1", new byte[]{3, 4});

        assertArrayEquals(new byte[]{1, 2, 3, 4}, manager.stopSession("dev-1"));
    }

    @Test
    void expiredSuspendedSessionIsDiscarded() throws Exception {
        AudioRecordSessionManager manager = new AudioRecordSessionManager(1);

        manager.startSession("dev-1");
        manager.appendChunk("dev-1", new byte[]{1, 2});
        assertTrue(manager.suspendSession("dev-1"));

        Thread.sleep(5);
        manager.purgeExpiredSuspendedSessions();

        assertFalse(manager.isRecording("dev-1"));
    }

    @Test
    void stopOnReconnectIsConsumedOnce() {
        AudioRecordSessionManager manager = new AudioRecordSessionManager(120_000);

        manager.startSession("dev-1");
        manager.appendChunk("dev-1", new byte[]{1, 2});
        assertTrue(manager.suspendSession("dev-1"));

        assertTrue(manager.requestStopOnReconnect("dev-1", "test"));
        assertTrue(manager.hasPendingStop("dev-1"));
        assertTrue(manager.resumeSession("dev-1"));

        assertTrue(manager.consumeStopOnReconnect("dev-1"));
        assertFalse(manager.consumeStopOnReconnect("dev-1"));
        assertFalse(manager.hasPendingStop("dev-1"));
    }
}
