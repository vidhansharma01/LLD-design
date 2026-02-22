package lld.notificationSystem.factory;

import lld.notificationSystem.ChannelType;
import lld.notificationSystem.strategy.EmailProvider;
import lld.notificationSystem.strategy.NotificationProvider;
import lld.notificationSystem.strategy.SmsProvider;

public class ProviderFactory {
    public static NotificationProvider getProvider(ChannelType channelType) {
        switch (channelType) {
            case SMS:
                return new SmsProvider();
            case EMAIL:
                return new EmailProvider();
            default:
                throw new IllegalArgumentException("Unsupported channel type: " + channelType);
        }
    }
}
