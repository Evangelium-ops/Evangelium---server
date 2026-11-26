package io.evangelium.server.ai;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * The result of {@code list.sort(Comparator.comparingDouble(key))}, each key read once instead of twice per comparison: a stable merge
 * sort with {@link Double#compare}, so ties and NaN land where they do there. In a crowd the comparator was a quarter of all tick time.
 */
public final class DistanceSort {
    private static final int RUN = 8;

    private DistanceSort() {}

    @SuppressWarnings("unchecked")
    public static <T> void sort(List<T> list, ToDoubleFunction<? super T> key) {
        int n = list.size();
        if (n < 2) return;
        Object[] items = list.toArray();
        double[] keys = new double[n];
        for (int i = 0; i < n; i++) keys[i] = key.applyAsDouble((T) items[i]);
        for (int start = 0; start < n; start += RUN) {
            insertion(items, keys, start, Math.min(start + RUN, n));
        }
        if (n > RUN) {
            Object[] itemsTo = new Object[n];
            double[] keysTo = new double[n];
            for (int width = RUN; width < n; width <<= 1) {
                for (int from = 0; from < n; from += width << 1) {
                    merge(items, keys, itemsTo, keysTo, from, Math.min(from + width, n), Math.min(from + (width << 1), n));
                }
                Object[] swapItems = items; items = itemsTo; itemsTo = swapItems;
                double[] swapKeys = keys; keys = keysTo; keysTo = swapKeys;
            }
        }
        for (int i = 0; i < n; i++) list.set(i, (T) items[i]);
    }

    private static void insertion(Object[] items, double[] keys, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            Object item = items[i];
            double key = keys[i];
            int at = i - 1;
            while (at >= from && Double.compare(keys[at], key) > 0) {
                items[at + 1] = items[at];
                keys[at + 1] = keys[at];
                at--;
            }
            items[at + 1] = item;
            keys[at + 1] = key;
        }
    }

    private static void merge(Object[] items, double[] keys, Object[] itemsTo, double[] keysTo, int from, int middle, int to) {
        int left = from, right = middle;
        for (int at = from; at < to; at++) {
            // the left run on a tie: that is what keeps it stable
            if (right >= to || (left < middle && Double.compare(keys[left], keys[right]) <= 0)) {
                itemsTo[at] = items[left];
                keysTo[at] = keys[left++];
            } else {
                itemsTo[at] = items[right];
                keysTo[at] = keys[right++];
            }
        }
    }
}
