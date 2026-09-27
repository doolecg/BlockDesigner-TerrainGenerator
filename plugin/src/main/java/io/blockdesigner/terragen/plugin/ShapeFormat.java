package io.blockdesigner.terragen.plugin;

import java.util.Locale;

/**
 * How the Terrain page shows a generator slider's value: a noise frequency as the size of its features in blocks
 * ("≈ 1,490 blocks" rather than "0.00067"), heights and amplitudes in blocks (when their slider goes above 2: below
 * that they are factors), a ground level as a Y, anything else as a plain number.
 */
final class ShapeFormat {
    private ShapeFormat() {
    }

    /** The value of a generator parameter as the page shows it; {@code max} is the top of its slider. */
    static String value(String param, double v, double max) {
        return switch (param) {
            case "frequency" -> v > 0 ? "≈ " + size(1 / v) + " blocks" : "flat";
            case "amplitude", "factor", "step" -> max > 2 ? number(v) + " blocks" : number(v);
            case "offset" -> "Y " + Math.round(v);
            default -> number(v);
        };
    }

    /** A feature size, rounded to about three significant digits so it reads as a size, not a measurement. */
    static String size(double blocks) {
        long n = Math.round(blocks);
        if (n >= 1000) {
            double scale = Math.pow(10, Math.floor(Math.log10(n)) - 2);
            n = Math.round(Math.round(n / scale) * scale);
        }
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A whole number when it is one, else two decimals. */
    static String number(double v) {
        return String.format(Locale.ROOT, v == Math.rint(v) ? "%,.0f" : "%.2f", v);
    }
}
