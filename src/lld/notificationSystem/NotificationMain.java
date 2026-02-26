package lld.notificationSystem;

import lld.notificationSystem.decorator.BaseNotification;
import lld.notificationSystem.decorator.FooterDecorator;
import lld.notificationSystem.decorator.HeaderDecorator;
import lld.notificationSystem.decorator.Notification;
import lld.notificationSystem.observer.EmailListener;
import lld.notificationSystem.observer.OrderService;
import lld.notificationSystem.observer.SmsListener;

public class NotificationMain {
    public static void main(String[] args) {
        NotificationService notificationService = new NotificationService();
        AsyncNotificationEngine engine = new AsyncNotificationEngine(5, notificationService);

        Notification notification = new BaseNotification();
        notification = new HeaderDecorator(notification);
        notification = new FooterDecorator(notification);

        OrderService orderService = new OrderService();


        // Create some notification requests
        NotificationRequest request1 = new NotificationRequest("user1", notification.getContent(), ChannelType.SMS);
        NotificationRequest request2 = new NotificationRequest("user2", "Hello via Email!", ChannelType.EMAIL);


        // 1. Setup Listeners
        SmsListener smsListener = new SmsListener(notificationService, request1);
        EmailListener emailListener = new EmailListener(notificationService, request2);

        // 2. Subscribe them to specific events
        orderService.events.subscribe("ORDER_PLACED", smsListener);
        orderService.events.subscribe("ORDER_PLACED", emailListener);

        // 3. Trigger the business action
        orderService.createOrder("ORD-99");
        // Send notifications
        engine.enqueue(request1);
        engine.enqueue(request2);

        engine.shutdown();
    }
}
