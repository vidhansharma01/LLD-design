package lld.notificationSystem.observer;

public class OrderService {
    public NotificationEventManager events;

    public OrderService() {
        this.events = new NotificationEventManager();
    }

    public void createOrder(String orderId) {
        System.out.println("OrderService: Order " + orderId + " created successfully.");

        // Broadcast the event to anyone listening
        events.notify("ORDER_PLACED", orderId);
    }
}