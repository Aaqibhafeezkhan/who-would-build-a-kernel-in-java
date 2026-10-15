package java.util;

public class ArrayDeque<E> extends AbstractCollection<E> implements Deque<E> {
    private Object[] elements;
    private int head;
    private int size;

    public ArrayDeque() {
        elements = new Object[8];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    private void grow() {
        Object[] bigger = new Object[elements.length * 2];
        for (int i = 0; i < size; i++) {
            bigger[i] = elements[(head + i) % elements.length];
        }
        elements = bigger;
        head = 0;
    }

    // Null is rejected before anything is touched, so a failed add leaves the deque unchanged.
    // pollFirst and peekFirst return null to mean "empty", so a stored null would be ambiguous.
    // Every other adding method (offerFirst, offerLast, push, add, offer) ends up in addFirst or addLast.
    public void addFirst(E e) {
        if (e == null) throw new NullPointerException();
        if (size == elements.length) grow();
        head = (head - 1 + elements.length) % elements.length;
        elements[head] = e;
        size++;
    }

    public void addLast(E e) {
        if (e == null) throw new NullPointerException();
        if (size == elements.length) grow();
        elements[(head + size) % elements.length] = e;
        size++;
    }

    @SuppressWarnings("unchecked")
    public E removeFirst() {
        if (size == 0) throw new NoSuchElementException();
        E item = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        return item;
    }

    @SuppressWarnings("unchecked")
    public E removeLast() {
        if (size == 0) throw new NoSuchElementException();
        int tail = (head + size - 1) % elements.length;
        E item = (E) elements[tail];
        elements[tail] = null;
        size--;
        return item;
    }

    @SuppressWarnings("unchecked")
    public E peekFirst() {
        if (size == 0) return null;
        return (E) elements[head];
    }

    @SuppressWarnings("unchecked")
    public E peekLast() {
        if (size == 0) return null;
        return (E) elements[(head + size - 1) % elements.length];
    }

    @SuppressWarnings("unchecked")
    public E getFirst() {
        if (size == 0) throw new NoSuchElementException();
        return (E) elements[head];
    }

    @SuppressWarnings("unchecked")
    public E getLast() {
        if (size == 0) throw new NoSuchElementException();
        return (E) elements[(head + size - 1) % elements.length];
    }


    public boolean offerFirst(E e) { addFirst(e); return true; }
    public boolean offerLast(E e)  { addLast(e); return true; }
    public E pollFirst() { return size == 0 ? null : removeFirst(); }
    public E pollLast()  { return size == 0 ? null : removeLast(); }
    public void push(E e) { addFirst(e); }
    public E pop() { return removeFirst(); }

    public boolean add(E e) { addLast(e); return true; }
    public boolean offer(E e) { return offerLast(e); }
    public E poll() { return pollFirst(); }
    public E peek() { return peekFirst(); }

    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int i = 0;

            public boolean hasNext() {
                return i < size;
            }

            @SuppressWarnings("unchecked")
            public E next() {
                if (i >= size) throw new NoSuchElementException();
                return (E) elements[(head + i++) % elements.length];
            }
        };
    }
}
