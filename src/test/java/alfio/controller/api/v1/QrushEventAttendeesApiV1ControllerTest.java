/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.v1;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.config.authentication.support.APITokenAuthentication;
import alfio.controller.api.ControllerConfiguration;
import alfio.controller.api.v1.admin.EventApiV1Controller;
import alfio.controller.api.v1.admin.QrushEventAttendeesApiV1Controller;
import alfio.controller.api.v1.admin.QrushReservationApiV1Controller;
import alfio.controller.api.v1.admin.ReservationApiV1Controller;
import alfio.controller.api.v2.user.TicketApiV2Controller;
import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.manager.EventManager;
import alfio.manager.PurchaseContextFieldManager;
import alfio.manager.support.AccessDeniedException;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.Ticket;
import alfio.model.TicketCategory;
import alfio.model.api.v1.admin.AdditionalInfoRequest;
import alfio.model.api.v1.admin.AttendeesByCategory;
import alfio.model.api.v1.admin.DescriptionRequest;
import alfio.model.api.v1.admin.DownloadedAttendeeData;
import alfio.model.api.v1.admin.DownloadedAttendeesByCategory;
import alfio.model.api.v1.admin.ReservationConfirmationRequest;
import alfio.model.api.v1.admin.ReservationUser;
import alfio.model.api.v1.admin.TicketReservationCreationRequest;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.AdminReservationModification.Notification;
import alfio.model.modification.AdminReservationModification.TransactionDetails;
import alfio.model.modification.AttendeeData;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.transaction.PaymentProxy;
import alfio.model.user.Role;
import alfio.model.user.User;
import alfio.repository.EventRepository;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.AuthorityRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import alfio.util.ImageUtil;
import alfio.util.Json;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.DESCRIPTION;
import static org.junit.jupiter.api.Assertions.*;

