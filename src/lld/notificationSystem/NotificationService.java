package lld.notificationSystem;

import lld.notificationSystem.factory.ProviderFactory;
import lld.notificationSystem.strategy.NotificationProvider;

public class NotificationService {
    public void sendNotification(NotificationRequest request) {
        // Get the appropriate provider based on the channel type
        NotificationProvider provider = ProviderFactory.getProvider(request.getChannelType());

        // Send the notification using the provider
        provider.send(request);
    }

    public String getStatus(NotificationRequest request) {
        return request.getStatus();
    }
}
