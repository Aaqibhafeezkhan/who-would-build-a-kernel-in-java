package duke.conformance;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** java.util against HotSpot: results, iteration order, toString shapes and exception messages. */
final class Collections2 {

    static String arrayListBasics() {
        List<String> list = new ArrayList<>();
        list.add("b");
        list.add("d");
        list.add(0, "a");
        list.add(2, "c");
        list.set(3, "D");
        String removed = list.remove(1);
        boolean removedObject = list.remove("zzz");
        return list + " " + list.size() + " " + removed + " " + removedObject + " " + list.indexOf("D") + " "
            + list.contains("c") + " " + list.get(0);
    }

    static String listEqualityAndHash() {
        List<Integer> a = new ArrayList<>(Arrays.asList(1, 2, 3));
        List<Integer> b = Arrays.asList(1, 2, 3);
        return a.equals(b) + " " + (a.hashCode() == b.hashCode()) + " " + a.hashCode() + " " + a.equals(List2.of());
    }

    static final class List2 {
        static List<Integer> of() {
            return new ArrayList<>();
        }
    }

    static String listIndexMessages() {
        List<String> list = new ArrayList<>(Arrays.asList("x", "y"));
        StringBuilder sb = new StringBuilder();
        try {
            list.get(5);
        } catch (IndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            list.add(9, "z");
        } catch (IndexOutOfBoundsException e) {
            sb.append(e.getMessage()).append(';');
        }
        try {
            list.remove(-1);
        } catch (IndexOutOfBoundsException e) {
            sb.append(e.getMessage());
        }
        return sb.toString();
    }

