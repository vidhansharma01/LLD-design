package lld.notificationSystem.observer;

import lld.notificationSystem.ChannelType;
import lld.notificationSystem.NotificationRequest;
import lld.notificationSystem.NotificationService;

public class EmailListener implements NotificationObserver{
    private NotificationService service;
    private NotificationRequest notificationRequest;

    public EmailListener(NotificationService service, NotificationRequest notificationRequest) {
        this.service = service;
        this.notificationRequest = notificationRequest;
    }

    @Override
    public void update(String eventType, Object data) {
        System.out.println("EmailListener: Event [" + eventType + "] received. Triggering Email...");
        NotificationRequest req = new NotificationRequest("U123", "Update: " + eventType, ChannelType.EMAIL);
        service.sendNotification(req);
    }
}
