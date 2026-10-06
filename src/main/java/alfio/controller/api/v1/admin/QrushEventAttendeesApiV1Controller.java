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
import alfio.manager.PurchaseContextFieldManager;
import alfio.model.FullTicketInfo;
import alfio.model.PurchaseContextFieldValue;
import alfio.model.api.v1.admin.DownloadedAttendeeData;
import alfio.model.api.v1.admin.DownloadedAttendeesByCategory;
import alfio.repository.TicketRepository;
import org.apache.commons.codec.digest.DigestUtils;
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
import java.util.stream.Collectors;

/**
 * qrush wave-2 fork patch — additive-only, see QRUSH-FORK.md.
 * Auto-guarded by APITokenAuthWebSecurity: /api/v1/admin/** requires ROLE_API_CLIENT.
 * accessService.checkEventOwnership is the org-isolation boundary.
 * Same payload as EventApiV1Controller#downloadAttendees (identical records, byte-compatible for
 * the qrush CF parser); the only difference is the status-agnostic additional-values lookup.
 * P4 qrush-ticket-signatures: sha256 of each assigned ticket's QR signature, the offline check-in key.
 */
@RestController
@RequestMapping("/api/v1/admin/event")
public class QrushEventAttendeesApiV1Controller {

    private static final int MAX_SIGNATURE_IDS = 200;

    private final AccessService accessService;
    private final EventManager eventManager;
    private final PurchaseContextFieldManager purchaseContextFieldManager;
    private final TicketRepository ticketRepository;

    public QrushEventAttendeesApiV1Controller(AccessService accessService,
                                              EventManager eventManager,
                                              PurchaseContextFieldManager purchaseContextFieldManager,
                                              TicketRepository ticketRepository) {
        this.accessService = accessService;
        this.eventManager = eventManager;
        this.purchaseContextFieldManager = purchaseContextFieldManager;
        this.ticketRepository = ticketRepository;
    }

    @GetMapping("/{slug}/qrush-attendees")
    public ResponseEntity<List<DownloadedAttendeesByCategory>> qrushAttendees(@PathVariable String slug, Principal user) {
        accessService.checkEventOwnership(user, slug);
        var event = eventManager.getSingleEvent(slug, user.getName());
        var ticketCategories = eventManager.loadTicketCategories(event);
        var ticketsByCategoryId = eventManager.findAllConfirmedTicketsForCSV(slug, user.getName()).stream()
            .filter(t -> t.getTicket().getCategoryId() != null && t.getTicket().getAssigned())
            .collect(Collectors.groupingBy(t -> t.getTicket().getCategoryId()));
        var ticketIds = ticketsByCategoryId.values().stream()
            .flatMap(List::stream)
            .map(t -> t.getTicket().getId())
            .toList();
        // status-agnostic lookup over the rows we actually return: upstream fetches values for
        // ACQUIRED tickets only, so CHECKED_IN/TO_BE_PAID tickets lose their additional fields.
        var valuesByTicketId = ticketIds.isEmpty()
            ? Map.<Integer, List<PurchaseContextFieldValue>>of()
            : purchaseContextFieldManager.findAllValuesByTicketIds(ticketIds);
        return ResponseEntity.ok(ticketCategories.stream().filter(category -> ticketsByCategoryId.containsKey(category.getId()))
            .map(category -> {
                var ticketsInCategory = ticketsByCategoryId.get(category.getId());
                var downloadedAttendeesData = ticketsInCategory.stream()
                    .map(t -> {
                        var ticket = t.getTicket();
                        var additional = valuesByTicketId.getOrDefault(ticket.getId(), List.of()).stream()
                            .collect(Collectors.groupingBy(PurchaseContextFieldValue::getName, Collectors.mapping(PurchaseContextFieldValue::getValue, Collectors.toList())));
                        return new DownloadedAttendeeData(ticket.getFirstName(),
                            ticket.getLastName(),
                            ticket.getEmail(),
                            Map.of(),
                            additional,
                            ticket.getExtReference(),
                            ticket.getStatus(),
                            t.getTicketReservation().getConfirmationTimestamp());
                    })
                    .toList();
                return new DownloadedAttendeesByCategory(category.getId(), downloadedAttendeesData);
            }).toList());
    }

    @PostMapping("/{slug}/qrush-ticket-signatures")
    public ResponseEntity<List<QrushTicketSignature>> qrushTicketSignatures(@PathVariable String slug,
                                                                            @RequestBody List<Integer> ids,
                                                                            Principal user) {
        accessService.checkEventOwnership(user, slug);
        if (ids.size() > MAX_SIGNATURE_IDS) {
            return ResponseEntity.badRequest().build();
        }
        if (ids.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        var event = eventManager.getSingleEvent(slug, user.getName());
        // event-scoped and ordered by id; only tickets with a holder carry a QR signature
        return ResponseEntity.ok(ticketRepository.findAllFullTicketInfoAssignedByEventId(event.getId(), ids).stream()
            .filter(FullTicketInfo::getAssigned)
            .map(t -> new QrushTicketSignature(t.getId(),
                DigestUtils.sha256Hex(t.hmacTicketInfo(event.getPrivateKey(), event.supportsQRCodeCaseInsensitive()))))
            .toList());
    }

    public record QrushTicketSignature(int id, String signatureHash) {}
}
