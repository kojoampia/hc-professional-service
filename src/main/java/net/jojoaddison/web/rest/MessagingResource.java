package net.jojoaddison.web.rest;

import java.util.List;
import net.jojoaddison.domain.Conversation;
import net.jojoaddison.domain.Message;
import net.jojoaddison.security.SecurityUtils;
import net.jojoaddison.service.MessagingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Clinician messaging.
 *
 * <p><b>Why this lives under {@code /api/messaging/**} rather than the generic entity paths.</b> The
 * mutation matrix in {@code SecurityConfiguration} restricts {@code POST /api/**} to
 * {@code CLINICAL_MUTATION} — admin, doctor, nurse, paramedic, pharmacist, therapist — which would
 * leave carer, angel, chemist and technician able to receive a message but never answer one.
 * Messaging is correspondence, not clinical data, so this surface is {@code .authenticated()}
 * instead, the same exception {@code /api/onboarding/**} already makes for applicants who hold only
 * ROLE_USER. If that is not wanted, move these paths back under the matrix and read-only roles
 * become read-only correspondents too.
 *
 * <p>Every endpoint resolves the caller from the security context and never takes an account id
 * from the request. A conversation or message id is not authority to read it: the service resolves
 * both through the caller's own recipient rows.
 */
@RestController
@RequestMapping("/api/messaging")
public class MessagingResource {

    private static final Logger log = LoggerFactory.getLogger(MessagingResource.class);

    private final MessagingService messagingService;

    public MessagingResource(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * The caller's account id. {@code accountId} carries the gateway login today — see
     * {@code OnboardingService} — which is also what STOMP uses as the principal name, so the
     * websocket notification and this read path agree on identity.
     */
    private String caller() {
        return SecurityUtils.getCurrentUserLogin()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user"));
    }

    public record NewConversation(String subject, String body, List<String> recipientIds, String recipientRole) {}

    public record NewReply(String body) {}

    @GetMapping("/conversations")
    public List<Conversation> myConversations() {
        return messagingService.conversationsFor(caller());
    }

    @PostMapping("/conversations")
    public ResponseEntity<Message> startConversation(@RequestBody NewConversation request) {
        String me = caller();
        if (request.body() == null || request.body().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A message body is required");
        }
        boolean noExplicit = request.recipientIds() == null || request.recipientIds().isEmpty();
        boolean noRole = request.recipientRole() == null || request.recipientRole().isBlank();
        if (noExplicit && noRole) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give at least one recipient, or a role to broadcast to");
        }
        Message message = messagingService.startConversation(
            me,
            me,
            request.subject(),
            request.body(),
            request.recipientIds(),
            request.recipientRole()
        );
        log.debug("{} started conversation {}", me, message.getConversationId());
        return ResponseEntity.ok(message);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<Message> reply(@PathVariable String conversationId, @RequestBody NewReply request) {
        String me = caller();
        if (request.body() == null || request.body().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A message body is required");
        }
        try {
            return ResponseEntity.ok(messagingService.reply(conversationId, me, me, request.body()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            // Not a member. 404 rather than 403 on purpose: 403 would confirm the thread exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such conversation");
        }
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<Message> messages(@PathVariable String conversationId) {
        return messagingService.messagesFor(caller(), conversationId);
    }

    /**
     * The second half of the notification flow. A {@code message.created} event carries identifiers
     * only, so the client comes here for the content — through the same authorization check as any
     * other read.
     */
    @GetMapping("/messages/{messageId}")
    public ResponseEntity<Message> message(@PathVariable String messageId) {
        return messagingService
            .messageFor(caller(), messageId)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such message"));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Long> unreadCount() {
        return ResponseEntity.ok(messagingService.unreadCount(caller()));
    }

    @PostMapping("/messages/{messageId}/read")
    public ResponseEntity<Void> markRead(@PathVariable String messageId) {
        return messagingService.markRead(caller(), messageId).isPresent()
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Integer> markAllRead() {
        return ResponseEntity.ok(messagingService.markAllRead(caller()));
    }
}
