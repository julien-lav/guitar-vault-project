package com.guitarvault;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailMessages {
    private final JavaMailSender sender;
    private final MessageSource messages;
    private final String baseUrl;
    private final String from;

    public MailMessages(JavaMailSender sender, MessageSource messages,
                        @Value("${app.base-url}") String baseUrl, @Value("${app.mail.from}") String from) {
        this.sender = sender;
        this.messages = messages;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.from = from;
    }

    public void send(User user, String purpose, String path, String token) {
        var locale = LocaleContextHolder.getLocaleContext() == null
                ? java.util.Locale.FRENCH : LocaleContextHolder.getLocale();
        String language = "en".equals(locale.getLanguage()) ? "en" : "fr";
        String link = baseUrl + path + "?token=" + token + "&lang=" + language;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject(messages.getMessage("mail." + purpose + ".subject", null, locale));
        message.setText(messages.getMessage("mail." + purpose + ".body", new Object[]{user.getFullName(), link}, locale));
        sender.send(message);
    }
}
