package com.dwinovo.animus.pathing.calc;

import java.util.Arrays;

/**
 * A binary min-heap open set ordered by {@link PathNode#combinedCost}, with an
 * O(log n) decrease-key. Ported from Baritone's {@code BinaryHeapOpenSet}.
 *
 * <p>The reason for a hand-rolled heap rather than {@link java.util.PriorityQueue}:
 * A* relaxes the same node many times, each time potentially lowering its cost.
 * {@code PriorityQueue} has no decrease-key — you'd either re-insert duplicates
 * (and lazily skip stale pops) or pay an O(n) {@code remove}. Here each node
 * caches its {@link PathNode#heapPosition}, so lowering a key is a pure
 * O(log n) sift-up with no search. 1-indexed so parent/child math is shifts.
 */
final class BinaryHeapOpenSet {

    private static final int INITIAL_CAPACITY = 1024;

    private PathNode[] array = new PathNode[INITIAL_CAPACITY];
    private int size = 0;

    boolean isEmpty() {
        return size == 0;
    }

    /** Add a node not currently in the set, then sift it up. */
    void insert(PathNode node) {
        if (size + 1 >= array.length) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        array[size] = node;
        node.heapPosition = size;
        siftUp(size);
    }

    /** Decrease-key: a node already in the set got a lower combinedCost. */
    void update(PathNode node) {
        siftUp(node.heapPosition);
    }

    /** Remove and return the lowest-combinedCost node. */
    PathNode removeLowest() {
        PathNode result = array[1];
        result.heapPosition = -1;
        size--;
        if (size > 0) {
            PathNode moved = array[size + 1];
            array[size + 1] = null;
            array[1] = moved;
            moved.heapPosition = 1;
            siftDown(1);
        } else {
            array[1] = null;
        }
        return result;
    }

    private void siftUp(int index) {
        PathNode node = array[index];
        while (index > 1) {
            int parent = index >>> 1;
            PathNode p = array[parent];
            if (node.combinedCost >= p.combinedCost) {
                break;
            }
            array[index] = p;
            p.heapPosition = index;
            index = parent;
        }
        array[index] = node;
        node.heapPosition = index;
    }

    private void siftDown(int index) {
        PathNode node = array[index];
        while (true) {
            int child = index << 1;
            if (child > size) {
                break;
            }
            if (child + 1 <= size && array[child + 1].combinedCost < array[child].combinedCost) {
                child++;
            }
            PathNode c = array[child];
            if (node.combinedCost <= c.combinedCost) {
                break;
            }
            array[index] = c;
            c.heapPosition = index;
            index = child;
        }
        array[index] = node;
        node.heapPosition = index;
    }
}
