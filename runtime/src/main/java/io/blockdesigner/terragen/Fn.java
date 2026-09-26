package io.blockdesigner.terragen;

/** A compiled node: its value at a world position. Immutable and safe to call from any thread. */
@FunctionalInterface
public interface Fn {
    double at(double x, double y, double z);
}
