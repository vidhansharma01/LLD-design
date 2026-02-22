package lld.notificationSystem.decorator;

public class HeaderDecorator extends NotificationDecorator{
    public HeaderDecorator(Notification notification) {
        super(notification);
    }

    @Override
    public String getContent() {
        return "\n--- HEADER ---\n" + super.getContent();
    }
}