@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class QrushEventAttendeesApiV1ControllerTest {

    private static final String DEFAULT_CATEGORY_NAME = "default";
    /** qrush writes the ticket number into this alf.io additional field. */
    private static final String TICKET_NUMBER_FIELD = "Ticketnummer";
    private static final List<String> ATTENDEE_JSON_KEYS = List.of("firstName", "lastName", "email",
        "metadata", "additional", "externalReference", "status", "confirmationTimestamp");

    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private ClockProvider clockProvider;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserManager userManager;
    @Autowired private EventManager eventManager;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketCategoryRepository ticketCategoryRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private PurchaseContextFieldManager purchaseContextFieldManager;
    @Autowired private ReservationApiV1Controller upstreamReservationController;
    @Autowired private EventApiV1Controller upstreamEventController;
    @Autowired private QrushEventAttendeesApiV1Controller controller;
    @Autowired private QrushReservationApiV1Controller qrushReservationController;
    @Autowired private TicketApiV2Controller upstreamTicketController;
    @Autowired private AuthorityRepository authorityRepository;

    private Event event;
    private APITokenAuthentication orgAKey;

    @BeforeEach
    void setUp() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        Pair<Event, String> eventAndUser = IntegrationTestUtil.initEvent(defaultCategories(),
            organizationRepository, userManager, eventManager, eventRepository);
        event = eventAndUser.getLeft();
        var apiKeyUsername = UUID.randomUUID().toString();
        userManager.insertUser(event.getOrganizationId(), apiKeyUsername, "test", "test",
            "test@example.com", Role.API_CONSUMER, User.Type.INTERNAL, null);
        orgAKey = apiKey(apiKeyUsername);
        addTicketNumberField(event);
    }

    /**
     * Builds the API-key principal the way the production filter does
     * (APITokenAuthWebSecurity: authorities from authorityRepository.findRoles). An empty
     * authority list makes AccessService#isSystemApiUser vacuously true (allMatch over an empty
     * stream) — which would silently bypass org isolation and void the cross-org test below.
     */
    private APITokenAuthentication apiKey(String username) {
        var authorities = authorityRepository.findRoles(username).stream()
            .map(SimpleGrantedAuthority::new).toList();
        return new APITokenAuthentication(username, null, authorities);
    }

    private List<TicketCategoryModification> defaultCategories() {
        return Arrays.asList(
            new TicketCategoryModification(null, DEFAULT_CATEGORY_NAME, TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty()));
    }

    /** The ATTENDEE-context definition must exist before reservation creation, or values are dropped. */
    private void addTicketNumberField(Event target) {
        var request = new AdditionalInfoRequest(0, TICKET_NUMBER_FIELD,
            AdditionalInfoRequest.AdditionalInfoType.GENERIC_TEXT, false,
            List.of(new DescriptionRequest("en", "Ticket number")), List.of(), null, null, null);
        purchaseContextFieldManager.addAdditionalField(target, request.toAdditionalField(0));
    }

    /**
     * Reservation via the upstream org-key controller — the exact path the qrush CFs use, with one
     * ticket number per attendee in the additional-fields map.
     */
    private String createReservation(Event target, APITokenAuthentication principal, List<String> ticketNumbers) {
        var category = ticketCategoryRepository.findFirstWithAvailableTickets(target.getId()).orElseThrow();
        var attendees = IntStream.range(0, ticketNumbers.size())
            .mapToObj(i -> new AttendeeData("firstName" + i, "lastName" + i, "attendee" + i + "@example.org",
                null, Map.of("source", "qrush-test"), Map.of(TICKET_NUMBER_FIELD, List.of(ticketNumbers.get(i)))))
            .toList();
        var buyer = new ReservationUser(null, "Buyer", "McBuyer", "buyer@example.org", null);
        var creationRequest = new TicketReservationCreationRequest(
            List.of(new AttendeesByCategory(category.getId(), ticketNumbers.size(), attendees, null)),
            List.of(), null, buyer, null, "en", null, null);
        var response = upstreamReservationController.createTicketsReservation(target.getShortName(), creationRequest, principal);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return Objects.requireNonNull(Objects.requireNonNull(response.getBody()).id());
    }

    /** Full-amount ON_SITE transaction -> reservation COMPLETE, tickets TO_BE_PAID (upstream idiom). */
    private String createConfirmedReservation(List<String> ticketNumbers) {
        return createConfirmedReservation(event, orgAKey, ticketNumbers);
    }

    /** The same confirmation for any event and key, so a foreign org can own a COMPLETE reservation too. */
    private String createConfirmedReservation(Event target, APITokenAuthentication key, List<String> ticketNumbers) {
        var reservationId = createReservation(target, key, ticketNumbers);
        var confirmation = new ReservationConfirmationRequest(
            new TransactionDetails("TRID", new BigDecimal("100.00"),
                LocalDateTime.now(clockProvider.getClock()), "notes", PaymentProxy.ON_SITE),
            new Notification(true, true), null);
        var response = upstreamReservationController.confirmReservation(reservationId, confirmation, key);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return reservationId;
    }

    private void setTicketStatus(String reservationId, Ticket.TicketStatus status) {
        ticketRepository.updateTicketsStatusWithReservationId(reservationId, status.name());
    }

    private static List<String> ticketNumbersIn(List<DownloadedAttendeesByCategory> body) {
        return body.stream()
            .flatMap(c -> c.attendees().stream())
            .flatMap(a -> a.additional().getOrDefault(TICKET_NUMBER_FIELD, List.of()).stream())
            .sorted()
            .toList();
    }

    private static List<DownloadedAttendeeData> attendeesIn(List<DownloadedAttendeesByCategory> body) {
        return body.stream().flatMap(c -> c.attendees().stream()).toList();
    }

    @Test
    void acquiredAttendeesAreByteCompatibleWithUpstreamDownload() throws Exception {
        var reservationId = createConfirmedReservation(List.of("QR-1001", "QR-1002"));
        // ACQUIRED is the one status where the upstream endpoint still finds the values, so the two
        // payloads must be identical — that is the CF parser contract this endpoint has to keep.
        setTicketStatus(reservationId, Ticket.TicketStatus.ACQUIRED);

        var response = controller.qrushAttendees(event.getShortName(), orgAKey);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        var body = Objects.requireNonNull(response.getBody());
        assertEquals(1, body.size());
        var categoryId = ticketCategoryRepository.findAllTicketCategories(event.getId()).get(0).getId();
        assertEquals(categoryId, body.get(0).ticketCategoryId());
        var attendees = attendeesIn(body);
        assertEquals(2, attendees.size());
        assertEquals(List.of("QR-1001", "QR-1002"), ticketNumbersIn(body));
        assertTrue(attendees.stream().allMatch(a -> a.email().endsWith("@example.org")));
        assertTrue(attendees.stream().allMatch(a -> a.status() == Ticket.TicketStatus.ACQUIRED));
        assertTrue(attendees.stream().allMatch(a -> a.confirmationTimestamp() != null));

        var upstream = Objects.requireNonNull(
            upstreamEventController.downloadAttendees(event.getShortName(), orgAKey).getBody());
        assertEquals(Json.toJson(upstream), Json.toJson(body));

        var attendeeJson = Json.OBJECT_MAPPER.readTree(Json.toJson(body)).get(0).get("attendees").get(0);
        ATTENDEE_JSON_KEYS.forEach(key -> assertTrue(attendeeJson.has(key), "missing key " + key));
    }

    @Test
    void checkedInTicketKeepsItsAdditionalFieldValue() {
        var reservationId = createConfirmedReservation(List.of("QR-2001"));
        // our scanner flips the ticket to CHECKED_IN
        setTicketStatus(reservationId, Ticket.TicketStatus.CHECKED_IN);

        var body = Objects.requireNonNull(
            controller.qrushAttendees(event.getShortName(), orgAKey).getBody());

        var attendees = attendeesIn(body);
        assertEquals(1, attendees.size());
        assertEquals(Ticket.TicketStatus.CHECKED_IN, attendees.get(0).status());
        assertEquals(List.of("QR-2001"), attendees.get(0).additional().get(TICKET_NUMBER_FIELD));

        // the asymmetry this endpoint exists for: upstream download-attendees looks values up with
        // status = 'ACQUIRED' only, so the same checked-in ticket comes back without its number
        var upstream = Objects.requireNonNull(
            upstreamEventController.downloadAttendees(event.getShortName(), orgAKey).getBody());
        var upstreamAttendees = attendeesIn(upstream);
        assertEquals(1, upstreamAttendees.size());
        assertTrue(upstreamAttendees.get(0).additional().isEmpty());
    }

    @Test
    void eventWithoutConfirmedTicketsReturnsEmptyList() {
        var response = controller.qrushAttendees(event.getShortName(), orgAKey);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals(List.of(), Objects.requireNonNull(response.getBody()));
    }

    /** Second, fully independent org with its own event + API key (initEvent randomizes all names). */
    private record ForeignOrg(Event event, APITokenAuthentication key) {}

    private ForeignOrg createForeignOrg() {
        Pair<Event, String> eventAndUser = IntegrationTestUtil.initEvent(defaultCategories(),
            organizationRepository, userManager, eventManager, eventRepository);
        var foreignEvent = eventAndUser.getLeft();
        var username = UUID.randomUUID().toString();
        userManager.insertUser(foreignEvent.getOrganizationId(), username, "test", "test",
            "test@example.com", Role.API_CONSUMER, User.Type.INTERNAL, null);
        return new ForeignOrg(foreignEvent, apiKey(username));
    }

    @Test
    void crossOrgKeyCannotReadForeignAttendees() {
        createConfirmedReservation(List.of("QR-3001"));
        var orgB = createForeignOrg();

        // org B key + org A slug -> denied, no attendee data
        assertThrows(AccessDeniedException.class,
            () -> controller.qrushAttendees(event.getShortName(), orgB.key()));
        // nonexistent slug -> AccessDenied (403 over HTTP), never a 404 that confirms existence
        assertThrows(AccessDeniedException.class,
            () -> controller.qrushAttendees("does-not-exist", orgAKey));
        // org B key on org B's own event still works -> the denial above is isolation, not a blanket failure
        assertTrue(controller.qrushAttendees(orgB.event().getShortName(), orgB.key())
            .getStatusCode().is2xxSuccessful());
    }

    /** Tickets without holder data, the way qrush creates them: a buyer plus a bare quantity. */
    private String createConfirmedUnassignedReservation(APITokenAuthentication principal, int ticketCount) {
        var category = ticketCategoryRepository.findFirstWithAvailableTickets(event.getId()).orElseThrow();
        var buyer = new ReservationUser(null, "Buyer", "McBuyer", "buyer@example.org", null);
        var creationRequest = new TicketReservationCreationRequest(
            List.of(new AttendeesByCategory(category.getId(), ticketCount, List.of(), null)),
            List.of(), null, buyer, null, "en", null, null);
        var created = upstreamReservationController.createTicketsReservation(event.getShortName(), creationRequest, principal);
        assertTrue(created.getStatusCode().is2xxSuccessful());
        var reservationId = Objects.requireNonNull(Objects.requireNonNull(created.getBody()).id());
        var confirmation = new ReservationConfirmationRequest(
            new TransactionDetails("TRID", new BigDecimal("100.00"),
                LocalDateTime.now(clockProvider.getClock()), "notes", PaymentProxy.ON_SITE),
            new Notification(true, true), null);
        assertTrue(upstreamReservationController.confirmReservation(reservationId, confirmation, principal)
            .getStatusCode().is2xxSuccessful());
        return reservationId;
    }

    /** Tickets A and B of org A's event, both assigned to attendees; A is the lower id. */
    private List<Ticket> createAssignedTicketPair() {
        var reservationId = createConfirmedReservation(List.of("QR-4001", "QR-4002"));
        var tickets = ticketRepository.findTicketsInReservation(reservationId).stream()
            .sorted(Comparator.comparingInt(Ticket::getId))
            .toList();
        assertEquals(2, tickets.size());
        return tickets;
    }

    /** The offline check-in key, computed from the suite's own event re-read from the database. */
    private String expectedSignatureHash(Ticket row) {
        var reread = eventRepository.findById(event.getId());
        return DigestUtils.sha256Hex(row.hmacTicketInfo(reread.getPrivateKey(), reread.supportsQRCodeCaseInsensitive()));
    }

    @Test
    void p4_4_signaturesOrderedById() {
        var pair = createAssignedTicketPair();
        var a = pair.get(0);
        var b = pair.get(1);

        var response = controller.qrushTicketSignatures(event.getShortName(), List.of(b.getId(), a.getId()), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(
                new QrushEventAttendeesApiV1Controller.QrushTicketSignature(a.getId(), expectedSignatureHash(a)),
                new QrushEventAttendeesApiV1Controller.QrushTicketSignature(b.getId(), expectedSignatureHash(b))),
            response.getBody());
    }

    @Test
    void p4_5_signaturesSkipUnassignedAndForeignTickets() {
        var pair = createAssignedTicketPair();
        var a = pair.get(0);
        var b = pair.get(1);
        var unassignedReservationId = createConfirmedUnassignedReservation(orgAKey, 1);
        var unassignedId = ticketRepository.findTicketsInReservation(unassignedReservationId).get(0).getId();
        var orgB = createForeignOrg();
        addTicketNumberField(orgB.event());
        var foreignReservationId = createReservation(orgB.event(), orgB.key(), List.of("QR-x"));
        var foreignId = ticketRepository.findTicketsInReservation(foreignReservationId).get(0).getId();

        var response = controller.qrushTicketSignatures(event.getShortName(),
            List.of(a.getId(), b.getId(), unassignedId, foreignId), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(
                new QrushEventAttendeesApiV1Controller.QrushTicketSignature(a.getId(), expectedSignatureHash(a)),
                new QrushEventAttendeesApiV1Controller.QrushTicketSignature(b.getId(), expectedSignatureHash(b))),
            response.getBody());
    }

    @Test
    void p4_6_moreThan200IdsIsRejected() {
        var a = createAssignedTicketPair().get(0);

        var response = controller.qrushTicketSignatures(event.getShortName(), Collections.nCopies(201, a.getId()), orgAKey);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void p4_7_emptyIdListAnswersEmptyList() {
        var response = controller.qrushTicketSignatures(event.getShortName(), List.of(), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(), response.getBody());
    }

    @Test
    @DisplayName("P4.8 crossOrgKeyCannotReadForeignSignatures")
    void crossOrgKeyCannotReadForeignSignatures() {
        var pair = createAssignedTicketPair();
        var ids = List.of(pair.get(0).getId(), pair.get(1).getId());
        var orgB = createForeignOrg();

        // org B key + org A slug -> denied, no hashes
        assertThrows(AccessDeniedException.class,
            () -> controller.qrushTicketSignatures(event.getShortName(), ids, orgB.key()));
        // nonexistent slug -> AccessDenied (403 over HTTP), never a 404 that confirms existence
        assertThrows(AccessDeniedException.class,
            () -> controller.qrushTicketSignatures("does-not-exist", ids, orgB.key()));
        // org B key on its own event with org A's ids -> allowed, but the event scope yields nothing
        var own = controller.qrushTicketSignatures(orgB.event().getShortName(), ids, orgB.key());
        assertEquals(200, own.getStatusCode().value());
        assertEquals(List.of(), own.getBody());
    }

    @Test
    void p4_9_foreignKeyOverCapIsDeniedNotRejected() {
        var a = createAssignedTicketPair().get(0);
        var orgB = createForeignOrg();

        assertThrows(AccessDeniedException.class,
            () -> controller.qrushTicketSignatures(event.getShortName(), Collections.nCopies(201, a.getId()), orgB.key()));
    }

    @Test
    void p4_10_foreignKeyWithEmptyListIsDenied() {
        var orgB = createForeignOrg();

        assertThrows(AccessDeniedException.class,
            () -> controller.qrushTicketSignatures(event.getShortName(), List.of(), orgB.key()));
    }

    // ---- W15: P5 org-key assign, P6 reissue, P7 QR image (T22.121 - T22.167) ----

    /** Belongs to no ticket. */
    private static final UUID U = UUID.fromString("c0ffee00-0000-4000-8000-000000000001");

    /** The holder the CF sends; the additional map stays mutable because the validator writes into it. */
    private static UpdateTicketOwnerForm ritaBody() {
        var form = new UpdateTicketOwnerForm();
        form.setFirstName("Rita");
        form.setLastName("Recipient");
        form.setFullName("Rita Recipient");
        form.setEmail("rita@example.org");
        form.setUserLanguage("en");
        form.setAdditional(new HashMap<>());
        return form;
    }

    private static UpdateTicketOwnerForm ritaBodyWithTicketNumber(String ticketNumber) {
        var form = ritaBody();
        form.getAdditional().put(TICKET_NUMBER_FIELD, new ArrayList<>(List.of(ticketNumber)));
        return form;
    }

    private Ticket singleTicketOf(String reservationId) {
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        assertEquals(1, tickets.size());
        return tickets.get(0);
    }

    /** T1: the single ticket of a confirmed reservation without holder data. */
    private Ticket createUnassignedTicket() {
        return singleTicketOf(createConfirmedUnassignedReservation(orgAKey, 1));
    }

    /** TB: the single, assigned ticket of a COMPLETE reservation on another org's event. */
    private Ticket createForeignConfirmedTicket(ForeignOrg orgB) {
        addTicketNumberField(orgB.event());
        return singleTicketOf(createConfirmedReservation(orgB.event(), orgB.key(), List.of("QR-6001")));
    }

    /** The row as the database holds it now, matched by id inside its reservation. */
    private Ticket reread(Ticket row) {
        return ticketRepository.findTicketsInReservation(row.getTicketsReservationId()).stream()
            .filter(t -> t.getId() == row.getId())
            .findFirst()
            .orElseGet(() -> fail("ticket " + row.getId() + " is no longer in reservation " + row.getTicketsReservationId()));
    }

    /** The row by id alone, for a ticket that may have left its reservation. */
    private Ticket rereadById(int ticketId) {
        var rows = ticketRepository.findByIds(List.of(ticketId));
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    /** Setup step through P5: the assignment must succeed. */
    private void assignRitaOk(Ticket row) {
        var response = controller.assign(event.getShortName(), row.getPublicUuid(), ritaBody(), orgAKey);
        assertEquals(200, response.getStatusCode().value());
    }

    /** Setup step through P6: the reissue must succeed; answers the new public uuid. */
    private UUID reissueOk(Ticket row) {
        var response = controller.reissue(event.getShortName(), row.getPublicUuid(), orgAKey);
        assertEquals(200, response.getStatusCode().value());
        return UUID.fromString(Objects.requireNonNull(response.getBody()).publicUuid());
    }

    /** The QR the org-key route must serve: the ticket's code under the event key as re-read from the database. */
    private byte[] expectedQrPng(Ticket row) {
        var reread = eventRepository.findById(event.getId());
        return ImageUtil.createQRCode(row.ticketCode(reread.getPrivateKey(), reread.supportsQRCodeCaseInsensitive()));
    }

    // ---- P5: PUT /qrush-ticket/{publicUuid}/assign ----

    @Test
    @DisplayName("[T22.121] assign writes Rita as holder of an unassigned ticket and answers 200")
    void t22_121_assignWritesHolderOfUnassignedTicket() {
        var t1 = createUnassignedTicket();

        var response = controller.assign(event.getShortName(), t1.getPublicUuid(), ritaBody(), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        var reread = reread(t1);
        assertEquals("Rita Recipient", reread.getFullName());
        assertEquals("rita@example.org", reread.getEmail());
        assertTrue(reread.getAssigned());
    }

    @Test
    @DisplayName("[T22.122] after assign the public QR route serves the ticket as image/png")
    void t22_122_assignedTicketServesItsQrOnThePublicRoute() throws Exception {
        var t1 = createUnassignedTicket();
        assignRitaOk(t1);

        var response = new MockHttpServletResponse();
        upstreamTicketController.showQrCode(event.getShortName(), t1.getPublicUuid(), response);

        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getContentType());
    }

    @Test
    @DisplayName("[T22.123] assign stores the ticket number from additional and the attendee export lists it")
    void t22_123_assignStoresTicketNumberFromAdditional() {
        var t1 = createUnassignedTicket();

        var response = controller.assign(event.getShortName(), t1.getPublicUuid(), ritaBodyWithTicketNumber("QR-5001"), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        var attendees = Objects.requireNonNull(controller.qrushAttendees(event.getShortName(), orgAKey).getBody());
        assertTrue(ticketNumbersIn(attendees).contains("QR-5001"), "ticket numbers " + ticketNumbersIn(attendees));
    }

    @Test
    @DisplayName("[T22.124] (pin) assign without an additional map still assigns")
    void t22_124_assignWithoutAdditionalMapStillAssigns() {
        var t1 = createUnassignedTicket();
        var form = ritaBody();
        form.setAdditional(null);

        var response = controller.assign(event.getShortName(), t1.getPublicUuid(), form, orgAKey);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(reread(t1).getAssigned());
    }

    @Test
    @DisplayName("[T22.125] assign with an invalid e-mail answers 422 and leaves the ticket unassigned")
    void t22_125_assignWithInvalidEmailAnswers422() {
        var t1 = createUnassignedTicket();
        var form = ritaBody();
        form.setEmail("not-an-email");

        var response = controller.assign(event.getShortName(), t1.getPublicUuid(), form, orgAKey);

        assertEquals(422, response.getStatusCode().value());
        assertFalse(reread(t1).getAssigned());
    }

    @Test
    @DisplayName("[T22.126] crossOrgKeyCannotAssignForeignTicket: org B's key is denied on org A's slug")
    void crossOrgKeyCannotAssignForeignTicket() {
        var t1 = createUnassignedTicket();
        var orgB = createForeignOrg();

        assertThrows(AccessDeniedException.class,
            () -> controller.assign(event.getShortName(), t1.getPublicUuid(), ritaBody(), orgB.key()));
        assertFalse(reread(t1).getAssigned());
    }

    @Test
    @DisplayName("[T22.127] assign on org A's slug refuses a ticket of another event with 404 and leaves its holder alone")
    void t22_127_assignRefusesTicketOfAnotherEvent() {
        var orgB = createForeignOrg();
        var tb = createForeignConfirmedTicket(orgB);

        var response = controller.assign(event.getShortName(), tb.getPublicUuid(), ritaBody(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
        var reread = reread(tb);
        assertEquals(tb.getFullName(), reread.getFullName());
        assertEquals(tb.getEmail(), reread.getEmail());
        assertNotEquals("Rita Recipient", reread.getFullName());
        assertNotEquals("rita@example.org", reread.getEmail());
    }

    @Test
    @DisplayName("[T22.128] assign on an unknown slug is denied, never a 404")
    void t22_128_assignOnUnknownSlugIsDenied() {
        assertThrows(AccessDeniedException.class,
            () -> controller.assign("does-not-exist", U, ritaBody(), orgAKey));
    }

    @Test
    @DisplayName("[T22.129] assign for a uuid that belongs to no ticket answers 404")
    void t22_129_assignUnknownTicketAnswers404() {
        var response = controller.assign(event.getShortName(), U, ritaBody(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("[T22.130] assign refuses a ticket of a reservation that was never confirmed with 404")
    void t22_130_assignRefusesTicketOfUnconfirmedReservation() {
        var ticket = singleTicketOf(createReservation(event, orgAKey, List.of("QR-5002")));

        var response = controller.assign(event.getShortName(), ticket.getPublicUuid(), ritaBody(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
        assertNotEquals("rita@example.org", reread(ticket).getEmail());
    }

    @Test
    @DisplayName("[T22.131] (pin) assigning the same body again answers 200 and keeps the holder (retry-safe)")
    void t22_131_assignTwiceWithSameBodyKeepsHolder() {
        var t1 = createUnassignedTicket();
        assignRitaOk(t1);

        var again = controller.assign(event.getShortName(), t1.getPublicUuid(), ritaBody(), orgAKey);

        assertEquals(200, again.getStatusCode().value());
        var reread = reread(t1);
        assertEquals("Rita Recipient", reread.getFullName());
        assertEquals("rita@example.org", reread.getEmail());
    }

    // ---- P6: POST /qrush-ticket/{publicUuid}/reissue ----

    @Test
    @DisplayName("[T22.141] reissue gives an unassigned ticket fresh uuid and public uuid and changes nothing else")
    void t22_141_reissueRotatesBothUuidsAndNothingElse() {
        var t1 = createUnassignedTicket();
        var p0 = t1.getPublicUuid();
        var i0 = t1.getUuid();

        var response = controller.reissue(event.getShortName(), p0, orgAKey);

        assertEquals(200, response.getStatusCode().value());
        var newPublicUuid = UUID.fromString(Objects.requireNonNull(response.getBody()).publicUuid());
        assertNotEquals(p0, newPublicUuid);
        var reread = reread(t1);
        assertEquals(newPublicUuid, reread.getPublicUuid());
        assertNotEquals(i0, reread.getUuid());
        assertEquals(Ticket.TicketStatus.TO_BE_PAID, reread.getStatus());
        assertEquals(t1.getTicketsReservationId(), reread.getTicketsReservationId());
        assertEquals(t1.getCategoryId(), reread.getCategoryId());
        assertFalse(reread.getAssigned());
    }

    @Test
    @DisplayName("[T22.142] reissue keeps the holder of the ticket and leaves its sibling's uuids alone")
    void t22_142_reissueKeepsHolderAndSiblingUuids() {
        var pair = createAssignedTicketPair();
        var a = pair.get(0);
        var b = pair.get(1);

        var response = controller.reissue(event.getShortName(), a.getPublicUuid(), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        var rereadA = reread(a);
        assertEquals(a.getFullName(), rereadA.getFullName());
        assertEquals(a.getEmail(), rereadA.getEmail());
        assertTrue(rereadA.getAssigned());
        var rereadB = reread(b);
        assertEquals(b.getPublicUuid(), rereadB.getPublicUuid());
        assertEquals(b.getUuid(), rereadB.getUuid());
    }

    @Test
    @DisplayName("[T22.143] after reissue the old public uuid serves no QR on the public route (403)")
    void t22_143_oldPublicUuidServesNoQrAfterReissue() throws Exception {
        var a = createAssignedTicketPair().get(0);
        var p0 = a.getPublicUuid();
        reissueOk(a);

        var response = new MockHttpServletResponse();
        upstreamTicketController.showQrCode(event.getShortName(), p0, response);

        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("[T22.144] after reissue the new public uuid serves the QR on the public route as image/png")
    void t22_144_newPublicUuidServesQrAfterReissue() throws Exception {
        var a = createAssignedTicketPair().get(0);
        var newPublicUuid = reissueOk(a);

        var response = new MockHttpServletResponse();
        upstreamTicketController.showQrCode(event.getShortName(), newPublicUuid, response);

        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getContentType());
    }

    @Test
    @DisplayName("[T22.145] reissue changes the door-list signature hash to the one of the new uuid")
    void t22_145_reissueChangesSignatureHash() {
        var a = createAssignedTicketPair().get(0);
        var h0 = expectedSignatureHash(a);
        reissueOk(a);

        var response = controller.qrushTicketSignatures(event.getShortName(), List.of(a.getId()), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        var signatures = Objects.requireNonNull(response.getBody());
        assertEquals(1, signatures.size());
        var signatureHash = signatures.get(0).signatureHash();
        assertNotEquals(h0, signatureHash);
        assertEquals(expectedSignatureHash(reread(a)), signatureHash);
    }

    @Test
    @DisplayName("[T22.146] (pin) the ticket numbers stay in the attendee export after a reissue")
    void t22_146_ticketNumbersSurviveReissue() {
        var a = createAssignedTicketPair().get(0);
        reissueOk(a);

        var attendees = Objects.requireNonNull(controller.qrushAttendees(event.getShortName(), orgAKey).getBody());

        var numbers = ticketNumbersIn(attendees);
        assertTrue(numbers.contains("QR-4001"), "ticket numbers " + numbers);
        assertTrue(numbers.contains("QR-4002"), "ticket numbers " + numbers);
    }

    @Test
    @DisplayName("[T22.147] reissue with the old public uuid a second time answers 404")
    void t22_147_reissueWithOldUuidAgainAnswers404() {
        var a = createAssignedTicketPair().get(0);
        var p0 = a.getPublicUuid();
        reissueOk(a);

        var again = controller.reissue(event.getShortName(), p0, orgAKey);

        assertEquals(404, again.getStatusCode().value());
    }

    @Test
    @DisplayName("[T22.148] assign on the old public uuid after a reissue answers 404")
    void t22_148_assignWithOldUuidAfterReissueAnswers404() {
        var a = createAssignedTicketPair().get(0);
        var p0 = a.getPublicUuid();
        reissueOk(a);

        var response = controller.assign(event.getShortName(), p0, ritaBody(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("[T22.149] reissue keeps the ACQUIRED status")
    void t22_149_reissueKeepsAcquiredStatus() {
        var t1 = createUnassignedTicket();
        setTicketStatus(t1.getTicketsReservationId(), Ticket.TicketStatus.ACQUIRED);

        var response = controller.reissue(event.getShortName(), t1.getPublicUuid(), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Ticket.TicketStatus.ACQUIRED, reread(t1).getStatus());
    }

    @Test
    @DisplayName("[T22.150] reissue refuses a CHECKED_IN ticket with 400 and keeps its uuids")
    void t22_150_reissueRefusesCheckedInTicketWith400() {
        var pair = createAssignedTicketPair();
        var a = pair.get(0);
        setTicketStatus(a.getTicketsReservationId(), Ticket.TicketStatus.CHECKED_IN);

        var response = controller.reissue(event.getShortName(), a.getPublicUuid(), orgAKey);

        assertEquals(400, response.getStatusCode().value());
        var reread = reread(a);
        assertEquals(a.getPublicUuid(), reread.getPublicUuid());
        assertEquals(a.getUuid(), reread.getUuid());
    }

    @Test
    @DisplayName("[T22.151] crossOrgKeyCannotReissueForeignTicket: org B's key is denied on org A's slug")
    void crossOrgKeyCannotReissueForeignTicket() {
        var a = createAssignedTicketPair().get(0);
        var orgB = createForeignOrg();

        assertThrows(AccessDeniedException.class,
            () -> controller.reissue(event.getShortName(), a.getPublicUuid(), orgB.key()));
        var reread = reread(a);
        assertEquals(a.getPublicUuid(), reread.getPublicUuid());
        assertEquals(a.getUuid(), reread.getUuid());
    }

    @Test
    @DisplayName("[T22.152] reissue on an unknown slug is denied, never a 404")
    void t22_152_reissueOnUnknownSlugIsDenied() {
        assertThrows(AccessDeniedException.class,
            () -> controller.reissue("does-not-exist", U, orgAKey));
    }

    @Test
    @DisplayName("[T22.153] reissue on org A's slug refuses a ticket of another event with 404")
    void t22_153_reissueRefusesTicketOfAnotherEvent() {
        var orgB = createForeignOrg();
        var tb = createForeignConfirmedTicket(orgB);

        var response = controller.reissue(event.getShortName(), tb.getPublicUuid(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(tb.getPublicUuid(), reread(tb).getPublicUuid());
    }

    @Test
    @DisplayName("[T22.154] reissue refuses a ticket of a reservation that was never confirmed with 404")
    void t22_154_reissueRefusesTicketOfUnconfirmedReservation() {
        var ticket = singleTicketOf(createReservation(event, orgAKey, List.of("QR-5003")));

        var response = controller.reissue(event.getShortName(), ticket.getPublicUuid(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(ticket.getPublicUuid(), reread(ticket).getPublicUuid());
    }

    @Test
    @DisplayName("[T22.155] reissue refuses a ticket that a full void released, with 404")
    void t22_155_reissueRefusesVoidedTicket() {
        var a = createAssignedTicketPair().get(0);
        var p0 = a.getPublicUuid();
        var voided = qrushReservationController.refundVoid(event.getShortName(), a.getTicketsReservationId(), null, orgAKey);
        assertEquals(200, voided.getStatusCode().value());
        var v = rereadById(a.getId());
        // batchReleaseTickets frees the seat and rotates the public uuid, so V is a dead row with a fresh uuid
        assertEquals(Ticket.TicketStatus.RELEASED, v.getStatus());
        assertNull(v.getTicketsReservationId());
        assertNotEquals(p0, v.getPublicUuid());

        var response = controller.reissue(event.getShortName(), v.getPublicUuid(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
        assertEquals(v.getPublicUuid(), rereadById(a.getId()).getPublicUuid());
    }

    @Test
    @DisplayName("[T22.156] reissue for a uuid that belongs to no ticket answers 404")
    void t22_156_reissueUnknownTicketAnswers404() {
        var response = controller.reissue(event.getShortName(), U, orgAKey);

        assertEquals(404, response.getStatusCode().value());
    }

    // ---- P7: GET /qrush-ticket/{publicUuid}/code.png ----

    @Test
    @DisplayName("[T22.161] qrCode serves the PNG of an assigned ticket with image/png and no-store")
    void t22_161_qrCodeServesPngOfAssignedTicket() {
        var a = createAssignedTicketPair().get(0);

        var response = controller.qrCode(event.getShortName(), a.getPublicUuid(), orgAKey);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertArrayEquals(expectedQrPng(reread(a)), response.getBody());
    }

    @Test
    @DisplayName("[T22.162] qrCode refuses an unassigned ticket with 404")
    void t22_162_qrCodeRefusesUnassignedTicket() {
        var t1 = createUnassignedTicket();

        var response = controller.qrCode(event.getShortName(), t1.getPublicUuid(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("[T22.163] crossOrgKeyCannotReadForeignTicketQr: org B's key is denied on org A's slug")
    void crossOrgKeyCannotReadForeignTicketQr() {
        var a = createAssignedTicketPair().get(0);
        var orgB = createForeignOrg();

        assertThrows(AccessDeniedException.class,
            () -> controller.qrCode(event.getShortName(), a.getPublicUuid(), orgB.key()));
    }

    @Test
    @DisplayName("[T22.164] qrCode on an unknown slug is denied, never a 404")
    void t22_164_qrCodeOnUnknownSlugIsDenied() {
        assertThrows(AccessDeniedException.class,
            () -> controller.qrCode("does-not-exist", U, orgAKey));
    }

    @Test
    @DisplayName("[T22.165] qrCode on org A's slug refuses a ticket of another event with 404")
    void t22_165_qrCodeRefusesTicketOfAnotherEvent() {
        var orgB = createForeignOrg();
        var tb = createForeignConfirmedTicket(orgB);

        var response = controller.qrCode(event.getShortName(), tb.getPublicUuid(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("[T22.166] qrCode refuses an assigned ticket of a reservation that was never confirmed with 404")
    void t22_166_qrCodeRefusesTicketOfUnconfirmedReservation() {
        var ticket = singleTicketOf(createReservation(event, orgAKey, List.of("QR-5004")));

        var response = controller.qrCode(event.getShortName(), ticket.getPublicUuid(), orgAKey);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    @DisplayName("[T22.167] after a reissue qrCode answers 404 for the old public uuid and a different PNG for the new one")
    void t22_167_qrCodeOfOldUuidIsDeadAfterReissue() {
        var a = createAssignedTicketPair().get(0);
        var p0 = a.getPublicUuid();
        var before = controller.qrCode(event.getShortName(), p0, orgAKey);
        assertEquals(200, before.getStatusCode().value());
        var q0 = Objects.requireNonNull(before.getBody());
        var newPublicUuid = reissueOk(a);

        var oldAnswer = controller.qrCode(event.getShortName(), p0, orgAKey);
        var newAnswer = controller.qrCode(event.getShortName(), newPublicUuid, orgAKey);

        assertEquals(404, oldAnswer.getStatusCode().value());
        assertEquals(200, newAnswer.getStatusCode().value());
        assertFalse(Arrays.equals(q0, Objects.requireNonNull(newAnswer.getBody())), "the new QR must differ from the old one");
    }
}
