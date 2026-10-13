package org.teavm.classlib.java.util.stream.impl;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * TeaVM 0.15's sorted stream with an exact size. The stream drains its source into an array
 * when it is created, so the source's estimate afterwards describes what is left of the source
 * (nothing, for a spliterator that counts the remaining elements, as fastutil's and Guava's list
 * spliterators do). TeaVM delegated estimateSize() to that drained source; toArray() of this
 * stream, or of a stream mapped from it, then allocated too small an array and wrote past its end
 * (a WebAssembly GC trap; a silently grown or truncated array in the JavaScript build). Vanilla's
 * LevelStorageSource.loadLevelSummaries sorts the level summaries this way.
 */
public class TSortedStreamImpl<T> extends TSimpleStreamImpl<T> {
    private TSimpleStreamImpl<T> sourceStream;
    private T[] array;
    private int index;

    @SuppressWarnings("unchecked")
    public TSortedStreamImpl(TSimpleStreamImpl<T> sourceStream, Comparator<? super T> comparator) {
        this.sourceStream = sourceStream;
        array = (T[]) sourceStream.toArray(Object[]::new);
        Arrays.sort(array, comparator);
    }

    @Override
    public boolean next(Predicate<? super T> consumer) {
        while (index < array.length) {
            if (!consumer.test(array[index++])) {
                break;
            }
        }
        return index < array.length;
    }

    @Override
    protected int estimateSize() {
        return array.length - index;
    }

    @Override
    public void close() {
        sourceStream.close();
    }
}
