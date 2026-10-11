package org.acf.mtracker;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Alerts waiting to be shown on the full-screen alert screen.
 * Held in memory (they are live alerts, not history) — the feed itself is
 * always available inside the app.
 */
public final class Alerts {

    private static final Deque<AlertItem> QUEUE = new ArrayDeque<>();
    private static final int MAX = 10;

    private Alerts() {}

    public static synchronized void push(AlertItem item) {
        if (item == null) return;
        QUEUE.addLast(item);
        while (QUEUE.size() > MAX) QUEUE.pollFirst();
    }

    public static synchronized AlertItem poll() { return QUEUE.pollFirst(); }

    public static synchronized int size() { return QUEUE.size(); }

    /** Drop a queued alert that is already on screen (opened from a notification). */
    public static synchronized void removeId(long id) {
        for (java.util.Iterator<AlertItem> it = QUEUE.iterator(); it.hasNext(); ) {
            if (it.next().id == id) { it.remove(); return; }
        }
    }

    public static synchronized void clear() { QUEUE.clear(); }
}