    static String iteratorRemoveAndFailFast() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(i);
        }
        for (Iterator<Integer> it = list.iterator(); it.hasNext(); ) {
            if (it.next() % 3 == 0) {
                it.remove();
            }
        }
        String afterRemove = list.toString();
        String cme = "none";
        try {
            for (Integer i : list) {
                if (i == 4) {
                    list.add(100);
                }
            }
        } catch (ConcurrentModificationException e) {
            cme = e.getClass().getName();
        }
        String nse = "none";
        try {
            new ArrayList<String>().iterator().next();
        } catch (NoSuchElementException e) {
            nse = e.getClass().getName();
        }
        return afterRemove + " " + cme + " " + nse;
    }

    static String removeIfAndSort() {
        List<String> words = new ArrayList<>(Arrays.asList("pear", "fig", "apple", "kiwi", "banana", "date"));
        words.removeIf(w -> w.length() == 4);
        words.sort(Comparator.naturalOrder());
        List<String> byLength = new ArrayList<>(Arrays.asList("ccc", "a", "bb", "dd", "e", "fff"));
        byLength.sort(Comparator.comparingInt(String::length));
        return words + " " + byLength;
    }

    static String stableSortKeepsTies() {
        String[] items = {"b1", "a1", "b2", "a2", "c1", "a3", "b3"};
        Arrays.sort(items, Comparator.comparing(s -> s.charAt(0)));
        return Arrays.toString(items);
    }

    static String comparatorCombinators() {
        List<String> words = new ArrayList<>(Arrays.asList("bb", "a", "ccc", "aa", "c", "b"));
        words.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
        return words.toString();
    }

    static String primitiveSorts() {
        int[] ints = {5, -1, 3, Integer.MIN_VALUE, 0, 3};
        long[] longs = {5L << 40, -1, 0, Long.MAX_VALUE};
        char[] chars = {'d', 'a', 'c', 'b'};
        byte[] bytes = {3, -128, 127, 0};
        Arrays.sort(ints);
        Arrays.sort(longs);
        Arrays.sort(chars);
        Arrays.sort(bytes);
        return Arrays.toString(ints) + Arrays.toString(longs) + Arrays.toString(chars) + Arrays.toString(bytes)
            + Arrays.binarySearch(ints, 3) + Arrays.binarySearch(ints, 4);
    }

    static String hashMapIterationOrderMatchesTheJdk() {
        Map<String, Integer> map = new HashMap<>();
        String[] keys = {"zeta", "alpha", "kernel", "duke", "java", "x", "yy", "zzz", "memory", "paging",
            "frame", "heap", "gc", "lambda", "stack", "boot", "limine", "serial", "idt", "gdt", "tss"};
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], i);
        }
        map.remove("x");
        map.put("duke", 99);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            sb.append(e.getKey()).append(e.getValue()).append(',');
        }
        return sb + " " + map.size();
    }

    static String integerKeysAcrossResizes() {
        Map<Integer, String> map = new HashMap<>();
        for (int i = 0; i < 100; i += 7) {
            map.put(i * 31, "v" + i);
        }
        return map.keySet().toString();
    }

    static String mapToStringAndViews() {
        Map<String, Integer> map = new HashMap<>();
        map.put("one", 1);
        map.put("two", 2);
        map.put("three", 3);
        return map + " " + map.keySet() + " " + map.values() + " " + map.containsValue(2) + " " + map.get("four")
            + " " + map.getOrDefault("four", 4);
    }

    static String mapDefaultMethods() {
        Map<String, Integer> counts = new HashMap<>();
        for (String w : new String[] {"a", "b", "a", "c", "a", "b"}) {
            counts.merge(w, 1, Integer::sum);
        }
        Map<Integer, List<String>> byLength = new HashMap<>();
        for (String w : new String[] {"kernel", "duke", "gc", "java", "heap"}) {
            byLength.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
        }
        counts.putIfAbsent("d", 7);
        counts.putIfAbsent("a", 0);
        return counts + " " + byLength;
    }

    static String mapEqualityHashAndNullKeys() {
        Map<String, String> a = new HashMap<>();
        a.put(null, "nullkey");
        a.put("k", null);
        Map<String, String> b = new HashMap<>(a);
        return a.equals(b) + " " + a.hashCode() + " " + a.get(null) + " " + a.containsKey("k") + " " + a;
    }

    static String presizedMapsIterateLikeTheJdk() {
        Map<Integer, Integer> source = new HashMap<>();
        for (int i = 0; i < 40; i++) {
            source.put(i * 1000, i);
        }
        Map<Integer, Integer> copy = new HashMap<>(source);
        Map<Integer, Integer> small = new HashMap<>(2);
        for (int i = 0; i < 10; i++) {
            small.put(i * 17, i);
        }
        return copy.keySet() + " " + small.keySet();
    }

    static String mapIteratorRemove() {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            map.put(i, i * i);
        }
        for (Iterator<Map.Entry<Integer, Integer>> it = map.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getKey() % 2 == 0) {
                it.remove();
            }
        }
        String cme = "none";
        try {
            for (Integer k : map.keySet()) {
                map.put(k + 1000, 0);
            }
        } catch (ConcurrentModificationException e) {
            cme = "cme";
        }
        return map.size() + " " + cme;
    }

    static String hashSetBasics() {
        Set<String> set = new HashSet<>(Arrays.asList("c", "a", "b", "a", "d"));
        set.remove("d");
        Set<String> other = new HashSet<>();
        other.add("a");
        other.add("b");
        other.add("c");
        return set + " " + set.size() + " " + set.contains("a") + " " + set.equals(other) + " " + (set.hashCode() == other.hashCode());
    }

    static String forEachAndLambdas() {
        StringBuilder sb = new StringBuilder();
        List<Integer> list = new ArrayList<>(Arrays.asList(3, 1, 2));
        list.forEach(i -> sb.append(i * 10).append(' '));
        Map<String, Integer> map = new HashMap<>();
        map.put("x", 1);
        map.put("y", 2);
        map.forEach((k, v) -> sb.append(k).append(v));
        list.replaceAll(i -> i + 100);
        return sb + " " + list;
    }

    static String arraysUtilities() {
        int[] filled = new int[4];
        Arrays.fill(filled, 7);
        int[] grown = Arrays.copyOf(filled, 6);
        String[] names = {"x", "y"};
        String[] moreNames = Arrays.copyOf(names, 3);
        return Arrays.toString(grown) + " " + Arrays.toString(moreNames) + " " + moreNames.getClass().getSimpleName()
            + " " + Arrays.equals(filled, new int[] {7, 7, 7, 7}) + " " + Arrays.hashCode(filled)
            + " " + Arrays.toString(Arrays.copyOfRange(grown, 2, 5));
    }

    static String unmodifiableAndAsList() {
        List<String> fixed = Arrays.asList("a", "b");
        fixed.set(1, "B");
        StringBuilder sb = new StringBuilder(fixed.toString());
        try {
            fixed.add("c");
        } catch (UnsupportedOperationException e) {
            sb.append(" uoe ").append(e.getMessage());
        }
        try {
            Collections.unmodifiableList(fixed).set(0, "z");
        } catch (UnsupportedOperationException e) {
            sb.append(" uoe2");
        }
        List<Integer> nums = new ArrayList<>(Arrays.asList(1, 2, 3, 4));
        Collections.reverse(nums);
        Collections.swap(nums, 0, 3);
        return sb + " " + nums + " " + Collections.emptyList().size() + " " + Collections.emptyList();
    }

    static String arrayDequeBasics() {
        Deque<String> d = new ArrayDeque<>();
        d.addLast("b");
        d.addLast("c");
        d.addFirst("a");
        d.offerLast("d");
        d.push("z");
        return d + " " + d.size() + " " + d.peekFirst() + " " + d.peekLast() + " "
            + d.pop() + " " + d.pollLast() + " " + d.removeFirst() + " " + d;
    }

    static String arrayDequeGrowsAndWraps() {
        Deque<Integer> d = new ArrayDeque<>();
        for (int i = 0; i < 5; i++) {
            d.addLast(i);
        }
        for (int i = 0; i < 3; i++) {
            d.removeFirst();
        }
        for (int i = 10; i < 30; i++) {
            d.addLast(i);
        }
        d.addFirst(-1);
        StringBuilder sb = new StringBuilder();
        for (Integer x : d) {
            sb.append(x).append(',');
        }
        return sb + " " + d.size() + " " + d.getFirst() + " " + d.getLast();
    }

    static String arrayDequeEmpty() {
        Deque<String> d = new ArrayDeque<>();
        StringBuilder sb = new StringBuilder();
        sb.append(d.isEmpty()).append(' ').append(d.pollFirst()).append(' ').append(d.peekLast()).append(' ');
        try {
            d.removeFirst();
        } catch (NoSuchElementException e) {
            sb.append("nse ");
        }
        try {
            d.pop();
        } catch (NoSuchElementException e) {
            sb.append("nse2");
        }
        return sb.toString();
    }

    static String arrayDequeRejectsNull() {
        String[] names = {"addFirst", "addLast", "offerFirst", "offerLast", "push", "add", "offer"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            Deque<String> d = new ArrayDeque<>();
            d.addLast("a");
            d.addLast("b");
            String before = d + " " + d.size();
            String outcome;
            try {
                addNull(d, i);
                outcome = "accepted";
            } catch (NullPointerException e) {
                outcome = "NullPointerException";
            }
            String after = d + " " + d.size();
            sb.append(names[i]).append(' ').append(outcome).append(' ')
                .append(before.equals(after) ? "unchanged" : "changed:" + after).append(';');
        }
        return sb.toString();
    }

    private static void addNull(Deque<String> d, int which) {
        switch (which) {
            case 0: d.addFirst(null); break;
            case 1: d.addLast(null); break;
            case 2: d.offerFirst(null); break;
            case 3: d.offerLast(null); break;
            case 4: d.push(null); break;
            case 5: d.add(null); break;
            default: d.offer(null); break;
        }
    }

    static String selfReferenceToString() {
        List<Object> list = new ArrayList<>();
        list.add(1);
        list.add(list);
        Map<Object, Object> map = new HashMap<>();
        map.put("me", map);
        return list + " " + map;
    }
}
