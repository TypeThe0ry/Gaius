package org.teavm.classlib.java.util;

import java.util.SequencedSet;

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

    public static <E> SequencedSet<E> unmodifiableSequencedSet(SequencedSet<E> set) {
        return set;
    }
}
