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
import alfio.controller.api.v1.admin.QrushEventAttendeesApiV1Controller;
import alfio.controller.api.v1.admin.ReservationApiV1Controller;
import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.manager.EventManager;
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
import alfio.repository.system.ConfigurationRepository;
import alfio.repository.user.AuthorityRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.test.util.AlfioIntegrationTest;
import alfio.test.util.IntegrationTestUtil;
import alfio.util.ClockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static alfio.test.util.IntegrationTestUtil.AVAILABLE_SEATS;
import static alfio.test.util.IntegrationTestUtil.DESCRIPTION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P9: the org-key assign queues no engine mail, neither for a first holder nor for a transfer.
 * SEND_TICKETS_AUTOMATICALLY=false at system level, as on the boxes; the tickets are confirmed the
 * production way (ADMIN comp confirm without notifications), so they are ACQUIRED.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class QrushAssignMailTest {

    @Autowired private ConfigurationRepository configurationRepository;
    @Autowired private ClockProvider clockProvider;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserManager userManager;
    @Autowired private EventManager eventManager;
    @Autowired private EventRepository eventRepository;
    @Autowired private TicketCategoryRepository ticketCategoryRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private ReservationApiV1Controller upstreamReservationController;
    @Autowired private QrushEventAttendeesApiV1Controller controller;
    @Autowired private AuthorityRepository authorityRepository;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private Event event;
    private APITokenAuthentication orgKey;

    @BeforeEach
    void setUp() {
        IntegrationTestUtil.ensureMinimalConfiguration(configurationRepository);
        setSystemConfiguration(ConfigurationKeys.SEND_TICKETS_AUTOMATICALLY, "false");
        event = IntegrationTestUtil.initEvent(List.of(new TicketCategoryModification(null, "default", TicketCategory.TicketAccessType.INHERIT, AVAILABLE_SEATS,
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).minusDays(1), LocalTime.now(clockProvider.getClock())),
                new DateTimeModification(LocalDate.now(clockProvider.getClock()).plusDays(1), LocalTime.now(clockProvider.getClock())),
                DESCRIPTION, BigDecimal.TEN, false, "", false, null, null, null, null, null, 0, null, null, AlfioMetadata.empty())),
            organizationRepository, userManager, eventManager, eventRepository).getLeft();
        var username = UUID.randomUUID().toString();
        userManager.insertUser(event.getOrganizationId(), username, "test", "test",
            "test@example.com", Role.API_CONSUMER, User.Type.INTERNAL, null);
        var authorities = authorityRepository.findRoles(username).stream().map(SimpleGrantedAuthority::new).toList();
        orgKey = new APITokenAuthentication(username, null, authorities);
    }

    /** A system-level configuration row as this test found it; absent when no row existed. */
    private record FoundSetting(String value, String description) {}

    // the database outlives this class: every system setting written here is put back after each test
    private final Map<String, Optional<FoundSetting>> foundSettings = new LinkedHashMap<>();

    private void setSystemConfiguration(ConfigurationKeys key, String value) {
        foundSettings.computeIfAbsent(key.getValue(), k -> jdbc.query(
            "select c_value, description from configuration where c_key = :key", new MapSqlParameterSource("key", k),
            (rs, row) -> new FoundSetting(rs.getString("c_value"), rs.getString("description"))).stream().findFirst());
        configurationRepository.deleteByKey(key.getValue());
        configurationRepository.insert(key.getValue(), value, "");
    }

    @AfterEach
    void restoreSystemConfiguration() {
        foundSettings.forEach((key, found) -> {
            configurationRepository.deleteByKey(key);
            found.ifPresent(f -> configurationRepository.insert(key, f.value(), f.description()));
        });
        foundSettings.clear();
    }

    private static UpdateTicketOwnerForm holder(String firstName, String lastName, String email) {
        var form = new UpdateTicketOwnerForm();
        form.setFirstName(firstName);
        form.setLastName(lastName);
        form.setFullName(firstName + " " + lastName);
        form.setEmail(email);
        form.setUserLanguage("en");
        form.setAdditional(new HashMap<>());
        return form;
    }

    /** A bare quantity plus a buyer, then the ADMIN comp confirm without notifications: one ACQUIRED ticket. */
    private Ticket createCompConfirmedTicket() {
        var category = ticketCategoryRepository.findFirstWithAvailableTickets(event.getId()).orElseThrow();
        var buyer = new ReservationUser(null, "Buyer", "McBuyer", "buyer@example.org", null);
        var creationRequest = new TicketReservationCreationRequest(
            List.of(new AttendeesByCategory(category.getId(), 1, List.of(), null)),
            List.of(), null, buyer, null, "en", null, null);
        var created = upstreamReservationController.createTicketsReservation(event.getShortName(), creationRequest, orgKey);
        assertTrue(created.getStatusCode().is2xxSuccessful());
        var reservationId = Objects.requireNonNull(Objects.requireNonNull(created.getBody()).id());
        var confirmation = new ReservationConfirmationRequest(
            new TransactionDetails("qrush-comp", BigDecimal.ZERO,
                LocalDateTime.now(clockProvider.getClock()), "qrush comp ($0) finalize", PaymentProxy.ADMIN),
            new Notification(false, false), null);
        assertTrue(upstreamReservationController.confirmReservation(reservationId, confirmation, orgKey)
            .getStatusCode().is2xxSuccessful());
        var tickets = ticketRepository.findTicketsInReservation(reservationId);
        assertEquals(1, tickets.size());
        assertEquals(Ticket.TicketStatus.ACQUIRED, tickets.get(0).getStatus());
        return tickets.get(0);
    }

    /** The email_message rows as they stand: the highest id and the ids that wait to be sent. */
    private record MailSnapshot(int maxId, Set<Integer> waiting) {}

    private MailSnapshot mailSnapshot() {
        var maxId = Objects.requireNonNull(jdbc.queryForObject("select coalesce(max(id), 0) from email_message",
            new MapSqlParameterSource(), Integer.class));
        var waiting = new TreeSet<>(jdbc.queryForList("select id from email_message where status = 'WAITING'",
            new MapSqlParameterSource(), Integer.class));
        return new MailSnapshot(maxId, waiting);
    }

    /** No row above the baseline and no row below it flipped back to WAITING. */
    private void assertNoMailSince(MailSnapshot baseline, String step) {
        var newRows = jdbc.queryForList("select subject from email_message where id > :baseline",
            new MapSqlParameterSource("baseline", baseline.maxId()), String.class);
        assertEquals(List.of(), newRows, step + ": the engine queued mail");
        var waitingNow = new TreeSet<>(jdbc.queryForList("select id from email_message where status = 'WAITING' and id <= :baseline",
            new MapSqlParameterSource("baseline", baseline.maxId()), Integer.class));
        assertEquals(baseline.waiting(), waitingNow, step + ": an existing mail was queued again");
    }

    private void assign(Ticket ticket, UpdateTicketOwnerForm form) {
        assertEquals(200, controller.assign(event.getShortName(), ticket.getPublicUuid(), form, orgKey).getStatusCode().value());
    }

    @Test
    @DisplayName("[P9.5] a first assign and a re-assign to another holder queue no engine mail")
    void p9_5_assignAndReassignQueueNoMail() {
        var ticket = createCompConfirmedTicket();
        var baseline = mailSnapshot();

        assign(ticket, holder("Rita", "Recipient", "rita@example.org"));
        assertNoMailSince(baseline, "first assign");

        assign(ticket, holder("Max", "Mustermann", "max@example.org"));
        assertNoMailSince(baseline, "re-assign");
    }

    @Test
    @DisplayName("[P9.R1] a re-assign after the event began queues neither the owner-changed mail nor the organizer warning")
    void p9_r1_reassignAfterEventStartQueuesNoOrganizerWarning() {
        var ticket = createCompConfirmedTicket();
        assign(ticket, holder("Rita", "Recipient", "rita@example.org"));
        jdbc.update("update event set start_ts = :begin where id = :eventId",
            new MapSqlParameterSource("begin", ZonedDateTime.now(clockProvider.getClock()).minusHours(1).toOffsetDateTime())
                .addValue("eventId", event.getId()));
        assertTrue(eventRepository.findById(event.getId()).getBegin().isBefore(ZonedDateTime.now(clockProvider.getClock())));
        var baseline = mailSnapshot();

        assign(ticket, holder("Max", "Mustermann", "max@example.org"));

        assertNoMailSince(baseline, "re-assign after the event began");
    }
}
