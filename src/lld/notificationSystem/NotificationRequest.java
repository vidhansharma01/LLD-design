package lld.notificationSystem;

public class NotificationRequest {
    String userId;
    String message;
    ChannelType channelType;
    String status;

    public NotificationRequest(String userId, String message, ChannelType channelType) {
        this.userId = userId;
        this.message = message;
        this.channelType = channelType;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public ChannelType getChannelType() {
        return channelType;
    }

    public void setChannelType(ChannelType channelType) {
        this.channelType = channelType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
