package lld.notificationSystem.decorator;

public abstract class NotificationDecorator implements Notification{
    Notification wrappedNotification;

    public NotificationDecorator(Notification notification) {
        this.wrappedNotification = notification;
    }

    @Override
    public String getContent() {
        return wrappedNotification.getContent();
    }
}
