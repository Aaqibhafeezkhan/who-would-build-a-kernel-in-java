package java.util;

public class LinkedList<E> extends AbstractList<E> implements List<E>, Deque<E> {

    private static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;

        Node(Node<E> prev, E item, Node<E> next) {
            this.item = item;
            this.prev = prev;
            this.next = next;
        }
    }

    private Node<E> first;
    private Node<E> last;
    private int size;

    public LinkedList() {
    }

    // ── size ────────────────────────────────────────────────────────────

    @Override
    public int size() {
        return size;
    }

    // ── Deque end-access ────────────────────────────────────────────────

    @Override
    public void addFirst(E e) {
        Node<E> f = first;
        Node<E> node = new Node<>(null, e, f);
        first = node;
        if (f == null) {
            last = node;
        } else {
            f.prev = node;
        }
        size++;
        modCount++;
    }

    @Override
    public void addLast(E e) {
        Node<E> l = last;
        Node<E> node = new Node<>(l, e, null);
        last = node;
        if (l == null) {
            first = node;
        } else {
            l.next = node;
        }
        size++;
        modCount++;
    }

    @Override
    public E removeFirst() {
        if (size == 0) throw new NoSuchElementException();
        return unlinkFirst();
    }

    @Override
    public E removeLast() {
        if (size == 0) throw new NoSuchElementException();
        return unlinkLast();
    }

    @Override
    public E getFirst() {
        if (size == 0) throw new NoSuchElementException();
        return first.item;
    }

    @Override
    public E getLast() {
        if (size == 0) throw new NoSuchElementException();
        return last.item;
    }

    @Override
    public E peekFirst() {
        return size == 0 ? null : first.item;
    }

    @Override
    public E peekLast() {
        return size == 0 ? null : last.item;
    }

    @Override
    public boolean offerFirst(E e) { addFirst(e); return true; }

    @Override
    public boolean offerLast(E e) { addLast(e); return true; }

    @Override
    public E pollFirst() { return size == 0 ? null : unlinkFirst(); }

    @Override
    public E pollLast() { return size == 0 ? null : unlinkLast(); }

    @Override
    public void push(E e) { addFirst(e); }

    @Override
    public E pop() { return removeFirst(); }

    // ── Queue ───────────────────────────────────────────────────────────

    @Override
    public boolean offer(E e) { return offerLast(e); }

    @Override
    public E poll() { return pollFirst(); }

    @Override
    public E peek() { return peekFirst(); }

    // ── List (indexed access) ───────────────────────────────────────────

    @Override
    public boolean add(E e) {
        addLast(e);
        return true;
    }

    @Override
    public E get(int index) {
        Objects.checkIndex(index, size);
        return nodeAt(index).item;
    }

    @Override
    public E set(int index, E element) {
        Objects.checkIndex(index, size);
        Node<E> node = nodeAt(index);
        E old = node.item;
        node.item = element;
        return old;
    }

    @Override
    public void add(int index, E element) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        if (index == size) {
            addLast(element);
        } else {
            Node<E> succ = nodeAt(index);
            Node<E> pred = succ.prev;
            Node<E> node = new Node<>(pred, element, succ);
            succ.prev = node;
            if (pred == null) {
                first = node;
            } else {
                pred.next = node;
            }
            size++;
            modCount++;
        }
    }

    @Override
    public E remove(int index) {
        Objects.checkIndex(index, size);
        return unlink(nodeAt(index));
    }

    @Override
    public boolean remove(Object o) {
        for (Node<E> n = first; n != null; n = n.next) {
            if (Objects.equals(o, n.item)) {
                unlink(n);
                return true;
            }
        }
        return false;
    }

    @Override
    public int indexOf(Object o) {
        int i = 0;
        for (Node<E> n = first; n != null; n = n.next) {
            if (Objects.equals(o, n.item)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        int i = size - 1;
        for (Node<E> n = last; n != null; n = n.prev) {
            if (Objects.equals(o, n.item)) {
                return i;
            }
            i--;
        }
        return -1;
    }

    @Override
    public void clear() {
        for (Node<E> n = first; n != null; ) {
            Node<E> next = n.next;
            n.item = null;
            n.prev = null;
            n.next = null;
            n = next;
        }
        first = null;
        last = null;
        size = 0;
        modCount++;
    }

    // ── Iterator (with working remove) ──────────────────────────────────

    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    private final class Itr implements Iterator<E> {
        private Node<E> next = first;
        private Node<E> lastReturned;
        private int expectedModCount = modCount;

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public E next() {
            checkForComodification();
            if (next == null) {
                throw new NoSuchElementException();
            }
            lastReturned = next;
            next = next.next;
            return lastReturned.item;
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            checkForComodification();
            unlink(lastReturned);
            lastReturned = null;
            expectedModCount = modCount;
        }

        private void checkForComodification() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
        }
    }

    // ── internal helpers ────────────────────────────────────────────────

    private Node<E> nodeAt(int index) {
        if (index < (size >> 1)) {
            Node<E> n = first;
            for (int i = 0; i < index; i++) {
                n = n.next;
            }
            return n;
        } else {
            Node<E> n = last;
            for (int i = size - 1; i > index; i--) {
                n = n.prev;
            }
            return n;
        }
    }

    private E unlinkFirst() {
        Node<E> f = first;
        E item = f.item;
        Node<E> next = f.next;
        f.item = null;
        f.next = null;
        first = next;
        if (next == null) {
            last = null;
        } else {
            next.prev = null;
        }
        size--;
        modCount++;
        return item;
    }

    private E unlinkLast() {
        Node<E> l = last;
        E item = l.item;
        Node<E> prev = l.prev;
        l.item = null;
        l.prev = null;
        last = prev;
        if (prev == null) {
            first = null;
        } else {
            prev.next = null;
        }
        size--;
        modCount++;
        return item;
    }

    private E unlink(Node<E> node) {
        E item = node.item;
        Node<E> prev = node.prev;
        Node<E> next = node.next;
        if (prev == null) {
            first = next;
        } else {
            prev.next = next;
            node.prev = null;
        }
        if (next == null) {
            last = prev;
        } else {
            next.prev = prev;
            node.next = null;
        }
        node.item = null;
        size--;
        modCount++;
        return item;
    }
}
