package com.archivist.bridge;

import com.archivist.data.ServerSession;
import com.archivist.detection.DetectionPipeline;
import com.archivist.detection.fingerprint.GuiCapture;

import java.util.function.Consumer;

/**
 * Bridge between mixin classes and the mod's detection/session systems.
 * State is held in an immutable record stored in a single volatile field
 * so that readers always see a consistent snapshot.
 */
public final class MixinBridge {
    /** Immutable snapshot of bridge state. */
    public record State(DetectionPipeline pipeline, ServerSession session, boolean active,
                        Consumer<GuiCapture> passiveGuiCallback) {
        static final State INACTIVE = new State(null, null, false, null);
    }

    private static volatile State state = State.INACTIVE;

    private MixinBridge() {}

    public static void activate(DetectionPipeline pipeline, ServerSession session) {
        state = new State(pipeline, session, true, state.passiveGuiCallback());
    }

    public static void deactivate() {
        state = State.INACTIVE;
    }

    // ── Accessors — read the single volatile field once, then pull values ──

    public static State snapshot() { return state; }

    public static DetectionPipeline pipeline() { return state.pipeline(); }
    public static ServerSession session() { return state.session(); }
    public static boolean active() { return state.active(); }
    public static Consumer<GuiCapture> passiveGuiCallback() { return state.passiveGuiCallback(); }

    // ── Field-level mutators (preserve other fields atomically) ──

    public static void setPassiveGuiCallback(Consumer<GuiCapture> cb) {
        State s = state;
        state = new State(s.pipeline(), s.session(), s.active(), cb);
    }
}
