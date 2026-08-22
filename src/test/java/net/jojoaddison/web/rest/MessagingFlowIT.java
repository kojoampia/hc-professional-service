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

    // --- Per-conversation read, recipients, and the refused broadcast (Phase 1.5/1.6) ----------

    @Test
    void readingONEthreadLeavesTHEOTHERSunread() {
        // The whole reason this endpoint exists. A client could previously mark one message or mark
        // EVERYTHING, so the mobile app opened a thread and cleared every unread badge — a clinician
        // lost the signal that other conversations were waiting.
        Message first = messagingService.startConversation(SENDER, SENDER, "One", "body", java.util.List.of(RECIPIENT), null);
        messagingService.startConversation(SENDER, SENDER, "Two", "body", java.util.List.of(RECIPIENT), null);
        assertThat(messagingService.unreadCount(RECIPIENT)).isEqualTo(2);

        long remaining = messagingService.markConversationRead(RECIPIENT, first.getConversationId());

        assertThat(remaining).isEqualTo(1);
        assertThat(messagingService.unreadCount(RECIPIENT)).isEqualTo(1);
    }

    @Test
    void markingAThreadReadANSWERSwithTheNewTotal() {
        // Returned rather than 204 so the badge costs one round trip, and cannot briefly disagree
        // with the list that prompted the call.
        Message message = messagingService.startConversation(SENDER, SENDER, "One", "body", java.util.List.of(RECIPIENT), null);

        assertThat(messagingService.markConversationRead(RECIPIENT, message.getConversationId())).isZero();
    }

    @Test
    void markingAThreadTheCallerIsNotInChangesNothing() {
        // A conversation id is not authority over it — the same rule every read here follows.
        Message message = messagingService.startConversation(SENDER, SENDER, "One", "body", java.util.List.of(RECIPIENT), null);

        assertThat(messagingService.markConversationRead(STRANGER, message.getConversationId())).isZero();
        assertThat(messagingService.unreadCount(RECIPIENT)).isEqualTo(1);
    }

    @Test
    void markingAnAlreadyReadThreadIsAnObviousNoOp() {
        Message message = messagingService.startConversation(SENDER, SENDER, "One", "body", java.util.List.of(RECIPIENT), null);
        messagingService.markConversationRead(RECIPIENT, message.getConversationId());

        assertThat(messagingService.markConversationRead(RECIPIENT, message.getConversationId())).isZero();
    }

    @Test
    void theRecipientDirectoryListsActiveProfessionals() {
        activeNurse(NURSE_A);
        activeNurse(NURSE_B);

        assertThat(messagingService.recipients(null, null))
            .extracting(MessagingService.Recipient::accountId)
            .containsExactlyInAnyOrder(NURSE_A, NURSE_B);
    }

    @Test
    void theDirectoryNarrowsByRoleAndByName() {
        activeNurse(NURSE_A);

        assertThat(messagingService.recipients(null, "ROLE_NURSE")).hasSize(1);
        assertThat(messagingService.recipients(null, "ROLE_DOCTOR")).isEmpty();
        assertThat(messagingService.recipients("nurse-a", null)).hasSize(1);
        assertThat(messagingService.recipients("nobody", null)).isEmpty();
    }

    @Test
    void theDirectoryAndTheBROADCASTagreeAboutWhoExists() {
        // Sourced from the same records on purpose: a picker that offers someone the broadcast
        // cannot reach, or vice versa, is worse than no picker.
        activeNurse(NURSE_A);

        assertThat(messagingService.recipients(null, "ROLE_NURSE")).isNotEmpty();
        assertThat(messagingService.roleHasRecipients("ROLE_NURSE")).isTrue();
        assertThat(messagingService.recipients(null, "ROLE_WIZARD")).isEmpty();
        assertThat(messagingService.roleHasRecipients("ROLE_WIZARD")).isFalse();
    }

    @Test
    @WithMockUser(username = SENDER, authorities = { "ROLE_NURSE" })
    void aBroadcastToARoleNOBODYholdsIsREFUSED() throws Exception {
        // It used to log at info and store a message with zero recipients, answering 200 — the
        // clinician saw their escalation sent and it reached no one. 422 rather than 400: the
        // request is well-formed, it simply cannot be carried out.
        mockMvc
            .perform(
                post("/api/messaging/conversations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"subject\":\"Escalation\",\"body\":\"Please review\",\"recipientRole\":\"ROLE_WIZARD\"}")
            )
            .andExpect(status().isUnprocessableEntity());

        assertThat(messageRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser(username = SENDER, authorities = { "ROLE_NURSE" })
    void aBroadcastToARoleSOMEONEholdsIsSent() throws Exception {
        activeNurse(NURSE_A);

        mockMvc
            .perform(
                post("/api/messaging/conversations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"subject\":\"Staff\",\"body\":\"Meeting at 3\",\"recipientRole\":\"ROLE_NURSE\"}")
            )
            .andExpect(status().isOk());

        assertThat(messagingService.unreadCount(NURSE_A)).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "msg-carer", authorities = { "ROLE_CARER" })
    void aREADONLYroleCanStillReadAThreadAndMarkItRead() throws Exception {
        // /api/messaging/** is hoisted above the CLINICAL_MUTATION rules for exactly this:
        // correspondence is not a clinical mutation, and a carer who could receive a message but
        // never answer or clear it would be worse than one who got none.
        messagingService.startConversation(SENDER, SENDER, "One", "body", java.util.List.of("msg-carer"), null);
        String conversationId = messagingService.conversationsFor("msg-carer").get(0).getId();

        mockMvc.perform(post("/api/messaging/conversations/" + conversationId + "/read")).andExpect(status().isOk());

        assertThat(messagingService.unreadCount("msg-carer")).isZero();
    }
}
