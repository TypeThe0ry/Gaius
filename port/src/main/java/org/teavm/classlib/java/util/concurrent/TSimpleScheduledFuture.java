package org.teavm.classlib.java.util.concurrent;

final class TSimpleScheduledFuture<V> extends TCompletableFuture<V>
        implements TScheduledFuture<V> {
    @Override
    public long getDelay(TTimeUnit unit) {
        return 0;
    }

    @Override
    public int compareTo(TDelayed other) {
        return Long.compare(getDelay(TTimeUnit.NANOSECONDS),
                other.getDelay(TTimeUnit.NANOSECONDS));
    }
}
