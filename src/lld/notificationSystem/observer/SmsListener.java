package lld.notificationSystem.observer;


import lld.notificationSystem.NotificationRequest;
import lld.notificationSystem.NotificationService;

public class SmsListener implements NotificationObserver {
    private NotificationService service;
    private NotificationRequest notificationRequest;

    public SmsListener(NotificationService service, NotificationRequest notificationRequest) {
        this.service = service;
        this.notificationRequest = notificationRequest;
    }

    @Override
    public void update(String eventType, Object data) {
        System.out.println("SMSListener: Sending SMS alert for " + eventType);
    }
}
