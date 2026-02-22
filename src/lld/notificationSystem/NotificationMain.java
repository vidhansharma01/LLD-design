package lld.notificationSystem;

import lld.notificationSystem.decorator.BaseNotification;
import lld.notificationSystem.decorator.FooterDecorator;
import lld.notificationSystem.decorator.HeaderDecorator;
import lld.notificationSystem.decorator.Notification;

public class NotificationMain {
    public static void main(String[] args) {
        NotificationService notificationService = new NotificationService();
        AsyncNotificationEngine engine = new AsyncNotificationEngine(5, notificationService);

        Notification notification = new BaseNotification();
        notification = new HeaderDecorator(notification);
        notification = new FooterDecorator(notification);

        // Create some notification requests
        NotificationRequest request1 = new NotificationRequest("user1", notification.getContent(), ChannelType.SMS);
        NotificationRequest request2 = new NotificationRequest("user2", "Hello via Email!", ChannelType.EMAIL);

        // Send notifications
        engine.enqueue(request1);
        engine.enqueue(request2);

        engine.shutdown();
    }
}
