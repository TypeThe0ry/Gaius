package org.teavm.classlib.java.lang;

import com.ibm.icu.lang.UCharacter;
import java.time.Duration;
import org.teavm.classlib.java.util.TCollections;
import org.teavm.classlib.java.util.TMap;
import org.teavm.classlib.java.lang.reflect.TType;

public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {
    }

    public static String characterToString(int codePoint) {
        return new String(TCharacter.toChars(codePoint));
    }

    public static int codePointOf(String name) {
        int result = UCharacter.getCharFromName(name);
        if (result < 0) {
            throw new IllegalArgumentException("Unrecognized Unicode character name: " + name);
        }
        return result;
    }

    public static int parseUnsignedInt(String value, int radix) {
        long result = TLong.parseLong(value, radix);
        if (result < 0 || result > 0xffff_ffffL) {
            throw new NumberFormatException("Unsigned integer out of range: " + value);
        }
        return (int) result;
    }

    public static long parseUnsignedLong(String value, int radix) {
        if (value == null || value.isEmpty()) {
            throw new NumberFormatException("empty String");
        }
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new NumberFormatException("radix " + radix + " out of range");
        }
        long result = 0;
        long maxQuotient = TLong.divideUnsigned(-1L, radix);
        int maxRemainder = (int) TLong.remainderUnsigned(-1L, radix);
        for (int index = 0; index < value.length(); index++) {
            int digit = TCharacter.digit(value.charAt(index), radix);
            if (digit < 0) {
                throw new NumberFormatException("Invalid digit in " + value);
            }
            int comparison = TLong.compareUnsigned(result, maxQuotient);
            if (comparison > 0 || comparison == 0 && digit > maxRemainder) {
                throw new NumberFormatException("Unsigned long out of range: " + value);
            }
            result = result * radix + digit;
        }
        return result;
    }

    public static float fma(float left, float right, float addend) {
        return (float) ((double) left * right + addend);
    }

    public static double fma(double left, double right, double addend) {
        return left * right + addend;
    }

    public static long maxMemory(TRuntime runtime) {
        // Conservative heap budget used by vanilla render-buffer sizing.
        return 1024L * 1024L * 1024L;
    }

    public static TMap<String, String> getenv() {
        return TCollections.emptyMap();
    }

    public static boolean isAnonymousClass(TClass<?> type) {
        return type.getEnclosingClass() != null && type.getSimpleName().isEmpty();
    }

    public static long threadId(TThread thread) {
        return thread.getId();
    }

    public static TThread$State threadState(TThread thread) {
        return thread.isAlive() ? TThread$State.RUNNABLE : TThread$State.TERMINATED;
    }

    public static void sleep(Duration duration) throws TInterruptedException {
        TThread.sleep(duration.toMillis());
    }

    public static TType genericSuperclass(TClass<?> type) {
        return type.getSuperclass();
    }

    public static TType[] genericInterfaces(TClass<?> type) {
        TClass<?>[] interfaces = type.getInterfaces();
        TType[] result = new TType[interfaces.length];
        System.arraycopy(interfaces, 0, result, 0, interfaces.length);
        return result;
    }
}
