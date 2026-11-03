package java.util;

/**
 * A hash table with a doubly linked list running through its entries. Iteration follows insertion
 * order by default; the optional access-order mode moves entries to the end when they are accessed.
 */
public class LinkedHashMap<K, V> extends HashMap<K, V> {

    private static final class LinkedNode<K, V> extends HashMap.Node<K, V> {
        LinkedNode<K, V> before;
        LinkedNode<K, V> after;

        LinkedNode(int hash, K key, V value, HashMap.Node<K, V> next) {
            super(hash, key, value, next);
        }
    }

    private LinkedNode<K, V> head;
    private LinkedNode<K, V> tail;
    private final boolean accessOrder;

    public LinkedHashMap() {
        this(false);
    }

    public LinkedHashMap(int initialCapacity) {
        this(initialCapacity, false);
    }

    public LinkedHashMap(int initialCapacity, boolean accessOrder) {
        super(initialCapacity);
        this.accessOrder = accessOrder;
    }

    public LinkedHashMap(Map<? extends K, ? extends V> map) {
        super(map);
        this.accessOrder = false;
    }

    @Override
    HashMap.Node<K, V> newNode(int hash, K key, V value, HashMap.Node<K, V> next) {
        LinkedNode<K, V> node = new LinkedNode<>(hash, key, value, next);
        node.before = tail;
        if (tail == null) {
            head = node;
        } else {
            tail.after = node;
        }
        tail = node;
        return node;
    }

    @Override
    void afterNodeAccess(HashMap.Node<K, V> node) {
        if (!accessOrder) {
            return;
        }
        LinkedNode<K, V> entry = (LinkedNode<K, V>) node;
        if (tail == entry) {
            return;
        }
        LinkedNode<K, V> before = entry.before;
        LinkedNode<K, V> after = entry.after;
        entry.after = null;
        entry.before = tail;
        if (before == null) {
            head = after;
        } else {
            before.after = after;
        }
        after.before = before;
        tail.after = entry;
        tail = entry;
        modCount++;
    }

    @Override
    void afterNodeInsertion(boolean evict) {
        LinkedNode<K, V> eldest = head;
        if (evict && eldest != null && removeEldestEntry(eldest)) {
            removeNode(eldest.key);
        }
    }

    @Override
    void afterNodeRemoval(HashMap.Node<K, V> node) {
        LinkedNode<K, V> entry = (LinkedNode<K, V>) node;
        LinkedNode<K, V> before = entry.before;
        LinkedNode<K, V> after = entry.after;
        entry.before = null;
        entry.after = null;
        if (before == null) {
            head = after;
        } else {
            before.after = after;
        }
        if (after == null) {
            tail = before;
        } else {
            after.before = before;
        }
    }

    /**
     * Called after a new entry is inserted. Override to remove the eldest entry when desired.
     */
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return false;
    }

    @Override
    public void clear() {
        super.clear();
        head = null;
        tail = null;
    }

    private abstract class LinkedIterator<T> implements Iterator<T> {
        private LinkedNode<K, V> next = head;
        private LinkedNode<K, V> current;
        private int expectedModCount = modCount;

        @Override
        public boolean hasNext() {
            return next != null;
        }

        final LinkedNode<K, V> nextNode() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (next == null) {
                throw new NoSuchElementException();
            }
            current = next;
            next = next.after;
            return current;
        }

        @Override
        public void remove() {
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
                return new LinkedIterator<>() {
                    @Override
                    public K next() {
                        return nextNode().key;
                    }
                };
            }

            @Override
            public int size() {
                return LinkedHashMap.this.size();
            }

            @Override
            public boolean contains(Object key) {
                return containsKey(key);
            }

            @Override
            public boolean remove(Object key) {
                return removeNode(key) != null;
            }

            @Override
            public void clear() {
                LinkedHashMap.this.clear();
            }
        };
    }

    @Override
    public Collection<V> values() {
        return new AbstractCollection<>() {
            @Override
            public Iterator<V> iterator() {
                return new LinkedIterator<>() {
                    @Override
                    public V next() {
                        return nextNode().value;
                    }
                };
            }

            @Override
            public int size() {
                return LinkedHashMap.this.size();
            }

            @Override
            public void clear() {
                LinkedHashMap.this.clear();
            }
        };
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
                return new LinkedIterator<>() {
                    @Override
                    public Map.Entry<K, V> next() {
                        return nextNode();
                    }
                };
            }

            @Override
            public int size() {
                return LinkedHashMap.this.size();
            }

            @Override
            public void clear() {
                LinkedHashMap.this.clear();
            }
        };
    }
}
