/*
 * Copyright (c) 1995, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package java.util;

/**
 * A growable vector of bits, packed into a {@code long[]}. Bit {@code i} lives in bit {@code i & 63}
 * of word {@code i >> 6}; the array grows as higher bits are set. {@code wordsInUse} counts the
 * words up to and including the highest non-zero one, so the trailing words are always zero and the
 * methods that scan bits can stop early.
 *
 * <p>{@code size()} is left out on purpose: it reports the capacity, which depends on how the words
 * grow rather than on the bits that are set.
 */
public class BitSet {

    private static final int ADDRESS_BITS_PER_WORD = 6;
    private static final int BITS_PER_WORD = 1 << ADDRESS_BITS_PER_WORD;
    private static final long WORD_MASK = 0xffffffffffffffffL;

    private long[] words;
    private int wordsInUse;

    public BitSet() {
        words = new long[1];
    }

    private static int wordIndex(int bitIndex) {
        return bitIndex >> ADDRESS_BITS_PER_WORD;
    }

    private void expandTo(int wordIndex) {
        int wordsRequired = wordIndex + 1;
        if (wordsInUse < wordsRequired) {
            ensureCapacity(wordsRequired);
            wordsInUse = wordsRequired;
        }
    }

    private void ensureCapacity(int wordsRequired) {
        if (words.length < wordsRequired) {
            words = Arrays.copyOf(words, Math.max(2 * words.length, wordsRequired));
        }
    }

    // After clearing the high bits of a word, the highest word in use may have become zero, so scan
    // back down to the new top.
    private void recalculateWordsInUse() {
        int i;
        for (i = wordsInUse - 1; i >= 0; i--) {
            if (words[i] != 0) {
                break;
            }
        }
        wordsInUse = i + 1;
    }

    public void set(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);
        }
        int wordIndex = wordIndex(bitIndex);
        expandTo(wordIndex);
        words[wordIndex] |= (1L << bitIndex);
    }

    public void set(int bitIndex, boolean value) {
        if (value) {
            set(bitIndex);
        } else {
            clear(bitIndex);
        }
    }

    public void clear(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);
        }
        int wordIndex = wordIndex(bitIndex);
        if (wordIndex >= wordsInUse) {
            return;
        }
        words[wordIndex] &= ~(1L << bitIndex);
        recalculateWordsInUse();
    }

    public void clear() {
        while (wordsInUse > 0) {
            words[--wordsInUse] = 0;
        }
    }

    public boolean get(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);
        }
        int wordIndex = wordIndex(bitIndex);
        return (wordIndex < wordsInUse) && ((words[wordIndex] & (1L << bitIndex)) != 0);
    }

    public void flip(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);
        }
        int wordIndex = wordIndex(bitIndex);
        expandTo(wordIndex);
        words[wordIndex] ^= (1L << bitIndex);
        recalculateWordsInUse();
    }

    public int cardinality() {
        int sum = 0;
        for (int i = 0; i < wordsInUse; i++) {
            sum += Long.bitCount(words[i]);
        }
        return sum;
    }

    public int length() {
        if (wordsInUse == 0) {
            return 0;
        }
        return BITS_PER_WORD * (wordsInUse - 1)
                + (BITS_PER_WORD - Long.numberOfLeadingZeros(words[wordsInUse - 1]));
    }

    public boolean isEmpty() {
        return wordsInUse == 0;
    }

    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        int u = wordIndex(fromIndex);
        if (u >= wordsInUse) {
            return -1;
        }
        // The high bits of fromIndex pick the word; the low bits mask off the bits before it.
        long word = words[u] & (WORD_MASK << fromIndex);
        while (true) {
            if (word != 0) {
                return (u * BITS_PER_WORD) + Long.numberOfTrailingZeros(word);
            }
            if (++u == wordsInUse) {
                return -1;
            }
            word = words[u];
        }
    }

    public int nextClearBit(int fromIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        int u = wordIndex(fromIndex);
        if (u >= wordsInUse) {
            return fromIndex;
        }
        long word = ~words[u] & (WORD_MASK << fromIndex);
        while (true) {
            if (word != 0) {
                return (u * BITS_PER_WORD) + Long.numberOfTrailingZeros(word);
            }
            if (++u == wordsInUse) {
                return wordsInUse * BITS_PER_WORD;
            }
            word = ~words[u];
        }
    }

    public void and(BitSet set) {
        if (this == set) {
            return;
        }
        while (wordsInUse > set.wordsInUse) {
            words[--wordsInUse] = 0;
        }
        for (int i = 0; i < wordsInUse; i++) {
            words[i] &= set.words[i];
        }
        recalculateWordsInUse();
    }

    public void or(BitSet set) {
        if (this == set) {
            return;
        }
        int wordsInCommon = Math.min(wordsInUse, set.wordsInUse);
        if (wordsInUse < set.wordsInUse) {
            ensureCapacity(set.wordsInUse);
            wordsInUse = set.wordsInUse;
        }
        for (int i = 0; i < wordsInCommon; i++) {
            words[i] |= set.words[i];
        }
        if (wordsInCommon < set.wordsInUse) {
            System.arraycopy(set.words, wordsInCommon, words, wordsInCommon, wordsInUse - wordsInCommon);
        }
        // or only sets bits, so the top word in use cannot have dropped; no recalculate needed.
    }

    public void xor(BitSet set) {
        int wordsInCommon = Math.min(wordsInUse, set.wordsInUse);
        if (wordsInUse < set.wordsInUse) {
            ensureCapacity(set.wordsInUse);
            wordsInUse = set.wordsInUse;
        }
        for (int i = 0; i < wordsInCommon; i++) {
            words[i] ^= set.words[i];
        }
        if (wordsInCommon < set.wordsInUse) {
            System.arraycopy(set.words, wordsInCommon, words, wordsInCommon, set.wordsInUse - wordsInCommon);
        }
        recalculateWordsInUse();
    }

    public void andNot(BitSet set) {
        for (int i = Math.min(wordsInUse, set.wordsInUse) - 1; i >= 0; i--) {
            words[i] &= ~set.words[i];
        }
        recalculateWordsInUse();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BitSet set)) {
            return false;
        }
        if (wordsInUse != set.wordsInUse) {
            return false;
        }
        for (int i = 0; i < wordsInUse; i++) {
            if (words[i] != set.words[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        long h = 1234;
        for (int i = wordsInUse; --i >= 0;) {
            h ^= words[i] * (i + 1);
        }
        return (int) ((h >> 32) ^ h);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        b.append('{');
        int i = nextSetBit(0);
        if (i != -1) {
            b.append(i);
            while (true) {
                if (++i < 0) {
                    break;
                }
                if ((i = nextSetBit(i)) < 0) {
                    break;
                }
                int endOfRun = nextClearBit(i);
                do {
                    b.append(", ").append(i);
                } while (++i != endOfRun);
            }
        }
        b.append('}');
        return b.toString();
    }
}
