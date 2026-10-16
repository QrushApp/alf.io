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
package alfio.controller.api.v1.admin;

import alfio.manager.AccessService;
import alfio.manager.EventManager;
import alfio.manager.TicketReservationManager;
import alfio.model.Ticket;
import alfio.model.TicketCategory;
import alfio.model.api.v1.admin.AttendeesByCategory;
import alfio.model.api.v1.admin.ReservationUser;
import alfio.model.api.v1.admin.TicketReservationCreationRequest;
import alfio.model.modification.AdditionalServiceReservationModification;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * qrush fork patch P10 — additive-only, see QRUSH-FORK.md.
 * Auto-guarded by APITokenAuthWebSecurity: /api/v1/admin/** requires ROLE_API_CLIENT.
 * The hold of one qrush order: idempotent on ext_reference "<orderId>:0", the qrush ceilings counted
 * under one advisory lock per event, the reservation made by the upstream create in the same transaction.
 */
@RestController
@RequestMapping("/api/v1/admin/event")
public class QrushHoldApiV1Controller {

    // "QHLD": the advisory-lock namespace; the event id is the key
    private static final int HOLD_LOCK_NAMESPACE = 0x51484C44;
    private static final String LOCK_TIMEOUT_PROPERTY = "qrush.hold.lock-timeout-ms";
    private static final int DEFAULT_LOCK_TIMEOUT_MS = 500;
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String TICKET_REF_UNIQUE = "ticket_ref_unique";
    private static final String ADVISORY_LOCK = "select pg_advisory_xact_lock(:namespace, :eventId)";
    private static final String FIND_HOLD = "select tickets_reservation_id from ticket"
        + " where event_id = :eventId and ext_reference = :reference and tickets_reservation_id is not null";
    private static final String TICKETS_BY_ID = "select id from ticket where tickets_reservation_id = :reservationId order by id";
    private static final String SET_REFERENCE = "update ticket set ext_reference = :reference"
        + " where id = :ticketId and tickets_reservation_id = :reservationId";
    private static final String REFUSED_ROW = "row";
    private static final String REFUSED_GROUP = "group";
    private static final String REFUSED_CAPACITY = "capacity";

    private final AccessService accessService;
    private final EventManager eventManager;
    private final ReservationApiV1Controller reservationApiV1Controller;
    private final TicketReservationManager ticketReservationManager;
    private final TicketRepository ticketRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Environment environment;

    public QrushHoldApiV1Controller(AccessService accessService,
                                    EventManager eventManager,
                                    ReservationApiV1Controller reservationApiV1Controller,
                                    TicketReservationManager ticketReservationManager,
                                    TicketRepository ticketRepository,
                                    TicketCategoryRepository ticketCategoryRepository,
                                    NamedParameterJdbcTemplate jdbcTemplate,
                                    PlatformTransactionManager transactionManager,
                                    Environment environment) {
        this.accessService = accessService;
        this.eventManager = eventManager;
        this.reservationApiV1Controller = reservationApiV1Controller;
        this.ticketReservationManager = ticketReservationManager;
        this.ticketRepository = ticketRepository;
        this.ticketCategoryRepository = ticketCategoryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.environment = environment;
    }

