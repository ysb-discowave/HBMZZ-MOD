package com.rsaudio.formats;

/**
 * The one fact the rest of the package has to agree on: is the PCM direct route available?
 *
 * <p>The direct route is only reachable through the Mixin hooks on rsaudio's
 * {@code ClientSoundPlayer} (see {@code com.rsaudio.formats.mixin.MixinClientSoundPlayer}), and those
 * only exist when <b>MixinBooter</b> is installed - the host mod itself declares no mixins, and this
 * package must never turn into a hidden hard dependency on one. So the answer is decided once, in
 * {@code FormatsBootstrap.init}, and read here.</p>
 *
 * <p>Deliberately dependency-free: no Minecraft type, no Mixin type, no MixinBooter type. Anything may
 * read this class at any point of the startup without dragging a fragile class in.</p>
 */
public final class FormatsRuntime {

    private static volatile boolean directRouteAvailable;

    private FormatsRuntime() {
    }

    /**
     * True when a sound may be handed to {@code PcmPlayer}: the Mixin hooks are installed and the
     * config has not pinned the transcode route ({@code route=NATIVE}).
     */
    public static boolean directRouteAvailable() {
        return directRouteAvailable;
    }

    public static void setDirectRouteAvailable(boolean available) {
        directRouteAvailable = available;
    }
}
