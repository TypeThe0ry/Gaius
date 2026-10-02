package dev.gaius.browser;

import java.util.stream.DoubleStream;
import org.teavm.jso.JSBody;

/**
 * Java arithmetic that TeaVM's JavaScript output does not keep, for the 26.3 worldgen classes.
 *
 * <p>TeaVM compiles {@code float} to a JS number without rounding, so float arithmetic runs in
 * double precision, and it compiles {@code (int)}/{@code (long)} casts without Java's saturation.
 * 26.3 computes noise and density in float, so the browser would generate different terrain than
 * vanilla. {@code StrictMath263} rewrites the worldgen bytecode to call these helpers after every
 * float operation and conversion. Rounding a double result of one {@code +,-,*,/} on two floats
 * to float gives the float result exactly (a double has more than 2 * 24 + 2 bits), so
 * {@link #round(float)} after each operation reproduces Java float arithmetic bit for bit.
 *
 * <p>On the JVM {@code round} is the identity; the golden parity check runs the rewritten classes
 * there with a twin of this class whose natives are plain casts.
 */
public final class BrowserStrictMath {
    private BrowserStrictMath() {
    }

    /** Rounds a float-typed value that was computed in double precision to float. */
    @JSBody(params = "value", script = "return Math.fround(value);")
    public static native float round(float value);

    /** {@code (float) value}: one rounding from double to float. */
    @JSBody(params = "value", script = "return Math.fround(value);")
    public static native float fromDouble(double value);

    /**
     * {@code (float) value}. TeaVM converts the long to a double first, which rounds a second
     * time above 2^53; the low 11 bits are folded into a sticky bit (round to odd) so the double
     * holds the value exactly enough for the final float rounding to be correct.
     */
    public static float fromLong(long value) {
        if (value > -(1L << 53) && value < (1L << 53)) {
            return fromDouble(value);
        }
        long odd = (value >> 11) | ((value & 0x7FFL) != 0 ? 1L : 0L);
        // Scaling a float by 2^11 is exact, also in double precision.
        return fromDouble(odd) * 2048.0F;
    }

    /** {@code (float) value}; the int-to-double step is exact. */
    public static float fromInt(int value) {
        return fromDouble(value);
    }

    /** {@code (int) value} with Java's saturation (NaN is 0). */
    public static int toInt(double value) {
        if (value >= 2147483647.0) {
            return Integer.MAX_VALUE;
        }
        if (value <= -2147483648.0) {
            return Integer.MIN_VALUE;
        }
        return value != value ? 0 : (int) value;
    }

    /** {@code (int) value} with Java's saturation (NaN is 0). */
    public static int toInt(float value) {
        return toInt((double) value);
    }

    /** {@code (long) value} with Java's saturation (NaN is 0). */
    public static long toLong(double value) {
        if (value >= 9.223372036854775807E18) {
            return Long.MAX_VALUE;
        }
        if (value <= -9.223372036854775808E18) {
            return Long.MIN_VALUE;
        }
        return value != value ? 0L : (long) value;
    }

    /** {@code (long) value} with Java's saturation (NaN is 0). */
    public static long toLong(float value) {
        return toLong((double) value);
    }

    /**
     * {@code DoubleStream.sum()} as the JDK computes it for a sequential stream: Kahan
     * summation ({@code Collectors.sumWithCompensation} / {@code computeFinalSum}). TeaVM's
     * class library sums naively, which moves NormalNoise's normalization factor by an ulp.
     */
    public static double sum(DoubleStream stream) {
        double high = 0.0;
        double lowNegated = 0.0;
        double simple = 0.0;
        for (double value : stream.toArray()) {
            double tmp = value - lowNegated;
            double velvel = high + tmp;
            lowNegated = (velvel - high) - tmp;
            high = velvel;
            simple += value;
        }
        double result = high - lowNegated;
        if (Double.isNaN(result) && Double.isInfinite(simple)) {
            return simple;
        }
        return result;
    }
}
