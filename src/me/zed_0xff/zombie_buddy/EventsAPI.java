package me.zed_0xff.zombie_buddy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EventsAPI {
    private static final ConcurrentHashMap<String, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    public static void on(String event, Consumer<Object> listener) {
        if (event != null && listener != null) {
            listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(listener);
        }
    }

    public static void trigger(String event, Object data) {
        if (event != null) {
            List<Consumer<Object>> list = listeners.get(event);
            if (list != null) {
                for (Consumer<Object> l : list) {
                    try {
                        l.accept(data);
                    } catch (Throwable t) {
                        Logger.error("Error invoking event listener for " + event, t);
                    }
                }
            }
        }
    }
}
