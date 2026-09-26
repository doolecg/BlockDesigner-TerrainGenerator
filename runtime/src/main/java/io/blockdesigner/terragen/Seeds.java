package io.blockdesigner.terragen;

/**
 * Seeds and hashing. Everything random in a generator comes from here, so the same seed gives the same terrain on any
 * machine, in any chunk order: no {@code java.util.Random}, no {@code Math.random}, no hash-map order.
 */
public final class Seeds {
    private Seeds() {
    }

    /**
     * The world seed for what a user typed: a number is used as it is, any other text is hashed the way Minecraft
     * hashes it ({@code String.hashCode()}), so a seed typed in the game and here agree. Blank is 0.
     */
    public static long parse(String text) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    /** SplitMix64's finaliser: a well-mixed 64-bit value from any 64-bit value. */
    public static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A 64-bit hash of a salt (FNV-1a over its chars, then mixed). */
    public static long hash64(String s) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001B3L;
        }
        return mix64(h);
    }

    /** A node's own seed: the world seed mixed with the node's salt, so renaming or re-wiring a node never reshuffles it. */
    public static long nodeSeed(long worldSeed, String salt) {
        return mix64(worldSeed ^ hash64(salt));
    }

    /** A hash of a seed and a grid position, for per-cell decisions (scatter, Worley points). */
    public static long hash(long seed, long a, long b) {
        return mix64(seed ^ mix64(a * 0x9E3779B97F4A7C15L ^ mix64(b * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L)));
    }

    /** {@link #hash(long, long, long)} in three dimensions. */
    public static long hash(long seed, long a, long b, long c) {
        return hash(hash(seed, a, b), c, 0x27D4EB2F165667C5L);
    }

    /** A double in [0, 1) from a hash. */
    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    /** A small deterministic random sequence (SplitMix64), for building permutation tables. */
    public static final class SplitMix {
        private long state;

        public SplitMix(long seed) {
            state = seed;
        }

        public long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            return mix64(state);
        }

        public int nextInt(int bound) {
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }

        public double nextDouble() {
            return unit(nextLong());
        }
    }
}
