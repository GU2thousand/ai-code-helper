package com.aicodehelper.ai;

import com.aicodehelper.config.AppProperties;
import com.aicodehelper.error.StreamTicketException;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatStreamTicketService {

    private final ConcurrentHashMap<UUID, StreamTicket> tickets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final AppProperties.Ai config;

    public ChatStreamTicketService(Clock clock, AppProperties properties) {
        this.clock = clock;
        this.config = properties.getAi();
    }

    public synchronized StreamTicket create(
            String conversationKey,
            String memoryId,
            String message,
            boolean regenerate
    ) {
        removeExpired();
        if (tickets.size() >= config.getMaxStreamTickets()) {
            throw StreamTicketException.capacityReached();
        }
        String owner = ownerKey(conversationKey);
        long pendingForOwner = tickets.values().stream()
                .filter(ticket -> ticket.owner().equals(owner))
                .count();
        if (pendingForOwner >= Math.max(1, config.getMaxPendingStreamTicketsPerOwner())) {
            throw StreamTicketException.capacityReached();
        }
        UUID id = UUID.randomUUID();
        StreamTicket ticket = new StreamTicket(
                id,
                owner,
                conversationKey,
                memoryId,
                message,
                regenerate,
                clock.instant().plus(config.getStreamTicketTtl())
        );
        tickets.put(id, ticket);
        return ticket;
    }

    public TicketDescriptor describe(UUID streamId) {
        StreamTicket ticket = tickets.get(streamId);
        if (ticket == null || expired(ticket)) {
            tickets.remove(streamId);
            throw StreamTicketException.notFound();
        }
        return new TicketDescriptor(ticket.memoryId(), ticket.expiresAt());
    }

    int pendingCount() {
        return tickets.size();
    }

    public StreamTicket claim(UUID streamId, String conversationKey) {
        AtomicReference<StreamTicket> claimed = new AtomicReference<>();
        AtomicBoolean wrongOwner = new AtomicBoolean();
        tickets.compute(streamId, (id, ticket) -> {
            if (ticket == null || expired(ticket)) {
                return null;
            }
            if (!ticket.conversationKey().equals(conversationKey)) {
                wrongOwner.set(true);
                return ticket;
            }
            claimed.set(ticket);
            return null;
        });
        if (wrongOwner.get()) {
            throw StreamTicketException.wrongOwner();
        }
        if (claimed.get() == null) {
            throw StreamTicketException.notFound();
        }
        return claimed.get();
    }

    private boolean expired(StreamTicket ticket) {
        return !ticket.expiresAt().isAfter(clock.instant());
    }

    private void removeExpired() {
        tickets.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    @Scheduled(fixedDelay = 30_000)
    public synchronized void removeExpiredOnSchedule() {
        removeExpired();
    }

    private String ownerKey(String conversationKey) {
        int delimiter = conversationKey.lastIndexOf(":memory:");
        return delimiter < 0 ? conversationKey : conversationKey.substring(0, delimiter);
    }

    public record StreamTicket(
            UUID id,
            String owner,
            String conversationKey,
            String memoryId,
            String message,
            boolean regenerate,
            Instant expiresAt
    ) {
    }

    public record TicketDescriptor(String memoryId, Instant expiresAt) {
    }
}