    @PostMapping("/{slug}/qrush-hold")
    public ResponseEntity<?> hold(@PathVariable String slug,
                                  @RequestBody QrushHoldRequest request,
                                  Principal principal) {
        var creationRequest = new TicketReservationCreationRequest(request.tickets(), request.additionalServices(),
            null, request.user(), null, request.language(), null, request.externalReference());
        // ownership first: refuses another organization's event and another event's categories
        accessService.checkEventReservationCreationRequest(principal, slug, creationRequest);
        if (StringUtils.isBlank(request.externalReference())) {
            return ResponseEntity.badRequest().build();
        }
        int eventId = eventManager.getEventAndOrganizationId(slug, principal.getName()).getId();
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                var response = holdInTransaction(eventId, slug, request, creationRequest, principal);
                if (!response.getStatusCode().is2xxSuccessful()) {
                    // our own rollback: a refusal inside the joined upstream transaction stays a plain answer
                    status.setRollbackOnly();
                }
                return response;
            }));
        } catch (RuntimeException e) {
            var cause = sqlCause(e);
            if (cause.isPresent() && LOCK_NOT_AVAILABLE.equals(cause.get().getSQLState())) {
                return busy();
            }
            if (cause.isPresent() && UNIQUE_VIOLATION.equals(cause.get().getSQLState())
                && StringUtils.contains(cause.get().getMessage(), TICKET_REF_UNIQUE)) {
                // a concurrent hold for the same order committed first: answer that one
                return Objects.requireNonNull(transactionTemplate.execute(status -> findHold(eventId, request.externalReference())
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(QrushHoldApiV1Controller::busy)));
            }
            throw e;
        }
    }

    private ResponseEntity<?> holdInTransaction(int eventId,
                                                String slug,
                                                QrushHoldRequest request,
                                                TicketReservationCreationRequest creationRequest,
                                                Principal principal) {
        // covers the advisory lock and every row lock after it: a longer wait answers busy
        int lockTimeoutMs = environment.getProperty(LOCK_TIMEOUT_PROPERTY, Integer.class, DEFAULT_LOCK_TIMEOUT_MS);
        jdbcTemplate.getJdbcTemplate().execute("set local lock_timeout = '" + lockTimeoutMs + "ms'");
        var ceilings = request.ceilings();
        if (ceilings != null) {
            jdbcTemplate.query(ADVISORY_LOCK, new MapSqlParameterSource("namespace", HOLD_LOCK_NAMESPACE)
                .addValue("eventId", eventId), (ResultSetExtractor<Void>) rs -> null);
        }
        var existing = findHold(eventId, request.externalReference());
        if (existing.isPresent()) {
            return ResponseEntity.ok(existing.get());
        }
        QrushHoldRemaining remaining = null;
        if (ceilings != null) {
            var check = checkCeilings(eventId, requestedByCategory(request), ceilings);
            if (check.refused() != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(new QrushHoldRefused(check.refused(), check.remaining()));
            }
            remaining = check.remaining();
        }
        var created = reservationApiV1Controller.createTicketsReservation(slug, creationRequest, principal);
        if (!created.getStatusCode().is2xxSuccessful()) {
            return created;
        }
        var reservationId = Objects.requireNonNull(created.getBody()).id();
        // index i follows the ticket id: the engine's own order (category, uuid) is random
        var ticketIds = jdbcTemplate.queryForList(TICKETS_BY_ID, new MapSqlParameterSource("reservationId", reservationId), Integer.class);
        for (int i = 0; i < ticketIds.size(); i++) {
            jdbcTemplate.update(SET_REFERENCE, new MapSqlParameterSource("reference", reference(request.externalReference(), i))
                .addValue("ticketId", ticketIds.get(i))
                .addValue("reservationId", reservationId));
        }
        return ResponseEntity.ok(describe(reservationId, true, remaining));
    }

    private Optional<QrushHold> findHold(int eventId, String orderId) {
        return jdbcTemplate.queryForList(FIND_HOLD, new MapSqlParameterSource("eventId", eventId)
                .addValue("reference", reference(orderId, 0)), String.class).stream()
            .findFirst()
            .map(reservationId -> describe(reservationId, false, null));
    }

    private QrushHold describe(String reservationId, boolean created, QrushHoldRemaining remaining) {
        var reservation = ticketReservationManager.findById(reservationId).orElseThrow();
        var tickets = ticketRepository.findTicketsInReservation(reservationId).stream()
            .sorted(Comparator.comparingInt(Ticket::getId))
            .map(t -> new QrushHeldTicket(t.getId(), t.getPublicUuid().toString(), t.getCategoryId()))
            .toList();
        return new QrushHold(reservationId, reservation.getValidity().getTime(), created, tickets, remaining);
    }

    private static String reference(String orderId, int index) {
        return orderId + ":" + index;
    }

    private static ResponseEntity<?> busy() {
        return ResponseEntity.status(HttpStatus.LOCKED).body(new QrushHoldBusy(true));
    }

    private static Optional<SQLException> sqlCause(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return Optional.of(sql);
            }
        }
        return Optional.empty();
    }

    private static Map<Integer, Integer> requestedByCategory(QrushHoldRequest request) {
        Map<Integer, Integer> requested = new HashMap<>();
        Objects.requireNonNullElse(request.tickets(), List.<AttendeesByCategory>of()).stream()
            .filter(t -> t != null && t.getTicketCategoryId() != null && t.getQuantity() != null)
            .forEach(t -> requested.merge(t.getTicketCategoryId(), t.getQuantity(), Integer::sum));
        return requested;
    }

    private record CeilingCheck(String refused, QrushHoldRemaining remaining) {}

    /** Rows first, then groups, then capacity; remaining is counted before the hold on a refusal, after it on success. */
    private CeilingCheck checkCeilings(int eventId, Map<Integer, Integer> requested, QrushHoldCeilings ceilings) {
        var rows = Objects.requireNonNullElse(ceilings.rows(), List.<QrushRowCeiling>of());
        var groups = Objects.requireNonNullElse(ceilings.groups(), List.<QrushGroupCeiling>of());
        var counts = new CategoryCounts(eventId, requested);

        String refused = null;
        if (rows.stream().anyMatch(r -> counts.held(r.categoryId()) + counts.wanted(r.categoryId()) > r.max())) {
            refused = REFUSED_ROW;
        } else if (groups.stream().anyMatch(g -> counts.held(g.categoryIds()) + counts.wanted(g.categoryIds()) > g.max())) {
            refused = REFUSED_GROUP;
        } else if (ceilings.capacity() != null && counts.heldInEvent() + counts.wantedInTotal() > ceilings.capacity()) {
            refused = REFUSED_CAPACITY;
        }

        boolean afterHold = refused == null;
        var rowsRemaining = rows.stream()
            .map(r -> new QrushRowRemaining(r.categoryId(),
                nonNegative(r.max() - counts.held(r.categoryId()) - (afterHold ? counts.wanted(r.categoryId()) : 0))))
            .toList();
        var groupsRemaining = groups.stream()
            .map(g -> new QrushGroupRemaining(g.categoryIds(),
                nonNegative(g.max() - counts.held(g.categoryIds()) - (afterHold ? counts.wanted(g.categoryIds()) : 0))))
            .toList();
        Integer capacityRemaining = ceilings.capacity() == null ? null
            : nonNegative(ceilings.capacity() - counts.heldInEvent() - (afterHold ? counts.wantedInTotal() : 0));
        return new CeilingCheck(refused, new QrushHoldRemaining(capacityRemaining, rowsRemaining, groupsRemaining));
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }

    /** Confirmed and pending tickets per category, read once each under the lock, plus the requested basket. */
    private final class CategoryCounts {
        private final int eventId;
        private final Map<Integer, Integer> requested;
        private final Map<Integer, Integer> held = new HashMap<>();
        private Integer heldInEvent;

        private CategoryCounts(int eventId, Map<Integer, Integer> requested) {
            this.eventId = eventId;
            this.requested = requested;
        }

        int held(int categoryId) {
            return held.computeIfAbsent(categoryId, id -> ticketRepository.countConfirmedAndPendingTickets(eventId, id));
        }

        int held(List<Integer> categoryIds) {
            return distinct(categoryIds).stream().mapToInt(this::held).sum();
        }

        int heldInEvent() {
            if (heldInEvent == null) {
                heldInEvent = ticketCategoryRepository.findAllTicketCategories(eventId).stream()
                    .map(TicketCategory::getId)
                    .mapToInt(this::held)
                    .sum();
            }
            return heldInEvent;
        }

        int wanted(int categoryId) {
            return requested.getOrDefault(categoryId, 0);
        }

        int wanted(List<Integer> categoryIds) {
            return distinct(categoryIds).stream().mapToInt(this::wanted).sum();
        }

        int wantedInTotal() {
            return requested.values().stream().mapToInt(Integer::intValue).sum();
        }

        private static List<Integer> distinct(List<Integer> categoryIds) {
            return Objects.requireNonNullElse(categoryIds, List.<Integer>of()).stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        }
    }

    public record QrushHoldRequest(List<AttendeesByCategory> tickets,
                                   List<AdditionalServiceReservationModification> additionalServices,
                                   String language,
                                   ReservationUser user,
                                   String externalReference,
                                   QrushHoldCeilings ceilings) {}

    public record QrushHoldCeilings(Integer capacity, List<QrushRowCeiling> rows, List<QrushGroupCeiling> groups) {}

    public record QrushRowCeiling(int categoryId, int max) {}

    public record QrushGroupCeiling(List<Integer> categoryIds, int max) {}

    public record QrushHold(String reservationId, long validity, boolean created, List<QrushHeldTicket> tickets,
                            @JsonInclude(JsonInclude.Include.NON_NULL) QrushHoldRemaining remaining) {}

    public record QrushHeldTicket(int id, String publicUuid, int categoryId) {}

    public record QrushHoldRemaining(@JsonInclude(JsonInclude.Include.NON_NULL) Integer capacity,
                                     List<QrushRowRemaining> rows,
                                     List<QrushGroupRemaining> groups) {}

    public record QrushRowRemaining(int categoryId, int remaining) {}

    public record QrushGroupRemaining(List<Integer> categoryIds, int remaining) {}

    public record QrushHoldRefused(String refused, QrushHoldRemaining remaining) {}

    public record QrushHoldBusy(boolean busy) {}
}
