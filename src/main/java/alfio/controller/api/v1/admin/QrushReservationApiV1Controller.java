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
import alfio.manager.AdminReservationManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.support.IncompatibleStateException;
import alfio.model.PurchaseContext.PurchaseContextType;
import alfio.model.TicketCategory;
import alfio.model.TicketReservation.TicketReservationStatus;
import alfio.repository.TicketCategoryRepository;
import alfio.repository.TicketRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * qrush wave-1 fork patch — additive-only, see QRUSH-FORK.md.
 * Auto-guarded by APITokenAuthWebSecurity: /api/v1/admin/** requires ROLE_API_CLIENT.
 * accessService.checkReservationOwnership is the org-isolation boundary
 * (AdminReservationManager does NOT re-check ownership).
 */
@RestController
@RequestMapping("/api/v1/admin/reservation")
public class QrushReservationApiV1Controller {

    private final AccessService accessService;
    private final TicketReservationManager ticketReservationManager;
    private final AdminReservationManager adminReservationManager;
    private final TicketRepository ticketRepository;
    private final TicketCategoryRepository ticketCategoryRepository;

    public QrushReservationApiV1Controller(AccessService accessService,
                                           TicketReservationManager ticketReservationManager,
                                           AdminReservationManager adminReservationManager,
                                           TicketRepository ticketRepository,
                                           TicketCategoryRepository ticketCategoryRepository) {
        this.accessService = accessService;
        this.ticketReservationManager = ticketReservationManager;
        this.adminReservationManager = adminReservationManager;
        this.ticketRepository = ticketRepository;
        this.ticketCategoryRepository = ticketCategoryRepository;
    }

    @GetMapping("/{eventSlug}/{reservationId}")
    public ResponseEntity<QrushReservationDetail> getReservation(@PathVariable String eventSlug,
                                                                 @PathVariable String reservationId,
                                                                 Principal principal) {
        accessService.checkReservationOwnership(principal, PurchaseContextType.event, eventSlug, reservationId);
        // guaranteed to exist by the check above
        var reservation = ticketReservationManager.findById(reservationId).orElseThrow();
        // one query for every category name in the reservation, not one per ticket
        Map<Integer, String> categoryNames = ticketCategoryRepository.findCategoriesInReservation(reservationId).stream()
            .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName, (a, b) -> a));
        var tickets = ticketRepository.findTicketsInReservation(reservationId).stream()
            .map(t -> new QrushTicketDetail(t.getPublicUuid().toString(), t.getCategoryId(), t.getStatus().name(),
                t.getId(), t.getUuid(), t.getAssigned(), t.isCheckedIn(), t.getFullName(),
                Objects.requireNonNullElse(categoryNames.get(t.getCategoryId()), "")))
            .toList();
        return ResponseEntity.ok(new QrushReservationDetail(reservationId, reservation.getStatus().name(), tickets));
    }

    @PostMapping("/{eventSlug}/{reservationId}/refund-void")
    public ResponseEntity<RefundVoidResponse> refundVoid(@PathVariable String eventSlug,
                                                         @PathVariable String reservationId,
                                                         @RequestBody(required = false) RefundVoidRequest request,
                                                         Principal principal) {
        accessService.checkReservationOwnership(principal, PurchaseContextType.event, eventSlug, reservationId);
        // idempotent short-circuit: the ownership guard above runs FIRST, so a foreign org key still
        // gets the same 403/404 for an already-cancelled reservation (no existence leak). Only the true
        // owner reaches here; an already-CANCELLED reservation was voided before, so skip the data-layer
        // void and report the prior outcome.
        var existing = ticketReservationManager.findById(reservationId).orElseThrow();
        if (existing.getStatus() == TicketReservationStatus.CANCELLED) {
            return ResponseEntity.ok(RefundVoidResponse.alreadyVoided("CANCELLED"));
        }
        List<String> requestedUuids = request == null || request.ticketUuids() == null
            ? List.of() : request.ticketUuids();
        if (requestedUuids.isEmpty()) {
            // full void: cancel reservation + release all tickets.
            // refund=false / notify=false / creditNoteRequested=false — money + email live on the qrush rail.
            var result = adminReservationManager.removeReservation(PurchaseContextType.event, eventSlug,
                reservationId, false, false, false, principal.getName());
            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(RefundVoidResponse.failure(result.getFormattedErrors()));
            }
            return ResponseEntity.ok(RefundVoidResponse.voided("CANCELLED"));
        }
        return partialVoid(eventSlug, reservationId, requestedUuids, principal);
    }

    private ResponseEntity<RefundVoidResponse> partialVoid(String eventSlug,
                                                           String reservationId,
                                                           List<String> requestedUuids,
                                                           Principal principal) {
        var ticketsByPublicUuid = ticketRepository.findTicketsInReservation(reservationId).stream()
            .collect(Collectors.toMap(t -> t.getPublicUuid().toString(), Function.identity()));
        if (!ticketsByPublicUuid.keySet().containsAll(requestedUuids)) {
            return ResponseEntity.badRequest()
                .body(RefundVoidResponse.failure("one or more ticketUuids do not belong to this reservation"));
        }
        var ticketIds = requestedUuids.stream().map(u -> ticketsByPublicUuid.get(u).getId()).toList();
        try {
            var result = adminReservationManager.removeTickets(eventSlug, reservationId, ticketIds,
                List.of(), false, false, principal.getName());
            if (!result.isSuccess()) {
                return ResponseEntity.badRequest().body(RefundVoidResponse.failure(result.getFormattedErrors()));
            }
        } catch (IncompatibleStateException e) { // checked-in ticket in the requested set
            return ResponseEntity.badRequest().body(RefundVoidResponse.failure(e.getMessage()));
        }
        var status = ticketReservationManager.findById(reservationId)
            .map(r -> r.getStatus().name())
            .orElse("CANCELLED");
        return ResponseEntity.ok(RefundVoidResponse.voided(status));
    }

    /** uuid is the internal check-in identifier: this answer stays org-key only, never forwarded to a client. */
    public record QrushTicketDetail(String publicUuid, int categoryId, String status,
                                    int id, String uuid, boolean assigned, boolean checkedIn,
                                    String fullName, String categoryName) {}

    public record QrushReservationDetail(String reservationId, String status, List<QrushTicketDetail> tickets) {}

    public record RefundVoidRequest(List<String> ticketUuids) {}

    public record RefundVoidResponse(boolean success, String reservationStatus, boolean alreadyVoided, String error) {
        static RefundVoidResponse voided(String reservationStatus) {
            return new RefundVoidResponse(true, reservationStatus, false, null);
        }
        static RefundVoidResponse alreadyVoided(String reservationStatus) {
            return new RefundVoidResponse(true, reservationStatus, true, null);
        }
        static RefundVoidResponse failure(String error) {
            return new RefundVoidResponse(false, null, false, error);
        }
    }
}
