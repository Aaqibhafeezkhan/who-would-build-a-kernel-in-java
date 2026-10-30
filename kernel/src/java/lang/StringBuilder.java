package java.lang;

/** Latin-1 like String. Also what javac emits for {@code +} on strings (-XDstringConcat=inline). */
public final class StringBuilder {

    private byte[] value;
    private int count;

    public StringBuilder() {
        this(16);
    }

    public StringBuilder(int capacity) {
        value = new byte[capacity];
    }

    public StringBuilder(String initial) {
        this(initial.length() + 16);
        append(initial);
    }

    public StringBuilder append(String s) {
        if (s == null) {
            s = "null";
        }
        int n = s.length();
        ensureCapacity(count + n);
        s.copyBytes(value, count);
        count += n;
        return this;
    }

    public StringBuilder append(Object o) {
        return append(String.valueOf(o));
    }

    public StringBuilder append(char c) {
        if (c > 0xFF) {
            throw new IllegalArgumentException("non-Latin-1 char " + (int) c + " (Strings are Latin-1 only for now)");
        }
        ensureCapacity(count + 1);
        value[count++] = (byte) c;
        return this;
    }

    public StringBuilder append(int i) {
        return append(Integer.toString(i));
    }

    public StringBuilder append(long l) {
        return append(Long.toString(l));
    }

    public StringBuilder append(float f) {
        return append(Float.toString(f));
    }

    public StringBuilder append(double d) {
        return append(Double.toString(d));
    }

    public StringBuilder append(boolean b) {
        return append(b ? "true" : "false");
    }

    public int length() {
        return count;
    }

    public char charAt(int index) {
        checkIndex(index);
        return (char) (value[index] & 0xFF);
    }

    public void setCharAt(int index, char c) {
        checkIndex(index);
        value[index] = (byte) c;
    }

    public void setLength(int length) {
        if (length < 0) {
            throw new StringIndexOutOfBoundsException("String index out of range: " + length);
        }
        ensureCapacity(length);
        for (int i = count; i < length; i++) {
            value[i] = 0;
        }
        count = length;
    }

    public StringBuilder reverse() {
        for (int i = 0, j = count - 1; i < j; i++, j--) {
            byte t = value[i];
            value[i] = value[j];
            value[j] = t;
        }
        return this;
    }

    @Override
    public String toString() {
        byte[] bytes = new byte[count];
        System.arraycopy(value, 0, bytes, 0, count);
        return new String(bytes);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new StringIndexOutOfBoundsException("Index " + index + " out of bounds for length " + count);
        }
    }

    private void ensureCapacity(int needed) {
        if (needed > value.length) {
            byte[] grown = new byte[Math.max(needed, value.length * 2 + 2)];
            System.arraycopy(value, 0, grown, 0, count);
            value = grown;
        }
    }
    public StringBuilder insert(int index, char c) {
        if (index < 0 || index > count) {
            throw new StringIndexOutOfBoundsException("Range [" + index + ", " + count + ") out of bounds for length " + count);
        }
        if (c > 0xFF) {
            throw new IllegalArgumentException("non-Latin-1 char " + (int) c + " (Strings are Latin-1 only for now)");
        }
        ensureCapacity(count + 1);
        System.arraycopy(value, index, value, index + 1, count - index);
        value[index] = (byte) c;
        count++;
        return this;
    }

    public StringBuilder deleteCharAt(int index) {
        checkIndex(index);
        System.arraycopy(value, index + 1, value, index, count - index - 1);
        value[--count] = 0;
        return this;
    }
}
