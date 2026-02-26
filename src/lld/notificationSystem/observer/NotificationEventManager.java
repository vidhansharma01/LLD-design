package lld.notificationSystem.observer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotificationEventManager {
    Map<String, List<NotificationObserver>> observers = new HashMap<>();

    public void subscribe(String eventType, NotificationObserver observer) {
        observers.computeIfAbsent(eventType, k -> new java.util.ArrayList<>()).add(observer);
    }

    public void unsubscribe(String eventType, NotificationObserver observer) {
        List<NotificationObserver> users = observers.get(eventType);
        if (users != null) {
            users.remove(observer);
        }
    }

    public void notify(String eventType, Object data) {
        List<NotificationObserver> users = observers.get(eventType);
        if (users != null) {
            for (NotificationObserver observer : users) {
                observer.update(eventType, data);
            }
        }
    }
}
