package org.teavm.classlib.java.util.stream.doubleimpl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.DoublePredicate;

/** TeaVM 0.15's distinct double stream with one set for the whole stream (see TDistinctStreamImpl). */
public class TDistinctDoubleStreamImpl extends TWrappingDoubleStreamImpl {
    private final Set<Double> seen = new HashSet<>();

    public TDistinctDoubleStreamImpl(TSimpleDoubleStreamImpl innerStream) {
        super(innerStream);
    }

    @Override
    protected DoublePredicate wrap(DoublePredicate consumer) {
        return e -> !seen.add(e) || consumer.test(e);
    }
}
