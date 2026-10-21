package com.akshaykadam.pixelboard.extension.update;

import java.lang.reflect.Field;

/** Robolectric reuses its class loader across tests, so static update state must be reset. */
final class GboardUpdateTestState {
    private GboardUpdateTestState() {
    }

    static void reset() throws Exception {
        set("state", GboardUpdateInstaller.State.IDLE);
        set("failureCode", null);
        set("expectedNonce", null);
        set("bytesDownloaded", Long.valueOf(0L));
        set("bytesTotal", Long.valueOf(-1L));
        set("activeSessionId", Integer.valueOf(-1));
        set("pendingConfirmation", null);
        // Robolectric's package has no signing certificate; treat it as an official build.
        setOfficialBuild(true);
        GboardUpdateInstaller.setSettingsVisible(false);
        GboardUpdateUiListener.set(null);
    }

    static void setOfficialBuild(boolean official) throws Exception {
        set("officialSignature", Boolean.valueOf(official));
    }

    static void setState(GboardUpdateInstaller.State state) throws Exception {
        set("state", state);
    }

    static void setActiveSession(int sessionId) throws Exception {
        set("activeSessionId", Integer.valueOf(sessionId));
    }

    static void setFailure(String code) throws Exception {
        set("failureCode", code);
    }

    static void setNonce(String nonce) throws Exception {
        set("expectedNonce", nonce);
    }

    private static void set(String name, Object value) throws Exception {
        Field field = GboardUpdateInstaller.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
