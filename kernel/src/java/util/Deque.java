package java.util;

public interface Deque<E> extends Queue<E> {
    void addFirst(E e);
    void addLast(E e);
    boolean offerFirst(E e);
    boolean offerLast(E e);
    E removeFirst();
    E removeLast();
    E pollFirst();
    E pollLast();
    E peekFirst();
    E peekLast();
    E getFirst();
    E getLast();
    void push(E e);
    E pop();
}
