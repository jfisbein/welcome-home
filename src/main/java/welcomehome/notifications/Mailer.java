package welcomehome.notifications;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends plain text emails, when the SMTP server and the sender are configured. */
@Component
class Mailer {

    private final @Nullable JavaMailSender sender;
    private final @Nullable String from;

    Mailer(ObjectProvider<JavaMailSender> sender, NotificationProperties properties) {
        // Spring Boot defines a JavaMailSender only when spring.mail.host is set.
        this.sender = sender.getIfAvailable();
        this.from = properties.from() == null || properties.from().isBlank() ? null : properties.from();
    }

    boolean isConfigured() {
        return sender != null && from != null;
    }

    /**
     * Sends an email right away.
     *
     * @throws IllegalStateException if sending is not configured
     * @throws MailException         if the SMTP server does not take it
     */
    void send(String to, String subject, String text) {
        if (sender == null || from == null) {
            throw new IllegalStateException("Email is not configured: set spring.mail.host and welcomehome.notifications.from");
        }
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        sender.send(message);
    }
}
