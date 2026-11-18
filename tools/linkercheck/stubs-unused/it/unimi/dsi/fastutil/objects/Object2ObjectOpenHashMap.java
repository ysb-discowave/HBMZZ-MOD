package it.unimi.dsi.fastutil.objects;

/**
 * Stand-in for the fastutil class 1.12.2 ships inside the game jar.
 *
 * <p>The fastutil jars on the Gradle cache are trimmed (no
 * {@code Object2ObjectOpenHashMap}), so both the ported panel and Cursed
 * Edition's own classes fail to initialise against them. A plain map behaves the
 * same for everything this check touches.</p>
 */
public class Object2ObjectOpenHashMap<K, V> extends java.util.HashMap<K, V> {
}
