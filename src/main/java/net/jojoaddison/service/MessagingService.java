package net.jojoaddison.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.jojoaddison.broker.DomainEventPublisher;
import net.jojoaddison.domain.Conversation;
import net.jojoaddison.domain.Message;
import net.jojoaddison.domain.MessageRecipient;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.repository.ConversationRepository;
import net.jojoaddison.repository.MessageRecipientRepository;
import net.jojoaddison.repository.MessageRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Clinician-to-clinician messaging.
 *
 * <p>Two rules here are load-bearing and easy to undo by accident:
 *
 * <ol>
 *   <li><b>The sender gets a recipient row too</b> ({@code read = true}). Membership is derived from
 *       {@link MessageRecipient}, so without it a conversation someone only ever sent into
 *       disappears from their own list.
 *   <li><b>A role broadcast is expanded at send time</b> into one row per holder. The role is also
 *       kept on the {@link Message} as the record of intent, but it is the rows that deliver. A
 *       clinician who joins that role later does not receive it retroactively, which is the
 *       intended reading of "sent to the nurses on duty that day".
 * </ol>
 *
 * <p>Every read is scoped by recipient. Callers must not load a {@link Message} by id directly —
 * that is what would let one clinician read another's inbox.
 */
@Service
public class MessagingService {

    private static final Logger log = LoggerFactory.getLogger(MessagingService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageRecipientRepository messageRecipientRepository;
    private final ProfessionalApplicationRepository professionalApplicationRepository;
    private final DomainEventPublisher domainEventPublisher;

    public MessagingService(
        ConversationRepository conversationRepository,
        MessageRepository messageRepository,
        MessageRecipientRepository messageRecipientRepository,
        ProfessionalApplicationRepository professionalApplicationRepository,
        DomainEventPublisher domainEventPublisher
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageRecipientRepository = messageRecipientRepository;
        this.professionalApplicationRepository = professionalApplicationRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    /**
     * Starts a thread and posts its first message.
     */
    public Message startConversation(
        String senderId,
        String senderName,
        String subject,
        String body,
        List<String> recipientIds,
        String recipientRole
    ) {
        Instant now = Instant.now();
        Conversation conversation = conversationRepository.save(
            new Conversation().subject(subject).createdBy(senderId).createdAt(now).lastMessageAt(now)
        );
        return post(conversation, senderId, senderName, body, recipientIds, recipientRole, now);
    }

    /**
     * Posts into an existing thread. The caller must already be a member — enforced here rather than
     * in the resource, so there is one place it can be got wrong.
     */
    public Message reply(String conversationId, String senderId, String senderName, String body) {
        Conversation conversation = conversationRepository
            .findById(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("No such conversation: " + conversationId));
        if (messageRecipientRepository.findByRecipientIdAndConversationId(senderId, conversationId).isEmpty()) {
            throw new IllegalStateException("Not a participant in conversation " + conversationId);
        }
        Instant now = Instant.now();
        // Everyone already in the thread, minus the sender — they are re-added below as their own row.
        Set<String> existing = new LinkedHashSet<>();
        for (MessageRecipient row : messageRecipientRepository.findByRecipientIdAndConversationId(senderId, conversationId)) {
            for (MessageRecipient sibling : messageRecipientRepository.findByMessageId(row.getMessageId())) {
                existing.add(sibling.getRecipientId());
            }
        }
        existing.remove(senderId);
        return post(conversation, senderId, senderName, body, new ArrayList<>(existing), null, now);
    }

    private Message post(
        Conversation conversation,
        String senderId,
        String senderName,
        String body,
        List<String> recipientIds,
        String recipientRole,
        Instant now
    ) {
        Message message = messageRepository.save(
            new Message()
                .conversationId(conversation.getId())
                .senderId(senderId)
                .senderName(senderName)
                .body(body)
                .sentAt(now)
                .recipientRole(recipientRole)
        );

        Set<String> targets = new LinkedHashSet<>();
        if (recipientIds != null) {
            recipientIds.stream().filter(Objects::nonNull).filter(id -> !id.isBlank()).forEach(targets::add);
        }
        if (recipientRole != null && !recipientRole.isBlank()) {
            targets.addAll(resolveRole(recipientRole));
        }
        // The sender's own row. Added last and marked read so it cannot be double-counted as unread
        // if they also appear in the addressee list.
        targets.remove(senderId);

        List<MessageRecipient> rows = new ArrayList<>();
        for (String target : targets) {
            rows.add(
                new MessageRecipient()
                    .messageId(message.getId())
                    .recipientId(target)
                    .conversationId(conversation.getId())
                    .read(Boolean.FALSE)
            );
        }
        rows.add(
            new MessageRecipient()
                .messageId(message.getId())
                .recipientId(senderId)
                .conversationId(conversation.getId())
                .read(Boolean.TRUE)
                .readAt(now)
        );
        messageRecipientRepository.saveAll(rows);

        conversation.setLastMessageAt(now);
        conversationRepository.save(conversation);

        // Identifiers only — the notification tells a client that something arrived, and the client
        // then fetches it through the authorized read path. Publishing must never break the write.
        for (String target : targets) {
            domainEventPublisher.publishMessageCreated(message.getId(), conversation.getId(), target, senderId);
        }
        log.debug("Message {} posted to conversation {} for {} recipient(s)", message.getId(), conversation.getId(), targets.size());
        return message;
    }

    /**
     * Who currently holds a clinical authority, per this service's own records. See
     * {@code ProfessionalApplicationRepository.findByRequestedRoleAndStatus} for why that is an
     * approximation of the gateway's grant.
     */
    private Set<String> resolveRole(String role) {
        Set<String> accounts = new LinkedHashSet<>();
        professionalApplicationRepository
            .findByRequestedRoleAndStatus(role, OnboardingStatus.ACTIVE)
            .forEach(application -> {
                if (application.getAccountId() != null) {
                    accounts.add(application.getAccountId());
                }
            });
        if (accounts.isEmpty()) {
            log.info("Role broadcast to {} matched no active professionals — message stored with no recipients", role);
        }
        return accounts;
    }

    // --- Reads, all scoped to the caller ---------------------------------------------------------

    public List<Conversation> conversationsFor(String accountId) {
        Set<String> ids = new LinkedHashSet<>();
        messageRecipientRepository.findByRecipientIdOrderByIdDesc(accountId).forEach(row -> ids.add(row.getConversationId()));
        if (ids.isEmpty()) {
            return List.of();
        }
        return conversationRepository.findByIdIn(ids, Sort.by(Sort.Direction.DESC, "lastMessageAt"));
    }

    /**
     * The messages of one thread, but only those the caller actually has a recipient row for. A
     * conversation id alone is not authority to read it.
     */
    public List<Message> messagesFor(String accountId, String conversationId) {
        List<String> messageIds = messageRecipientRepository
            .findByRecipientIdAndConversationId(accountId, conversationId)
            .stream()
            .map(MessageRecipient::getMessageId)
            .toList();
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return messageRepository.findByIdInOrderBySentAtDesc(messageIds);
    }

    /**
     * A single message, resolved through the caller's recipient row. Returns empty rather than
     * throwing when the caller has no row, so a probe cannot distinguish "not yours" from "absent".
     */
    public Optional<Message> messageFor(String accountId, String messageId) {
        return messageRecipientRepository
            .findByMessageIdAndRecipientId(messageId, accountId)
            .flatMap(row -> messageRepository.findById(row.getMessageId()));
    }

    public long unreadCount(String accountId) {
        return messageRecipientRepository.countByRecipientIdAndReadIsFalse(accountId);
    }

    public Optional<MessageRecipient> markRead(String accountId, String messageId) {
        return messageRecipientRepository
            .findByMessageIdAndRecipientId(messageId, accountId)
            .map(row -> messageRecipientRepository.save(row.read(Boolean.TRUE).readAt(Instant.now())));
    }

    public int markAllRead(String accountId) {
        List<MessageRecipient> unread = messageRecipientRepository.findByRecipientIdAndReadIsFalse(accountId);
        Instant now = Instant.now();
        unread.forEach(row -> row.read(Boolean.TRUE).readAt(now));
        messageRecipientRepository.saveAll(unread);
        return unread.size();
    }
}
