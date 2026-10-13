package org.teavm.classlib.java.util.stream.longimpl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;

/** TeaVM 0.15's distinct long stream with one set for the whole stream (see TDistinctStreamImpl). */
public class TDistinctLongStreamImpl extends TWrappingLongStreamImpl {
    private final Set<Long> seen = new HashSet<>();

    public TDistinctLongStreamImpl(TSimpleLongStreamImpl innerStream) {
        super(innerStream);
    }

    @Override
    protected LongPredicate wrap(LongPredicate consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
