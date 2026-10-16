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
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushGroupCeiling;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHeldTicket;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHold;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHoldBusy;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHoldCeilings;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHoldRefused;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHoldRemaining;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushHoldRequest;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushRowCeiling;
import alfio.controller.api.v1.admin.QrushHoldApiV1Controller.QrushRowRemaining;
import alfio.controller.api.v1.admin.QrushReservationApiV1Controller;
import alfio.controller.api.v1.admin.ReservationApiV1Controller;
import alfio.controller.api.v1.admin.ReservationApiV1Controller.CreationResponse;
import alfio.manager.EventManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.i18n.MessageSourceManager;
import alfio.manager.support.AccessDeniedException;
import alfio.manager.user.UserManager;
import alfio.model.Event;
import alfio.model.Ticket;
import alfio.model.TicketCategory;
import alfio.model.api.v1.admin.AttendeesByCategory;
import alfio.model.api.v1.admin.ReservationConfirmationRequest;
import alfio.model.api.v1.admin.ReservationUser;
import alfio.model.api.v1.admin.TicketReservationCreationRequest;
import alfio.model.metadata.AlfioMetadata;
import alfio.model.modification.AdminReservationModification.Notification;
import alfio.model.modification.AdminReservationModification.TransactionDetails;
import alfio.model.modification.DateTimeModification;
import alfio.model.modification.TicketCategoryModification;
import alfio.model.system.ConfigurationKeys;
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
import alfio.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static alfio.test.util.IntegrationTestUtil.DESCRIPTION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P10: the hold route, idempotent on the order and with the qrush ceilings decided under one lock per
 * event. Controllers are called directly, as in the other qrush suites; the races run on an executor.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class QrushHoldApiV1ControllerTest {

    /** "QHLD": the advisory-lock namespace of the hold route. */
    private static final int HOLD_LOCK_NAMESPACE = 0x51484C44;
    private static final String LOCK_TIMEOUT_PROPERTY = "qrush.hold.lock-timeout-ms";

    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private ClockProvider clockProvider;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserManager userManager;
    @Autowired private EventManager eventManager;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketCategoryRepository ticketCategoryRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketReservationRepository ticketReservationRepository;
    @Autowired private TicketReservationManager ticketReservationManager;
    @Autowired private MessageSourceManager messageSourceManager;
    @Autowired private ReservationApiV1Controller upstreamController;
    @Autowired private QrushReservationApiV1Controller qrushReservationController;
    @Autowired private QrushHoldApiV1Controller controller;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ConfigurableEnvironment environment;
    @Autowired private ObjectMapper objectMapper;

    /** An organization with one event of two bounded categories A and B (10 tickets each) and its API key. */
    private record Fixture(Event event, APITokenAuthentication key, int a, int b) {
        String slug() {
            return event.getShortName();
        }
    }

    private Fixture e;

    @BeforeEach
    void setUp() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        // as on the boxes
        setSystemConfiguration(ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION, "10");
        e = newFixture();
    }

    /** A system-level configuration row as this test found it; absent when no row existed. */
    private record FoundSetting(String value, String description) {}

    // the database outlives this class: every system setting written here is put back after each test
    private final Map<String, Optional<FoundSetting>> foundSettings = new LinkedHashMap<>();

    private void rememberSystemConfiguration(ConfigurationKeys key) {
        foundSettings.computeIfAbsent(key.getValue(), k -> jdbc.query(
            "select c_value, description from configuration where c_key = :key", new MapSqlParameterSource("key", k),
            (rs, row) -> new FoundSetting(rs.getString("c_value"), rs.getString("description"))).stream().findFirst());
    }

    private void setSystemConfiguration(ConfigurationKeys key, String value) {
        rememberSystemConfiguration(key);
        configurationRepository.deleteByKey(key.getValue());
        configurationRepository.insert(key.getValue(), value, "");
    }

    private void removeSystemConfiguration(ConfigurationKeys key) {
        rememberSystemConfiguration(key);
        configurationRepository.deleteByKey(key.getValue());
    }

    @AfterEach
    void restoreSystemConfiguration() {
        foundSettings.forEach((key, found) -> {
            configurationRepository.deleteByKey(key);
            found.ifPresent(f -> configurationRepository.insert(key, f.value(), f.description()));
        });
        foundSettings.clear();
    }

    private APITokenAuthentication apiKey(String username) {
        var authorities = authorityRepository.findRoles(username).stream()
            .map(SimpleGrantedAuthority::new).toList();
        return new APITokenAuthentication(username, null, authorities);
    }

    private TicketCategoryModification boundedCategory(String name, int maxTickets) {
        return new TicketCategoryModification(null, name, TicketCategory.TicketAccessType.INHERIT, maxTickets,
            new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
            new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
            DESCRIPTION, BigDecimal.TEN, false, "", true, null, null, null, null, null, 0, null, null, AlfioMetadata.empty());
    }

    private int categoryId(Event target, String name) {
        return ticketCategoryRepository.findAllTicketCategories(target.getId()).stream()
            .filter(c -> name.equals(c.getName()))
            .findFirst().orElseThrow().getId();
    }

    /** A fresh organization (initEvent randomizes all names) with the A/B event and an API key of its own. */
    private Fixture newFixture() {
        var created = IntegrationTestUtil.initEvent(List.of(boundedCategory("A", 10), boundedCategory("B", 10)),
            organizationRepository, userManager, eventManager, eventRepository).getLeft();
        var username = UUID.randomUUID().toString();
        userManager.insertUser(created.getOrganizationId(), username, "test", "test",
            "test@example.com", Role.API_CONSUMER, User.Type.INTERNAL, null);
        return new Fixture(created, apiKey(username), categoryId(created, "A"), categoryId(created, "B"));
    }

    private static final ReservationUser BUYER = new ReservationUser(null, "Buyer", "McBuyer", "buyer@example.org", null);

    private static List<AttendeesByCategory> basket(Map<Integer, Integer> quantities) {
        return quantities.entrySet().stream()
            .map(q -> new AttendeesByCategory(q.getKey(), q.getValue(), null, null))
            .toList();
    }

    private static Map<Integer, Integer> ticketsOf(int category, int quantity) {
        var quantities = new LinkedHashMap<Integer, Integer>();
        quantities.put(category, quantity);
        return quantities;
    }

    private static QrushHoldRequest holdRequest(String orderId, Map<Integer, Integer> quantities, QrushHoldCeilings ceilings) {
        return new QrushHoldRequest(basket(quantities), List.of(), "en", BUYER, orderId, ceilings);
    }

    private static QrushHoldCeilings rows(QrushRowCeiling... rows) {
        return new QrushHoldCeilings(null, List.of(rows), List.of());
    }

    private ResponseEntity<?> hold(Fixture target, QrushHoldRequest request) {
        return controller.hold(target.slug(), request, target.key());
    }

    private static QrushHold held(ResponseEntity<?> response) {
        assertEquals(200, response.getStatusCode().value(), "body " + response.getBody());
        return assertInstanceOf(QrushHold.class, response.getBody());
    }

    private static QrushHoldRefused refused(ResponseEntity<?> response) {
        assertEquals(409, response.getStatusCode().value(), "body " + response.getBody());
        return assertInstanceOf(QrushHoldRefused.class, response.getBody());
    }

    /** Setup through the upstream create, so a fixture never depends on the route under test. */
    private String upstreamHold(Fixture target, int categoryId, int quantity) {
        var request = new TicketReservationCreationRequest(basket(ticketsOf(categoryId, quantity)), List.of(), null, BUYER, null, "en", null, null);
        var response = upstreamController.createTicketsReservation(target.slug(), request, target.key());
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return Objects.requireNonNull(Objects.requireNonNull(response.getBody()).id());
    }

    /** The production confirm: the ADMIN comp confirm without engine mails, so the tickets are ACQUIRED. */
    private void compConfirm(Fixture target, String reservationId) {
        var confirmation = new ReservationConfirmationRequest(
            new TransactionDetails("qrush-comp", BigDecimal.ZERO,
                LocalDateTime.now(clockProvider.getClock()), "qrush comp ($0) finalize", PaymentProxy.ADMIN),
            new Notification(false, false), null);
        assertTrue(upstreamController.confirmReservation(reservationId, confirmation, target.key())
            .getStatusCode().is2xxSuccessful());
    }

    private int reservationCount(Fixture target) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from tickets_reservation where event_id_fk = :eventId",
            new MapSqlParameterSource("eventId", target.event().getId()), Integer.class));
    }

    /** ext_reference of the reservation's tickets in ascending ticket id. */
    private List<String> extReferences(String reservationId) {
        return jdbc.queryForList("select ext_reference from ticket where tickets_reservation_id = :reservationId order by id",
            new MapSqlParameterSource("reservationId", reservationId), String.class);
    }

    private List<Integer> ticketIds(String reservationId) {
        return jdbc.queryForList("select id from ticket where tickets_reservation_id = :reservationId order by id",
            new MapSqlParameterSource("reservationId", reservationId), Integer.class);
    }

    private int reservationsCarrying(Fixture target, String orderId) {
        return Objects.requireNonNull(jdbc.queryForObject(
            "select count(distinct tickets_reservation_id) from ticket where event_id = :eventId and ext_reference like :prefix",
            new MapSqlParameterSource("eventId", target.event().getId()).addValue("prefix", orderId + ":%"), Integer.class));
    }

    /** Holds the route's advisory lock of an event in a transaction of its own until the latch opens. */
    private Future<?> holdAdvisoryLock(ExecutorService executor, Fixture target, CountDownLatch locked, CountDownLatch release) {
        return executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("select pg_advisory_xact_lock(:namespace, :eventId)::text",
                new MapSqlParameterSource("namespace", HOLD_LOCK_NAMESPACE).addValue("eventId", target.event().getId()), String.class);
            locked.countDown();
            try {
                assertTrue(release.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    /** Raises the route's lock timeout for a race whose loser must wait rather than answer busy. */
    private AutoCloseable raisedLockTimeout() {
        var sources = environment.getPropertySources();
        sources.addFirst(new MapPropertySource("qrush-hold-race", Map.of(LOCK_TIMEOUT_PROPERTY, "10000")));
        return () -> sources.remove("qrush-hold-race");
    }

    private void assertTicketsOfReservation(QrushHold body, int categoryId, int count) {
        assertEquals(count, body.tickets().size());
        assertTrue(body.tickets().stream().allMatch(t -> t.categoryId() == categoryId));
        var ids = body.tickets().stream().map(QrushHeldTicket::id).toList();
        assertEquals(ticketIds(body.reservationId()), ids, "tickets in ascending id");
        var publicUuids = ticketRepository.findTicketsInReservation(body.reservationId()).stream()
            .sorted(Comparator.comparingInt(Ticket::getId))
            .map(t -> t.getPublicUuid().toString())
            .toList();
        assertEquals(publicUuids, body.tickets().stream().map(QrushHeldTicket::publicUuid).toList());
    }

    @Test
    @DisplayName("[P10.1] a hold with a row ceiling answers 200 created, the remaining row and the order reference on each ticket by id")
    void p10_1_holdWithRowCeilingCreatesAndReferencesTheOrder() throws Exception {
        var response = hold(e, holdRequest("o1", ticketsOf(e.a(), 2), rows(new QrushRowCeiling(e.a(), 5))));

        var body = held(response);
        assertTrue(body.created());
        assertTicketsOfReservation(body, e.a(), 2);
        assertEquals(new QrushHoldRemaining(null, List.of(new QrushRowRemaining(e.a(), 3)), List.of()), body.remaining());
        assertEquals(List.of("o1:0", "o1:1"), extReferences(body.reservationId()));
        var reservation = ticketReservationRepository.findOptionalReservationById(body.reservationId()).orElseThrow();
        assertEquals(reservation.getValidity().getTime(), body.validity());
        var json = objectMapper.readTree(objectMapper.writeValueAsString(body));
        assertFalse(json.get("remaining").has("capacity"), "capacity is omitted when it was not sent");
    }

    @Test
    @DisplayName("[P10.2] the same request again answers the same hold with created false and makes no second reservation")
    void p10_2_replayAnswersTheExistingHold() {
        var request = holdRequest("o1", ticketsOf(e.a(), 2), rows(new QrushRowCeiling(e.a(), 5)));
        var first = held(hold(e, request));

        var again = held(hold(e, request));

        assertFalse(again.created());
        assertEquals(first.reservationId(), again.reservationId());
        assertEquals(first.tickets(), again.tickets());
        assertNull(again.remaining(), "a replay carries no remaining");
        assertEquals(1, reservationsCarrying(e, "o1"));
        assertEquals(1, reservationCount(e));
    }

    @Test
    @DisplayName("[P10.3] a hold without ceilings twice answers one reservation; its four tickets carry o4:0 to o4:3 in ascending id")
    void p10_3_holdWithoutCeilingsIsIdempotentAndReferencesByTicketId() {
        var request = holdRequest("o4", ticketsOf(e.a(), 4), null);

        var first = held(hold(e, request));
        var again = held(hold(e, request));

        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals(first.reservationId(), again.reservationId());
        assertNull(first.remaining(), "no remaining without ceilings");
        assertTicketsOfReservation(first, e.a(), 4);
        assertEquals(List.of("o4:0", "o4:1", "o4:2", "o4:3"), extReferences(first.reservationId()));
        assertEquals(1, reservationCount(e));
    }

    @Test
    @DisplayName("[P10.4] org 2's key is refused on E's slug, for org 1's order and for a fresh one, and nothing changes")
    void p10_4_foreignKeyIsRefusedBeforeTheLookup() {
        var request = holdRequest("o1", ticketsOf(e.a(), 2), rows(new QrushRowCeiling(e.a(), 5)));
        var first = held(hold(e, request));
        var org2 = newFixture();

        assertThrows(AccessDeniedException.class, () -> controller.hold(e.slug(), request, org2.key()));
        assertThrows(AccessDeniedException.class,
            () -> controller.hold(e.slug(), holdRequest("o2", ticketsOf(e.a(), 1), null), org2.key()));
        // org 2's own slug with E's categories: the creation-request check refuses another event's categories
        assertThrows(AccessDeniedException.class,
            () -> controller.hold(org2.slug(), holdRequest("o3", ticketsOf(e.a(), 1), null), org2.key()));

        assertEquals(1, reservationCount(e));
        assertEquals(0, reservationCount(org2));
        assertEquals(List.of("o1:0", "o1:1"), extReferences(first.reservationId()));
    }

    @Test
    @DisplayName("[P10.5] with 5 tickets of A held or confirmed, a row ceiling of 5 refuses A x1 with 409 row and remaining 0")
    void p10_5_rowCeilingRefusesWhenTheCategoryIsFull() throws Exception {
        upstreamHold(e, e.a(), 3);
        compConfirm(e, upstreamHold(e, e.a(), 2));

        var body = refused(hold(e, holdRequest("o5", ticketsOf(e.a(), 1), rows(new QrushRowCeiling(e.a(), 5)))));

        assertEquals(new QrushHoldRefused("row", new QrushHoldRemaining(null, List.of(new QrushRowRemaining(e.a(), 0)), List.of())), body);
        var json = objectMapper.readTree(objectMapper.writeValueAsString(body));
        assertFalse(json.get("remaining").has("capacity"), "capacity is omitted when it was not sent");
        assertEquals(2, reservationCount(e));
    }

    @Test
    @DisplayName("[P10.6] two concurrent one-ticket holds of A and B under a group ceiling of 1 answer one 200 and one 409 group")
    void p10_6_groupCeilingDecidesTwoConcurrentHoldsUnderOneLock() throws Exception {
        var warmUp = newFixture();
        held(hold(warmUp, holdRequest("warm-up", ticketsOf(warmUp.a(), 1),
            new QrushHoldCeilings(null, List.of(), List.of(new QrushGroupCeiling(List.of(warmUp.a(), warmUp.b()), 1))))));
        var group = new QrushHoldCeilings(null, List.of(), List.of(new QrushGroupCeiling(List.of(e.a(), e.b()), 1)));
        var executor = Executors.newFixedThreadPool(2);
        try (var ignored = raisedLockTimeout()) {
            var start = new CountDownLatch(1);
            Callable<ResponseEntity<?>> holdA = () -> {
                start.await();
                return hold(e, holdRequest("o6a", ticketsOf(e.a(), 1), group));
            };
            Callable<ResponseEntity<?>> holdB = () -> {
                start.await();
                return hold(e, holdRequest("o6b", ticketsOf(e.b(), 1), group));
            };
            var futureA = executor.submit(holdA);
            var futureB = executor.submit(holdB);
            start.countDown();
            var answers = List.of(futureA.get(30, TimeUnit.SECONDS), futureB.get(30, TimeUnit.SECONDS));

            var statuses = answers.stream().map(r -> r.getStatusCode().value()).sorted().toList();
            assertEquals(List.of(200, 409), statuses);
            var loser = answers.stream().filter(r -> r.getStatusCode().value() == 409).findFirst().orElseThrow();
            assertEquals("group", refused(loser).refused());
            assertEquals(1, reservationCount(e));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[P10.7] with 15 tickets held over A and B, a capacity of 20 refuses B x6 with 409 capacity and remaining 5")
    void p10_7_capacityCeilingCountsEveryCategoryOfTheEvent() {
        upstreamHold(e, e.a(), 10);
        upstreamHold(e, e.b(), 5);

        var body = refused(hold(e, holdRequest("o7", ticketsOf(e.b(), 6), new QrushHoldCeilings(20, List.of(), List.of()))));

        assertEquals("capacity", body.refused());
        assertEquals(new QrushHoldRemaining(5, List.of(), List.of()), body.remaining());
        assertEquals(2, reservationCount(e));
    }

    @Test
    @DisplayName("[P10.8] while another transaction holds the event's lock, a hold without ceilings answers 200 at once")
    void p10_8_holdWithoutCeilingsTakesNoLock() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var lockHolder = holdAdvisoryLock(executor, e, locked, release);
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            Callable<ResponseEntity<?>> plainHold = () -> hold(e, holdRequest("o8", ticketsOf(e.a(), 1), null));
            var answer = executor.submit(plainHold).get(1500, TimeUnit.MILLISECONDS);

            assertTrue(held(answer).created());
            release.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[P10.9] while the event's lock is held, a hold with ceilings answers 423 busy within 1,500 ms; another event still holds")
    void p10_9_holdWithCeilingsAnswersBusyOnALockTimeout() throws Exception {
        var other = newFixture();
        var executor = Executors.newFixedThreadPool(3);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var lockHolder = holdAdvisoryLock(executor, e, locked, release);
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            Callable<ResponseEntity<?>> blockedHold = () -> hold(e, holdRequest("o9", ticketsOf(e.a(), 1), rows(new QrushRowCeiling(e.a(), 5))));
            var answer = executor.submit(blockedHold).get(1500, TimeUnit.MILLISECONDS);

            assertEquals(423, answer.getStatusCode().value(), "body " + answer.getBody());
            assertEquals(new QrushHoldBusy(true), answer.getBody());
            assertEquals(0, reservationCount(e));

            Callable<ResponseEntity<?>> otherEventHold = () -> hold(other, holdRequest("o9", ticketsOf(other.a(), 1), rows(new QrushRowCeiling(other.a(), 5))));
            assertTrue(held(executor.submit(otherEventHold).get(10, TimeUnit.SECONDS)).created());

            release.countDown();
            lockHolder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("[P10.10] after P1 voids the hold, the same request makes a new hold")
    void p10_10_voidedHoldIsReplacedByANewOne() {
        var request = holdRequest("o1", ticketsOf(e.a(), 2), rows(new QrushRowCeiling(e.a(), 5)));
        var first = held(hold(e, request));
        var voided = qrushReservationController.refundVoid(e.slug(), first.reservationId(), null, e.key());
        assertEquals(200, voided.getStatusCode().value());
        assertTrue(Objects.requireNonNull(voided.getBody()).success());

        var again = held(hold(e, request));

        assertTrue(again.created());
        assertNotEquals(first.reservationId(), again.reservationId());
        assertEquals(List.of("o1:0", "o1:1"), extReferences(again.reservationId()));
    }

    @Test
    @DisplayName("[P10.11] a basket the engine refuses answers the upstream 400 unchanged: over the maximum with its error, not enough tickets empty")
    void p10_11_engineRefusalAnswersTheUpstream400() {
        var overMaximum = hold(e, holdRequest("o11", ticketsOf(e.a(), 11), null));

        assertEquals(400, overMaximum.getStatusCode().value());
        var body = assertInstanceOf(CreationResponse.class, overMaximum.getBody());
        assertNull(body.id());
        assertEquals(1, body.errors().size());
        assertEquals("invalid.reservation", body.errors().get(0).getCode());
        assertEquals("error.STEP_1_OVER_MAXIMUM", body.errors().get(0).getDescription());
        var upstream = upstreamController.createTicketsReservation(e.slug(),
            new TicketReservationCreationRequest(basket(ticketsOf(e.a(), 11)), List.of(), null, BUYER, null, "en", null, "o11"), e.key());
        assertEquals(400, upstream.getStatusCode().value());
        assertEquals(Json.toJson(upstream.getBody()), Json.toJson(body));
        assertEquals(0, reservationCount(e));

        upstreamHold(e, e.a(), 8);
        var notEnough = hold(e, holdRequest("o11b", ticketsOf(e.a(), 5), null));

        assertEquals(400, notEnough.getStatusCode().value());
        assertNull(notEnough.getBody());
        assertEquals(1, reservationCount(e));
    }

    @Test
    @DisplayName("[P10.12] ASSIGNMENT_REMINDER_START=0 at system level writes no reminder for a confirmed, never assigned hold (control: unset writes one)")
    void p10_12_reminderStartZeroWritesNoReminder() {
        setSystemConfiguration(ConfigurationKeys.ASSIGNMENT_REMINDER_START, "0");
        var hold = held(hold(e, holdRequest("o12", ticketsOf(e.a(), 1), null)));
        compConfirm(e, hold.reservationId());
        var subject = messageSourceManager.getMessageSourceFor(e.event())
            .getMessage("reminder.ticket-not-assigned.subject", new Object[]{e.event().getDisplayName()}, Locale.ENGLISH);
        var params = new MapSqlParameterSource("reservationId", hold.reservationId()).addValue("subject", subject);
        var countReminders = "select count(*) from email_message where reservation_id = :reservationId and subject = :subject";

        ticketReservationManager.sendReminderForTicketAssignment();

        assertEquals(0, jdbc.queryForObject(countReminders, params, Integer.class));

        removeSystemConfiguration(ConfigurationKeys.ASSIGNMENT_REMINDER_START);
        ticketReservationManager.sendReminderForTicketAssignment();

        assertEquals(1, jdbc.queryForObject(countReminders, params, Integer.class), "control: the default start writes the reminder");
    }

    @Test
    @DisplayName("[P10.14] two concurrent holds without ceilings for one order answer one created and one replay of the same reservation")
    void p10_14_concurrentHoldsForOneOrderMakeOneReservation() throws Exception {
        var request = holdRequest("o14", ticketsOf(e.a(), 2), null);
        var executor = Executors.newFixedThreadPool(2);
        try (var ignored = raisedLockTimeout()) {
            var start = new CountDownLatch(1);
            Callable<ResponseEntity<?>> sameOrder = () -> {
                start.await();
                return hold(e, request);
            };
            var first = executor.submit(sameOrder);
            var second = executor.submit(sameOrder);
            start.countDown();
            var answers = new ArrayList<QrushHold>();
            answers.add(held(first.get(30, TimeUnit.SECONDS)));
            answers.add(held(second.get(30, TimeUnit.SECONDS)));

            assertEquals(List.of(false, true), answers.stream().map(QrushHold::created).sorted().toList());
            assertEquals(answers.get(0).reservationId(), answers.get(1).reservationId());
            assertEquals(1, reservationCount(e));
            assertEquals(List.of("o14:0", "o14:1"), extReferences(answers.get(0).reservationId()));
        } finally {
            executor.shutdownNow();
        }
    }
}
