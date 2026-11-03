package java.util;

/**
 * The JDK's HashMap algorithm, so iteration order matches HotSpot's: hash spreading, power-of-two
 * tables, a 0.75 load factor, tail insertion, and order-preserving splits on resize. The JDK's
 * tree bins (buckets of 8+ colliding keys) are left out; they only matter for heavily colliding
 * keys, where iteration order then differs from the JDK's.
 */
public class HashMap<K, V> extends AbstractMap<K, V> {

    static final int MAXIMUM_CAPACITY = 1 << 30;
    static final int DEFAULT_CAPACITY = 16;

    static class Node<K, V> implements Map.Entry<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V newValue) {
            V old = value;
            value = newValue;
            return old;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Map.Entry<?, ?> e && Objects.equals(key, e.getKey()) && Objects.equals(value, e.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(key) ^ Objects.hashCode(value);
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    Node<K, V>[] table;
    int size;
    int modCount;
    /** The next size to resize at; before the table exists, the initial capacity (0 for the default). */
    int threshold;

    public HashMap() {
    }

    public HashMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Illegal initial capacity: " + initialCapacity);
        }
        threshold = tableSizeFor(initialCapacity);
    }

    public HashMap(Map<? extends K, ? extends V> m) {
        putMapEntries(m);
    }

    static int hash(Object key) {
        int h;
        return key == null ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    static int tableSizeFor(int capacity) {
        int n = -1 >>> Integer.numberOfLeadingZeros(capacity - 1);
        return n < 0 ? 1 : n >= MAXIMUM_CAPACITY ? MAXIMUM_CAPACITY : n + 1;
    }

    /** The JDK presizes from s / 0.75f + 1; for these magnitudes that's exactly s * 4 / 3 + 1. */
    private void putMapEntries(Map<? extends K, ? extends V> m) {
        int s = m.size();
        if (s > 0) {
            if (table == null) {
                long ft = (long) s * 4 / 3 + 1;
                int t = ft < MAXIMUM_CAPACITY ? (int) ft : MAXIMUM_CAPACITY;
                if (t > threshold) {
                    threshold = tableSizeFor(t);
                }
            } else {
                while (s > threshold && table.length < MAXIMUM_CAPACITY) {
                    resize();
                }
            }
            for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
                put(e.getKey(), e.getValue());
            }
        }
    }

    Node<K, V> newNode(int hash, K key, V value, Node<K, V> next) {
        return new Node<>(hash, key, value, next);
    }

    void afterNodeAccess(Node<K, V> node) {
    }

    void afterNodeInsertion(boolean evict) {
    }

    void afterNodeRemoval(Node<K, V> node) {
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public V get(Object key) {
        Node<K, V> e = getNode(key);
        if (e == null) {
            return null;
        }
        afterNodeAccess(e);
        return e.value;
    }

    @Override
    public boolean containsKey(Object key) {
        return getNode(key) != null;
    }

    private Node<K, V> getNode(Object key) {
        Node<K, V>[] tab = table;
        if (tab == null) {
            return null;
        }
        int h = hash(key);
        for (Node<K, V> e = tab[(tab.length - 1) & h]; e != null; e = e.next) {
            if (e.hash == h && Objects.equals(key, e.key)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public V put(K key, V value) {
        if (table == null) {
            resize();
        }
        int h = hash(key);
        int i = (table.length - 1) & h;
        Node<K, V> e = table[i];
        if (e == null) {
            table[i] = newNode(h, key, value, null);
        } else {
            while (true) {
                if (e.hash == h && Objects.equals(key, e.key)) {
                    V old = e.value;
                    e.value = value;
                    afterNodeAccess(e);
                    return old;
                }
                if (e.next == null) {
                    e.next = newNode(h, key, value, null);
                    break;
                }
                e = e.next;
            }
        }
        modCount++;
        if (++size > threshold) {
            resize();
        }
        afterNodeInsertion(true);
        return null;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Node<K, V>[] old = table;
        int oldCap = old == null ? 0 : old.length;
        int newCap;
        if (oldCap > 0) {
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return;
            }
            newCap = oldCap << 1;
        } else if (threshold > 0) {
            newCap = threshold;
        } else {
            newCap = DEFAULT_CAPACITY;
        }
        threshold = newCap < MAXIMUM_CAPACITY ? newCap / 4 * 3 : Integer.MAX_VALUE;
        if (newCap < 4) {
            // (int) (newCap * 0.75f) for the tiny tables tableSizeFor allows: 1 -> 0, 2 -> 1.
            threshold = newCap * 3 / 4;
        }
        Node<K, V>[] tab = (Node<K, V>[]) new Node[newCap];
        table = tab;
        if (old == null) {
            return;
        }
        // Each bucket splits into the one at the same index and the one oldCap above, keeping order.
        for (int j = 0; j < oldCap; j++) {
            Node<K, V> loHead = null, loTail = null, hiHead = null, hiTail = null;
            for (Node<K, V> e = old[j]; e != null; ) {
                Node<K, V> next = e.next;
                e.next = null;
                if ((e.hash & oldCap) == 0) {
                    if (loTail == null) {
                        loHead = e;
                    } else {
                        loTail.next = e;
                    }
                    loTail = e;
                } else {
                    if (hiTail == null) {
                        hiHead = e;
                    } else {
                        hiTail.next = e;
                    }
                    hiTail = e;
                }
                e = next;
            }
            tab[j] = loHead;
            tab[j + oldCap] = hiHead;
        }
    }

    @Override
    public V remove(Object key) {
        Node<K, V> e = removeNode(key);
        return e == null ? null : e.value;
    }

    Node<K, V> removeNode(Object key) {
        Node<K, V>[] tab = table;
        if (tab == null) {
            return null;
        }
        int h = hash(key);
        int i = (tab.length - 1) & h;
        Node<K, V> previous = null;
        for (Node<K, V> e = tab[i]; e != null; previous = e, e = e.next) {
            if (e.hash == h && Objects.equals(key, e.key)) {
                if (previous == null) {
                    tab[i] = e.next;
                } else {
                    previous.next = e.next;
                }
                modCount++;
                size--;
                afterNodeRemoval(e);
                return e;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        if (table != null && size > 0) {
            modCount++;
            size = 0;
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
        }
    }

    @Override
    public boolean containsValue(Object value) {
        for (Map.Entry<K, V> e : entrySet()) {
            if (Objects.equals(value, e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private abstract class HashIterator<T> implements Iterator<T> {
        Node<K, V> next;
        Node<K, V> current;
        int expectedModCount = modCount;
        int index;

        HashIterator() {
            advanceFrom(0);
        }

        private void advanceFrom(int start) {
            next = null;
            Node<K, V>[] tab = table;
            if (tab == null) {
                return;
            }
            for (index = start; index < tab.length; index++) {
                if (tab[index] != null) {
                    next = tab[index++];
                    return;
                }
            }
        }

        @Override
        public final boolean hasNext() {
            return next != null;
        }

        final Node<K, V> nextNode() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            Node<K, V> e = next;
            if (e == null) {
                throw new NoSuchElementException();
            }
            current = e;
            if (e.next != null) {
                next = e.next;
            } else {
                advanceFrom(index);
            }
            return e;
        }

        @Override
        public final void remove() {
            if (current == null) {
                throw new IllegalStateException();
            }
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            removeNode(current.key);
            current = null;
            expectedModCount = modCount;
        }
    }

    @Override
    public Set<K> keySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<K> iterator() {
                return new HashIterator<>() {
                    @Override
                    public K next() {
                        return nextNode().key;
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean contains(Object o) {
                return containsKey(o);
            }

            @Override
            public boolean remove(Object o) {
                return removeNode(o) != null;
            }
        };
    }

    @Override
    public Collection<V> values() {
        return new AbstractCollection<>() {
            @Override
            public Iterator<V> iterator() {
                return new HashIterator<>() {
                    @Override
                    public V next() {
                        return nextNode().value;
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
                return new HashIterator<>() {
                    @Override
                    public Map.Entry<K, V> next() {
                        return nextNode();
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}
