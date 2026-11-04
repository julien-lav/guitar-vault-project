package com.guitarvault;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudioQuoteService {
    private final StudioQuoteRepository quotes;
    private final StudioService studios;

    public StudioQuoteService(StudioQuoteRepository quotes, StudioService studios) {
        this.quotes = quotes;
        this.studios = studios;
    }

    public Studio requestableStudio(User actor, Long studioId) {
        Studio studio = studios.find(studioId);
        if (!studio.getCreator().isEnabled() || studio.getCreator().getId().equals(actor.getId())) throw notFound();
        return studio;
    }

    public StudioQuote visible(User actor, Long id) {
        StudioQuote quote = quotes.findById(id).orElseThrow(StudioQuoteService::notFound);
        if (!quote.getRequester().getId().equals(actor.getId())
                && !quote.getStudio().getCreator().getId().equals(actor.getId())) throw notFound();
        return quote;
    }

    @Transactional
    public StudioQuote create(User actor, Long studioId, Map<String, String> form) {
        Studio studio = requestableStudio(actor, studioId);
        String title = form.get("title");
        String description = form.get("description");
        if (title.isBlank() || title.length() > 150 || description.isBlank() || description.length() > 5000) {
            throw new IllegalArgumentException("quote.projectError");
        }
        LocalDate start = date(form.get("startDate"));
        LocalDate end = date(form.get("endDate"));
        if ((start != null && start.isBefore(LocalDate.now(ZoneOffset.UTC)))
                || (end != null && (start == null || end.isBefore(start)))) {
            throw new IllegalArgumentException("quote.datesError");
        }
        BigDecimal budget = amount(form.get("budget"));
        return quotes.save(new StudioQuote(studio, actor, title, description, start, end, budget));
    }

    @Transactional
    public void reply(User actor, Long id, Map<String, String> form) {
        StudioQuote quote = quotes.findForUpdate(id).orElseThrow(StudioQuoteService::notFound);
        if (!quote.getStudio().getCreator().getId().equals(actor.getId())) throw notFound();
        if (quote.getStatus() != StudioQuote.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        String message = form.get("message");
        if (message.isBlank() || message.length() > 5000) throw new IllegalArgumentException("quote.replyError");
        BigDecimal proposedAmount = amount(form.get("amount"));
        quote.answer(message, proposedAmount);
        quotes.save(quote);
    }

    private static LocalDate date(String value) {
        if (value.isEmpty()) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("Invalid date", value, 0);
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new DateTimeParseException("Invalid year", value, 0);
            return date;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("quote.datesError");
        }
    }

    private static BigDecimal amount(String value) {
        if (value.isEmpty()) return null;
        if (!value.matches("[0-9]{1,6}([.,][0-9]{1,2})?")) throw new IllegalArgumentException("quote.amountError");
        return new BigDecimal(value.replace(',', '.'));
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
