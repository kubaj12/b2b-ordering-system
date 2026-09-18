package io.github.kubaj12.online_store;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.mail.javamail.JavaMailSender;
import io.github.kubaj12.online_store.notifications.application.*;
import io.github.kubaj12.online_store.notifications.persistence.SmtpMailDelivery;

@Configuration(proxyBeanMethods = false)
class MailConfiguration {
    @Bean @ConditionalOnMissingBean(MailDelivery.class)
    MailDelivery mailDelivery(JavaMailSender sender, ApplicationMailProperties properties) {
        if (!properties.deliveryEnabled()) return mail -> { throw MailDeliveryException.temporary(null); };
        return new SmtpMailDelivery(sender, properties.fromAddress());
    }
    @Bean(destroyMethod = "shutdownNow")
    java.util.concurrent.ThreadPoolExecutor accountLinkMailExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(64),
                runnable -> { var thread = new Thread(runnable, "account-link-mail"); thread.setDaemon(true); return thread; },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
    @Bean
    AccountLinkMail accountLinkMail(MailDelivery delivery, ApplicationWebProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("accountLinkMailExecutor") java.util.concurrent.Executor executor) {
        return new AccountLinkMail(delivery, properties.baseUrl(), executor);
    }
}
