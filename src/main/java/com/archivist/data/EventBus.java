package com.archivist.data;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe event bus for distributing {@link LogEvent}s to registered listeners.
 */
public final class EventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    private static final int MAX_EVENTS = 500;
    private final CopyOnWriteArrayList<LogEvent> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<LogEvent>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Posts an event, storing it and notifying all listeners.
     */
    public void post(LogEvent event) {
        if (event == null) return;
        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        for (Consumer<LogEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                LOGGER.error("EventBus listener threw while handling {}", event, e);
            }
        }
    }

    /**
     * Registers a listener to be notified of future events.
     */
    public void addListener(Consumer<LogEvent> listener) {
        if (listener == null) return;
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeListener(Consumer<LogEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * Returns an unmodifiable view of all posted events.
     */
    public List<LogEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * Clears all events but retains listeners.
     */
    public void reset() {
        events.clear();
    }

}
