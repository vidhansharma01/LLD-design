package lld.notificationSystem.decorator;

public class BaseNotification implements Notification{
    private  String message;

    public BaseNotification() {
        this.message = "This is a base notification.";
    }

    @Override
    public String getContent() {
        return message;
    }
}
