package lld.notificationSystem.strategy;

import lld.notificationSystem.NotificationRequest;

public class EmailProvider implements NotificationProvider {
    @Override
    public void send(NotificationRequest request) {
        System.out.println("Sending email notification to " + request.getUserId() + " with message: " + request.getMessage());
    }
}
