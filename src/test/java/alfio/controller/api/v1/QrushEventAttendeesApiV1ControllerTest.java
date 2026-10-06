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
import alfio.controller.api.v1.admin.ReservationApiV1Controller;
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
import alfio.util.Json;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
        var reservationId = createReservation(event, orgAKey, ticketNumbers);
        var confirmation = new ReservationConfirmationRequest(
            new TransactionDetails("TRID", new BigDecimal("100.00"),
                LocalDateTime.now(clockProvider.getClock()), "notes", PaymentProxy.ON_SITE),
            new Notification(true, true), null);
        var response = upstreamReservationController.confirmReservation(reservationId, confirmation, orgAKey);
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
}
