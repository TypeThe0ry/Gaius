package org.teavm.classlib.java.util.stream.impl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * TeaVM 0.15's distinct stream, remembering the elements it saw for the whole stream. TeaVM
 * created the set in wrap(), that is once per next() call, and iterator(), spliterator(),
 * skip() and concat() pull through several next() calls: every pull after the first let
 * duplicates through again.
 */
public class TDistinctStreamImpl<T> extends TWrappingStreamImpl<T, T> {
    private final Set<T> seen = new HashSet<>();

    public TDistinctStreamImpl(TSimpleStreamImpl<T> innerStream) {
        super(innerStream);
    }

    @Override
    protected Predicate<T> wrap(Predicate<? super T> consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
