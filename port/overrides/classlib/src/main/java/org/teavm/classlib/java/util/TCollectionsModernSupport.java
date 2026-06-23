package org.teavm.classlib.java.util;

public final class TCollectionsModernSupport {
    private TCollectionsModernSupport() {
    }

    public static <E> TSortedSet<E> unmodifiableSortedSet(TSortedSet<E> set) {
        return set;
    }

    public static <K, V> TSortedMap<K, V> unmodifiableSortedMap(TSortedMap<K, V> map) {
        return map;
    }

    public static <E> TSpliterator<E> emptySpliterator() {
        return TSpliterators.spliterator(new Object[0], 0);
    }
}
