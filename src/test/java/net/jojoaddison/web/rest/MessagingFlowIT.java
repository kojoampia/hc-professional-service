package net.jojoaddison.web.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.jojoaddison.IntegrationTest;
import net.jojoaddison.domain.Message;
import net.jojoaddison.domain.MessageRecipient;
import net.jojoaddison.domain.ProfessionalApplication;
import net.jojoaddison.domain.enumeration.OnboardingStatus;
import net.jojoaddison.repository.ConversationRepository;
import net.jojoaddison.repository.MessageRecipientRepository;
import net.jojoaddison.repository.MessageRepository;
import net.jojoaddison.repository.ProfessionalApplicationRepository;
import net.jojoaddison.service.MessagingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The messaging slice: fan-out rules, the read boundary, and the notification contract.
 *
 * <p>The boundary tests are the point of this file. Membership is derived from
 * {@link MessageRecipient}, so a bug that reads a message by id without going through the caller's
 * own row would leak correspondence to anyone who can guess an id, and every functional test would
 * still pass.
 */
@AutoConfigureMockMvc
@IntegrationTest
class MessagingFlowIT {

    private static final String SENDER = "msg-sender";
    private static final String RECIPIENT = "msg-recipient";
    private static final String STRANGER = "msg-stranger";
    private static final String NURSE_A = "msg-nurse-a";
    private static final String NURSE_B = "msg-nurse-b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessageRecipientRepository messageRecipientRepository;

    @Autowired
    private ProfessionalApplicationRepository professionalApplicationRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        messageRecipientRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        professionalApplicationRepository.deleteAll();
    }

    private ProfessionalApplication activeNurse(String accountId) {
        ProfessionalApplication application = new ProfessionalApplication();
        application.setAccountId(accountId);
        application.setLogin(accountId);
        application.setRequestedRole("ROLE_NURSE");
        application.setStatus(OnboardingStatus.ACTIVE);
        return professionalApplicationRepository.save(application);
    }

    @Test
    void sendWritesARowForEveryRecipientAndForTheSender() {
        Message message = messagingService.startConversation(
            SENDER,
            SENDER,
            "Handover",
            "Bed 4 needs review",
            java.util.List.of(RECIPIENT),
            null
        );

        assertThat(messageRecipientRepository.findByMessageId(message.getId()))
            .extracting(MessageRecipient::getRecipientId)
            .containsExactlyInAnyOrder(SENDER, RECIPIENT);

        // The sender's own row is pre-read, so their unread count is not inflated by their own send.
        assertThat(messagingService.unreadCount(SENDER)).isZero();
        assertThat(messagingService.unreadCount(RECIPIENT)).isEqualTo(1);
    }

    @Test
    void aSentOnlyConversationStaysInTheSendersOwnList() {
        messagingService.startConversation(SENDER, SENDER, "Handover", "Bed 4", java.util.List.of(RECIPIENT), null);

        // This is the whole reason the sender gets a row: membership is derived from those rows.
        assertThat(messagingService.conversationsFor(SENDER)).hasSize(1);
    }

    @Test
    void aRoleBroadcastIsExpandedIntoOneRowPerHolderAtSendTime() {
        activeNurse(NURSE_A);
        activeNurse(NURSE_B);

        Message message = messagingService.startConversation(SENDER, SENDER, "All nurses", "Staff meeting at 3", null, "ROLE_NURSE");

        assertThat(messageRecipientRepository.findByMessageId(message.getId()))
            .extracting(MessageRecipient::getRecipientId)
            .containsExactlyInAnyOrder(SENDER, NURSE_A, NURSE_B);

        // Joining the role afterwards does not backfill: delivery already happened.
        activeNurse("msg-nurse-late");
        assertThat(messageRecipientRepository.findByMessageId(message.getId())).hasSize(3);
        assertThat(messagingService.unreadCount("msg-nurse-late")).isZero();
    }

    @Test
    void readStateIsPerRecipient() {
        activeNurse(NURSE_A);
        activeNurse(NURSE_B);
        Message message = messagingService.startConversation(SENDER, SENDER, "All nurses", "Staff meeting", null, "ROLE_NURSE");

        messagingService.markRead(NURSE_A, message.getId());

        assertThat(messagingService.unreadCount(NURSE_A)).isZero();
        assertThat(messagingService.unreadCount(NURSE_B)).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = STRANGER)
    void aStrangerCannotReadAMessageByIdEvenKnowingIt() throws Exception {
        Message message = messagingService.startConversation(SENDER, SENDER, "Private", "Not for you", java.util.List.of(RECIPIENT), null);

        mockMvc.perform(get("/api/messaging/messages/{id}", message.getId())).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = STRANGER)
    void aStrangerSeesNoMessagesInAConversationTheyAreNotIn() throws Exception {
        Message message = messagingService.startConversation(SENDER, SENDER, "Private", "Not for you", java.util.List.of(RECIPIENT), null);

        mockMvc
            .perform(get("/api/messaging/conversations/{id}/messages", message.getConversationId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(username = STRANGER)
    void aStrangerCannotReplyIntoSomeoneElsesConversation() throws Exception {
        Message message = messagingService.startConversation(SENDER, SENDER, "Private", "Not for you", java.util.List.of(RECIPIENT), null);

        // 404 rather than 403: a 403 would confirm the conversation exists.
        mockMvc
            .perform(
                post("/api/messaging/conversations/{id}/messages", message.getConversationId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"body\":\"let me in\"}")
            )
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = RECIPIENT)
    void theRecipientCanFetchTheMessageTheNotificationPointsAt() throws Exception {
        Message message = messagingService.startConversation(
            SENDER,
            SENDER,
            "Handover",
            "Bed 4 needs review",
            java.util.List.of(RECIPIENT),
            null
        );

        // This is the second half of the push flow: the event carries ids only, the client comes here.
        mockMvc
            .perform(get("/api/messaging/messages/{id}", message.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.body").value("Bed 4 needs review"))
            .andExpect(jsonPath("$.senderId").value(SENDER));
    }

    @Test
    @WithMockUser(username = RECIPIENT)
    void markAllReadClearsOnlyTheCallersRows() throws Exception {
        messagingService.startConversation(SENDER, SENDER, "One", "a", java.util.List.of(RECIPIENT, STRANGER), null);
        messagingService.startConversation(SENDER, SENDER, "Two", "b", java.util.List.of(RECIPIENT, STRANGER), null);

        mockMvc.perform(post("/api/messaging/read-all")).andExpect(status().isOk());

        assertThat(messagingService.unreadCount(RECIPIENT)).isZero();
        assertThat(messagingService.unreadCount(STRANGER)).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = SENDER)
    void sendingRequiresARecipientOrARole() throws Exception {
        mockMvc
            .perform(post("/api/messaging/conversations").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello\"}"))
            .andExpect(status().isBadRequest());
    }
}
