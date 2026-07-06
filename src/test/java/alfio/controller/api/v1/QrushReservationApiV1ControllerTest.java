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
import alfio.controller.api.v1.admin.QrushReservationApiV1Controller;
import alfio.controller.api.v1.admin.ReservationApiV1Controller;
import alfio.manager.EventManager;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.TicketCategory;
import alfio.model.api.v1.admin.AttendeesByCategory;
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
import alfio.repository.TicketReservationRepository;
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.AuthorityRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
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
class QrushReservationApiV1ControllerTest {

    private static final String DEFAULT_CATEGORY_NAME = "default";

    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private ClockProvider clockProvider;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserManager userManager;
    @Autowired private EventManager eventManager;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketCategoryRepository ticketCategoryRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketReservationRepository ticketReservationRepository;
    @Autowired private ReservationApiV1Controller upstreamController;
    @Autowired private QrushReservationApiV1Controller controller;
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
    }

    /**
     * Builds the API-key principal the way the production filter does
     * (APITokenAuthWebSecurity: authorities from authorityRepository.findRoles). An empty
     * authority list makes AccessService#isSystemApiUser vacuously true (allMatch over an empty
     * stream) — which would silently bypass org isolation and void the cross-org tests below.
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

    /**
     * Reservation via the upstream org-key controller — the exact path phase-6 CFs use.
     * Mirrors the upstream ReservationApiV1ControllerTest reservation shape: one populated
     * AttendeeData per ticket (first/last/email) plus a buyer ReservationUser, so
     * AdminReservationManager.completeReservation has the contact data it requires.
     */
    private String createReservation(APITokenAuthentication principal, int ticketCount) {
        var category = ticketCategoryRepository.findFirstWithAvailableTickets(event.getId()).orElseThrow();
        var attendees = IntStream.range(0, ticketCount)
            .mapToObj(i -> new AttendeeData("firstName", "lastName", "attendee" + i + "@example.org",
                null, Map.of("source", "qrush-test"), null))
            .toList();
        var buyer = new ReservationUser(null, "Buyer", "McBuyer", "buyer@example.org", null);
        var creationRequest = new TicketReservationCreationRequest(
            List.of(new AttendeesByCategory(category.getId(), ticketCount, attendees, null)),
            List.of(), null, buyer, null, "en", null, null);
        var response = upstreamController.createTicketsReservation(event.getShortName(), creationRequest, principal);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return Objects.requireNonNull(Objects.requireNonNull(response.getBody()).id());
    }

    /**
     * Finalize the way the upstream reference test confirms: a full-amount ON_SITE transaction
     * -> COMPLETE. The $0 comp-finalize contract is exercised by phase-6's pinned integration
     * test against the deployed build, NOT here.
     */
    private String createConfirmedReservation(APITokenAuthentication principal, int ticketCount) {
        var reservationId = createReservation(principal, ticketCount);
        var confirmation = new ReservationConfirmationRequest(
            new TransactionDetails("TRID", new BigDecimal("100.00"),
                LocalDateTime.now(clockProvider.getClock()), "notes", PaymentProxy.ON_SITE),
            new Notification(true, true), null);
        var response = upstreamController.confirmReservation(reservationId, confirmation, principal);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return reservationId;
    }

    @Test
    void getReservationReturnsStatusAndPublicTicketUuids() {
        var reservationId = createConfirmedReservation(orgAKey, 2);

        var response = controller.getReservation(event.getShortName(), reservationId, orgAKey);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        var body = Objects.requireNonNull(response.getBody());
        assertEquals(reservationId, body.reservationId());
        assertEquals("COMPLETE", body.status());
        assertEquals(2, body.tickets().size());
        var expectedPublicUuids = ticketRepository.findTicketsInReservation(reservationId).stream()
            .map(t -> t.getPublicUuid().toString()).sorted().toList();
        var actualPublicUuids = body.tickets().stream()
            .map(QrushReservationApiV1Controller.QrushTicketDetail::publicUuid).sorted().toList();
        assertEquals(expectedPublicUuids, actualPublicUuids);
        assertTrue(body.tickets().stream().allMatch(t -> t.categoryId() > 0));
        // ON_SITE confirm leaves the reservation COMPLETE but tickets TO_BE_PAID (paid at the venue),
        // matching upstream ReservationApiV1ControllerTest#createAndConfirmTicket.
        assertTrue(body.tickets().stream().allMatch(t -> "TO_BE_PAID".equals(t.status())));
    }

    @Test
    void getReservationExposesPendingTicketsBeforeFinalize() {
        // retrieveDetail (upstream) hides resources for PENDING tickets — our lean read must not
        var reservationId = createReservation(orgAKey, 1);

        var response = controller.getReservation(event.getShortName(), reservationId, orgAKey);

        var body = Objects.requireNonNull(response.getBody());
        assertEquals("PENDING", body.status());
        assertEquals(1, body.tickets().size());
        assertFalse(body.tickets().get(0).publicUuid().isBlank());
    }

    @Test
    void refundVoidCancelsReservationAndKillsTicketQrs() {
        var reservationId = createConfirmedReservation(orgAKey, 2);
        var uuidsBeforeVoid = ticketRepository.findTicketsInReservation(reservationId).stream()
            .map(t -> t.getPublicUuid().toString()).toList();

        var response = controller.refundVoid(event.getShortName(), reservationId, null, orgAKey);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        var body = Objects.requireNonNull(response.getBody());
        assertTrue(body.success());
        assertEquals("CANCELLED", body.reservationStatus());
        assertFalse(body.alreadyVoided());
        assertNull(body.error());

        // reservation cancelled, tickets detached
        var reservation = ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow();
        assertEquals(alfio.model.TicketReservation.TicketReservationStatus.CANCELLED, reservation.getStatus());
        assertTrue(ticketRepository.findTicketsInReservation(reservationId).isEmpty());
        // batchReleaseTickets regenerated public uuids -> every pre-void QR identifier is dead
        for (var oldUuid : uuidsBeforeVoid) {
            assertTrue(ticketRepository.findOptionalByPublicUUID(UUID.fromString(oldUuid)).isEmpty());
        }
    }

    @Test
    void refundVoidIsRejectedForCheckedInTickets() {
        var reservationId = createConfirmedReservation(orgAKey, 1);
        ticketRepository.updateTicketsStatusWithReservationId(reservationId,
            alfio.model.Ticket.TicketStatus.CHECKED_IN.name());

        var response = controller.refundVoid(event.getShortName(), reservationId, null, orgAKey);

        assertEquals(400, response.getStatusCode().value());
        var body = Objects.requireNonNull(response.getBody());
        assertFalse(body.success());
        assertNotNull(body.error());
        // nothing changed
        assertEquals(alfio.model.Ticket.TicketStatus.CHECKED_IN,
            ticketRepository.findTicketsInReservation(reservationId).get(0).getStatus());
    }

    @Test
    void refundVoidRetryNeverVoidsTwice() {
        var reservationId = createConfirmedReservation(orgAKey, 1);
        var first = controller.refundVoid(event.getShortName(), reservationId, null, orgAKey);
        assertTrue(first.getStatusCode().is2xxSuccessful());
        assertFalse(Objects.requireNonNull(first.getBody()).alreadyVoided());
        assertEquals(alfio.model.TicketReservation.TicketReservationStatus.CANCELLED,
            ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow().getStatus());

        // A retry of a completed void is idempotent. The cancelled reservation keeps its row + event
        // link, so the ownership guard still passes; the already-CANCELLED short-circuit then returns
        // 200 alreadyVoided=true WITHOUT a second data-layer void — no exception, no double void.
        var retry = assertDoesNotThrow(
            () -> controller.refundVoid(event.getShortName(), reservationId, null, orgAKey));
        assertTrue(retry.getStatusCode().is2xxSuccessful());
        var retryBody = Objects.requireNonNull(retry.getBody());
        assertTrue(retryBody.success());
        assertTrue(retryBody.alreadyVoided());
        assertEquals("CANCELLED", retryBody.reservationStatus());
        assertNull(retryBody.error());
        assertEquals(alfio.model.TicketReservation.TicketReservationStatus.CANCELLED,
            ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow().getStatus());
    }

    @Test
    void partialVoidReleasesOnlyRequestedTickets() {
        var reservationId = createConfirmedReservation(orgAKey, 3);
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        var voidedUuid = tickets.get(0).getPublicUuid().toString();
        var keptUuids = List.of(tickets.get(1).getPublicUuid().toString(), tickets.get(2).getPublicUuid().toString());

        var response = controller.refundVoid(event.getShortName(), reservationId,
            new QrushReservationApiV1Controller.RefundVoidRequest(List.of(voidedUuid)), orgAKey);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertTrue(Objects.requireNonNull(response.getBody()).success());

        var remaining = ticketRepository.findTicketsInReservation(reservationId);
        assertEquals(2, remaining.size());
        var remainingUuids = remaining.stream().map(t -> t.getPublicUuid().toString()).sorted().toList();
        assertEquals(keptUuids.stream().sorted().toList(), remainingUuids);
        assertTrue(ticketRepository.findOptionalByPublicUUID(UUID.fromString(voidedUuid)).isEmpty());
        // reservation survives a partial void
        var reservation = ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow();
        assertNotEquals(alfio.model.TicketReservation.TicketReservationStatus.CANCELLED, reservation.getStatus());
    }

    @Test
    void partialVoidOfAllTicketsCancelsTheReservation() {
        var reservationId = createConfirmedReservation(orgAKey, 2);
        var allUuids = ticketRepository.findTicketsInReservation(reservationId).stream()
            .map(t -> t.getPublicUuid().toString()).toList();

        var response = controller.refundVoid(event.getShortName(), reservationId,
            new QrushReservationApiV1Controller.RefundVoidRequest(allUuids), orgAKey);

        assertTrue(Objects.requireNonNull(response.getBody()).success());
        var reservation = ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow();
        assertEquals(alfio.model.TicketReservation.TicketReservationStatus.CANCELLED, reservation.getStatus());
    }

    @Test
    void partialVoidRejectsUnknownTicketUuidWithoutSideEffects() {
        var reservationId = createConfirmedReservation(orgAKey, 2);

        var response = controller.refundVoid(event.getShortName(), reservationId,
            new QrushReservationApiV1Controller.RefundVoidRequest(List.of(UUID.randomUUID().toString())), orgAKey);

        assertEquals(400, response.getStatusCode().value());
        assertFalse(Objects.requireNonNull(response.getBody()).success());
        assertEquals(2, ticketRepository.findTicketsInReservation(reservationId).size());
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
    void crossOrgKeyCannotReadForeignReservation() {
        var reservationId = createConfirmedReservation(orgAKey, 1);
        var orgB = createForeignOrg();

        // org B key + org A slug + org A reservation id -> denied, no data
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.getReservation(event.getShortName(), reservationId, orgB.key()));
        // org B key + org B's OWN slug + org A reservation id (id-guessing) -> denied
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.getReservation(orgB.event().getShortName(), reservationId, orgB.key()));
    }

    @Test
    void crossOrgKeyCannotVoidForeignReservation() {
        var reservationId = createConfirmedReservation(orgAKey, 1);
        var orgB = createForeignOrg();

        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.refundVoid(event.getShortName(), reservationId, null, orgB.key()));
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.refundVoid(orgB.event().getShortName(), reservationId, null, orgB.key()));

        // the attack changed NOTHING: reservation still COMPLETE, both QR uuids still live
        var reservation = ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow();
        assertEquals(alfio.model.TicketReservation.TicketReservationStatus.COMPLETE, reservation.getStatus());
        assertEquals(1, ticketRepository.findTicketsInReservation(reservationId).size());
    }

    @Test
    void crossOrgKeyCannotVoidAlreadyCancelledForeignReservation() {
        var reservationId = createConfirmedReservation(orgAKey, 1);
        // owner voids first -> reservation is now CANCELLED
        controller.refundVoid(event.getShortName(), reservationId, null, orgAKey);
        assertEquals(alfio.model.TicketReservation.TicketReservationStatus.CANCELLED,
            ticketReservationRepository.findOptionalReservationById(reservationId).orElseThrow().getStatus());
        var orgB = createForeignOrg();

        // the ownership guard runs BEFORE the already-CANCELLED short-circuit, so a foreign key probing
        // a cancelled reservation gets the SAME denial as a live one — never a 200 alreadyVoided:true
        // that would leak the reservation's cancelled state.
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.refundVoid(event.getShortName(), reservationId, null, orgB.key()));
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.refundVoid(orgB.event().getShortName(), reservationId, null, orgB.key()));
    }

    @Test
    void unknownSlugAndUnknownReservationAreDeniedNotFound() {
        var reservationId = createConfirmedReservation(orgAKey, 1);

        // nonexistent slug -> AccessDenied (403 over HTTP) — never a 404 that confirms existence
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.getReservation("does-not-exist", reservationId, orgAKey));
        // own slug + nonexistent reservation id -> same
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.getReservation(event.getShortName(), UUID.randomUUID().toString(), orgAKey));
        assertThrows(alfio.manager.support.AccessDeniedException.class,
            () -> controller.refundVoid(event.getShortName(), UUID.randomUUID().toString(), null, orgAKey));
    }
}
