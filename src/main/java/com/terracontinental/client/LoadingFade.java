package com.terracontinental.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * Crossfade from the loading screen into the world (the user, 2026-11-11): once the load reaches 100%
 * there are a few seconds before the world shows (about 6.5 s on the user's machine: 1.5 s to the
 * join, 5 s more for the spawn chunks). Over that gap the menu music fades out and the game's own
 * sounds fade in, instead of vanilla's hard cut to silence.
 *
 * <p>Volumes are scaled in {@code SoundEngine.calculateVolume} (see {@code SoundVolumeFadeMixin}) and
 * pushed to the playing channels every client tick. Vanilla stops a channel whose volume reaches 0,
 * so a faded sound is held at a tiny volume and the music is stopped outright when its fade ends.
 */
public final class LoadingFade {

    /** Music fade-out, from the moment the load reaches 100%. */
    private static final long MUSIC_FADE_MS = 6000L;
    /** Game sounds fade-in, from the moment the client gets its level (the join). */
    private static final long GAME_FADE_MS = 5000L;
    /** Gives up if the join never comes (the connection failed and the game went back to the menu). */
    private static final long JOIN_TIMEOUT_MS = 30000L;
    /** Lowest volume a faded sound is held at; vanilla stops a channel at 0. */
    private static final float FLOOR = 1.0E-4F;

    private static boolean active;
    private static boolean musicDone;
    private static long readyAt;
    private static long joinedAt = -1L;

    private LoadingFade() {}

    /** The load from the menu reached 100%: the crossfade begins. */
    public static void start() {
        active = true;
        musicDone = false;
        readyAt = Util.getMillis();
        joinedAt = -1L;
    }

    /** True while the crossfade runs; the join's stop-all-sounds is skipped then. */
    public static boolean active() {
        return active;
    }

    /** The client level arrived: game sounds can play from now, so their fade-in starts. */
    public static void joined() {
        if (active && joinedAt < 0L) {
            joinedAt = Util.getMillis();
        }
    }

    /** Scales a sound's volume for the crossfade; {@code volume} is vanilla's, already clamped. */
    public static float scale(float volume, SoundSource source) {
        if (!active || volume <= 0.0F || source == SoundSource.MASTER) {
            return volume; // MASTER: menu clicks and other UI sounds stay as they are
        }
        float f = source == SoundSource.MUSIC ? musicLevel() : gameLevel();
        return Math.max(volume * f, FLOOR);
    }

    /** Runs every client tick: advances the fade, then ends it once both halves are through. */
    public static void tick(Minecraft mc) {
        if (!active) {
            return;
        }
        long now = Util.getMillis();
        if (!musicDone && now - readyAt >= MUSIC_FADE_MS) {
            musicDone = true;
            mc.getMusicManager().stopPlaying(); // vanilla's in-game music follows at its usual delay
        }
        boolean gameDone = joinedAt >= 0L ? now - joinedAt >= GAME_FADE_MS : now - readyAt >= JOIN_TIMEOUT_MS;
        if (musicDone && gameDone) {
            active = false;
        }
        // Any category but MASTER refreshes every playing channel's volume through calculateVolume.
        mc.getSoundManager().updateSourceVolume(SoundSource.MUSIC, 1.0F);
    }

    /** Equal-power curves, so the sum sounds steady through the crossfade. */
    private static float musicLevel() {
        float t = Mth.clamp((Util.getMillis() - readyAt) / (float) MUSIC_FADE_MS, 0.0F, 1.0F);
        return Mth.cos(t * Mth.HALF_PI);
    }

    private static float gameLevel() {
        if (joinedAt < 0L) {
            return 0.0F;
        }
        float t = Mth.clamp((Util.getMillis() - joinedAt) / (float) GAME_FADE_MS, 0.0F, 1.0F);
        return Mth.sin(t * Mth.HALF_PI);
    }
}
