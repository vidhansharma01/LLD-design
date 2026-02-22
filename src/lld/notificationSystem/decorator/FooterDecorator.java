package lld.notificationSystem.decorator;

public class FooterDecorator extends NotificationDecorator {
    public FooterDecorator(Notification n) { super(n); }

    @Override
    public String getContent() {
        return super.getContent() + "\n -- FOOTER --";
    }
}
