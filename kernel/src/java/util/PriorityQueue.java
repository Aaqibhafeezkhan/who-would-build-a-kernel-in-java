package java.util;

public class PriorityQueue<E> extends AbstractCollection<E> implements Queue<E> {
    private static final int DEFAULT_CAPACITY = 11;

    private Object[] elements;
    private int size;
    private final Comparator<? super E> comparator;

    public PriorityQueue() {
        this(DEFAULT_CAPACITY, null);
    }

    public PriorityQueue(Comparator<? super E> comparator) {
        this(DEFAULT_CAPACITY, comparator);
    }

    private PriorityQueue(int initialCapacity, Comparator<? super E> comparator) {
        elements = new Object[initialCapacity];
        this.comparator = comparator;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        grow(size + 1);
        int i = size++;
        if (comparator == null) {
            siftUpComparable(i, e);
        } else {
            siftUpComparator(i, e);
        }
        return true;
    }

    @Override
    public boolean add(E e) {
        return offer(e);
    }

    @Override
    @SuppressWarnings("unchecked")
    public E peek() {
        return size == 0 ? null : (E) elements[0];
    }

    @Override
    @SuppressWarnings("unchecked")
    public E poll() {
        if (size == 0) return null;
        int last = --size;
        E result = (E) elements[0];
        E x = (E) elements[last];
        elements[last] = null;
        if (last != 0) {
            if (comparator == null) {
                siftDownComparable(0, x);
            } else {
                siftDownComparator(0, x);
            }
        }
        return result;
    }

    public E remove() {
        E result = poll();
        if (result == null) throw new NoSuchElementException();
        return result;
    }

    @Override
    public boolean remove(Object value) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(value, elements[i])) {
                removeAt(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public void clear() {
        for (int i = 0; i < size; i++) {
            elements[i] = null;
        }
        size = 0;
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            @SuppressWarnings("unchecked")
            public E next() {
                if (!hasNext()) throw new NoSuchElementException();
                return (E) elements[index++];
            }
        };
    }

    private void grow(int minCapacity) {
        if (minCapacity <= elements.length) return;
        int oldCapacity = elements.length;
        int newCapacity = oldCapacity + (oldCapacity < 64 ? oldCapacity + 2 : oldCapacity >> 1);
        if (newCapacity < minCapacity) newCapacity = minCapacity;
        Object[] bigger = new Object[newCapacity];
        System.arraycopy(elements, 0, bigger, 0, size);
        elements = bigger;
    }

    @SuppressWarnings("unchecked")
    private E removeAt(int index) {
        int last = --size;
        E removed = (E) elements[index];
        E moved = (E) elements[last];
        elements[last] = null;
        if (last != index) {
            if (comparator == null) {
                siftDownComparable(index, moved);
            } else {
                siftDownComparator(index, moved);
            }
            if (elements[index] == moved) {
                if (comparator == null) {
                    siftUpComparable(index, moved);
                } else {
                    siftUpComparator(index, moved);
                }
            }
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    private void siftUpComparable(int index, E value) {
        Comparable<? super E> key = (Comparable<? super E>) value;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Object parentValue = elements[parent];
            if (key.compareTo((E) parentValue) >= 0) break;
            elements[index] = parentValue;
            index = parent;
        }
        elements[index] = value;
    }

    @SuppressWarnings("unchecked")
    private void siftUpComparator(int index, E value) {
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            Object parentValue = elements[parent];
            if (comparator.compare(value, (E) parentValue) >= 0) break;
            elements[index] = parentValue;
            index = parent;
        }
        elements[index] = value;
    }

    @SuppressWarnings("unchecked")
    private void siftDownComparable(int index, E value) {
        Comparable<? super E> key = (Comparable<? super E>) value;
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            Object childValue = elements[child];
            int right = child + 1;
            if (right < size && ((Comparable<? super E>) childValue).compareTo((E) elements[right]) > 0) {
                child = right;
                childValue = elements[child];
            }
            if (key.compareTo((E) childValue) <= 0) break;
            elements[index] = childValue;
            index = child;
        }
        elements[index] = value;
    }

    @SuppressWarnings("unchecked")
    private void siftDownComparator(int index, E value) {
        int half = size >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            Object childValue = elements[child];
            int right = child + 1;
            if (right < size && comparator.compare((E) childValue, (E) elements[right]) > 0) {
                child = right;
                childValue = elements[child];
            }
            if (comparator.compare(value, (E) childValue) <= 0) break;
            elements[index] = childValue;
            index = child;
        }
        elements[index] = value;
    }
}
