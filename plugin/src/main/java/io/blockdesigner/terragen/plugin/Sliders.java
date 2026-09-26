package io.blockdesigner.terragen.plugin;

/**
 * Slider position (0 to 1) to and from a parameter value, linear or logarithmic (for frequencies, where each notch
 * should feel the same), and optionally inverted (so a slider for a frequency can read as "size": right is bigger).
 */
final class Sliders {
    private Sliders() {
    }

    static double toSlider(double v, double min, double max, boolean log, boolean invert) {
        double t = log ? Math.log(v / min) / Math.log(max / min) : (v - min) / (max - min);
        t = Math.max(0, Math.min(1, t));
        return invert ? 1 - t : t;
    }

    static double fromSlider(double t, double min, double max, boolean log, boolean invert) {
        if (invert) t = 1 - t;
        return log ? min * Math.pow(max / min, t) : min + t * (max - min);
    }
}
