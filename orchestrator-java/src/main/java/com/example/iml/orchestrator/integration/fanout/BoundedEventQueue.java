package com.example.iml.orchestrator.integration.fanout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class BoundedEventQueue {

    record Entry(FanOutEvent event, BucketRobotSignal bucketSignal) {
        static Entry ofEvent(FanOutEvent event) {
            return new Entry(event, null);
        }

        static Entry ofBucketSignal(BucketRobotSignal bucketSignal) {
            return new Entry(null, bucketSignal);
        }
    }

    private final int capacity;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private long droppedTotal;
    private long pushedTotal;

    BoundedEventQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized void offer(FanOutEvent event) {
        offerEntry(Entry.ofEvent(event));
    }

    synchronized void offerBucketSignal(BucketRobotSignal bucketSignal) {
        offerEntry(Entry.ofBucketSignal(bucketSignal));
    }

    private void offerEntry(Entry entry) {
        if (queue.size() >= capacity) {
            queue.pollFirst();
            droppedTotal++;
        }
        queue.offerLast(entry);
        pushedTotal++;
        notifyAll();
    }

    synchronized Entry takeEntry() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        return queue.pollFirst();
    }

    synchronized List<FanOutEvent> snapshot() {
        List<FanOutEvent> events = new ArrayList<>();
        for (Entry entry : queue) {
            if (entry.event() != null) {
                events.add(entry.event());
            }
        }
        return events;
    }

    synchronized int size() {
        return queue.size();
    }

    synchronized long droppedTotal() {
        return droppedTotal;
    }

    synchronized long pushedTotal() {
        return pushedTotal;
    }
}
