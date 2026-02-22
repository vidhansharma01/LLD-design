package lld.notificationSystem.strategy;

import lld.notificationSystem.NotificationRequest;

// Strategy Interface
public interface NotificationProvider {
    void send(NotificationRequest request);
}
