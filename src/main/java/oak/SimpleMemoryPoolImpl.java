package oak;

import javafx.util.Pair;
import sun.misc.Cleaner;

import java.awt.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

class SimpleMemoryPoolImpl implements MemoryPool {

    final ByteBuffer largeBuffer; // TODO add more buffers
    final AtomicInteger allocated;
    final int capacity;
    final ArrayList<LinkedList<Pair<Integer, ByteBuffer>>> freeIntArray;
    final ArrayList<LinkedList<Pair<Integer, ByteBuffer>>> freeKeysArray;

    SimpleMemoryPoolImpl(long capacity) {
        assert capacity > 0;
        assert capacity <= Integer.MAX_VALUE;
        this.capacity = (int) capacity;
        allocated = new AtomicInteger();
        largeBuffer = ByteBuffer.allocateDirect(this.capacity);
        freeIntArray = new ArrayList<>(Chunk.MAX_THREADS);
        for (int i = 0; i < Chunk.MAX_THREADS; i++) {
            freeIntArray.add(i, new LinkedList<>());
        }
        freeKeysArray = new ArrayList<>(Chunk.MAX_THREADS);
        for (int i = 0; i < Chunk.MAX_THREADS; i++) {
            freeKeysArray.add(i, new LinkedList<>());
        }
    }

    @Override
    public Pair<Integer, ByteBuffer> allocate(int capacity) {
        if (capacity == Integer.BYTES) {
            int idx = Chunk.getIndex();
            LinkedList<Pair<Integer, ByteBuffer>> myList = freeIntArray.get(idx);
            if (myList.size() > 0) {
                Pair<Integer, ByteBuffer> pair = myList.removeFirst();
                assert pair != null;
                assert pair.getKey() == 0;
                assert pair.getValue().remaining() == 4;
                return pair;
            }
        }
        if (capacity == Chunk.MAX_ITEMS * 100) {
            int idx = Chunk.getIndex();
            LinkedList<Pair<Integer, ByteBuffer>> myList = freeKeysArray.get(idx);
            if (myList.size() > 0) {
                Pair<Integer, ByteBuffer> pair = myList.removeFirst();
                assert pair != null;
                assert pair.getKey() == 0;
                assert pair.getValue().remaining() == Chunk.MAX_ITEMS * 100;
                return pair;
            }
        }
        int now = allocated.getAndAdd(capacity);
        if (now + capacity > this.capacity || capacity > Integer.MAX_VALUE - now) {
            throw new OakOutOfMemoryException();
        }
        ByteBuffer bb = largeBuffer.duplicate();
        bb.position(now);
        bb.limit(now + capacity);
        bb = bb.slice();
        assert bb.position() == 0;
        return new Pair<>(0, bb);
    }

    @Override
    public void free(int i, ByteBuffer bb) {
        // TODO freelist
        int idx = Chunk.getIndex();
        if (bb.remaining() == Integer.BYTES) {
            LinkedList<Pair<Integer, ByteBuffer>> myList = freeIntArray.get(idx);
            myList.add(new Pair<>(i, bb));
        }
        if (bb.remaining() == Chunk.MAX_ITEMS * 100) { // keys size
            LinkedList<Pair<Integer, ByteBuffer>> myList = freeKeysArray.get(idx);
            myList.add(new Pair<>(i, bb));
        }
    }

    @Override
    public long allocated() {
        return allocated.get();
    }

    @Override
    public void clean() {
        Field cleanerField = null;
        try {
            cleanerField = largeBuffer.getClass().getDeclaredField("cleaner");
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        cleanerField.setAccessible(true);
        Cleaner cleaner = null;
        try {
            cleaner = (Cleaner) cleanerField.get(largeBuffer);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        cleaner.clean();
    }
}