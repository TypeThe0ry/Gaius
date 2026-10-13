package org.teavm.classlib.java.util.stream.intimpl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** TeaVM 0.15's distinct int stream with one set for the whole stream (see TDistinctStreamImpl). */
public class TDistinctIntStreamImpl extends TWrappingIntStreamImpl {
    private final Set<Integer> seen = new HashSet<>();

    public TDistinctIntStreamImpl(TSimpleIntStreamImpl innerStream) {
        super(innerStream);
    }

    @Override
    protected IntPredicate wrap(IntPredicate consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
