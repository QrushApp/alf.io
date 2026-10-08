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

import alfio.controller.api.support.TicketHelper;
import alfio.controller.form.UpdateTicketOwnerForm;
import alfio.manager.AccessService;
import alfio.manager.EventManager;
import alfio.manager.PurchaseContextFieldManager;
import alfio.manager.TicketReservationManager;
import alfio.manager.support.response.ValidatedResponse;
import alfio.model.FullTicketInfo;
import alfio.model.PurchaseContextFieldValue;
import alfio.model.api.v1.admin.DownloadedAttendeeData;
import alfio.model.api.v1.admin.DownloadedAttendeesByCategory;
import alfio.repository.TicketRepository;
import alfio.util.ImageUtil;
import alfio.util.LocaleUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * qrush wave-2 fork patch — additive-only, see QRUSH-FORK.md.
 * Auto-guarded by APITokenAuthWebSecurity: /api/v1/admin/** requires ROLE_API_CLIENT.
 * accessService.checkEventOwnership is the org-isolation boundary.
 * Same payload as EventApiV1Controller#downloadAttendees (identical records, byte-compatible for
 * the qrush CF parser); the only difference is the status-agnostic additional-values lookup.
 * P4 qrush-ticket-signatures: sha256 of each assigned ticket's QR signature, the offline check-in key.
 * P5 assign, P6 reissue, P7 code.png: org-key ticket routes that replace the public bearer-uuid ones.
 */
@RestController
@RequestMapping("/api/v1/admin/event")
public class QrushEventAttendeesApiV1Controller {

    private static final int MAX_SIGNATURE_IDS = 200;
    // rotates both uuids of one reissuable (ACQUIRED or TO_BE_PAID) ticket; holder, status and reservation stay
    private static final String REISSUE_TICKET = "update ticket set uuid = :newUuid, public_uuid = :newPublicUuid"
        + " where id = :ticketId and event_id = :eventId and status in ('ACQUIRED', 'TO_BE_PAID')";

    private final AccessService accessService;
    private final EventManager eventManager;
    private final PurchaseContextFieldManager purchaseContextFieldManager;
    private final TicketRepository ticketRepository;
    private final TicketHelper ticketHelper;
    private final TicketReservationManager ticketReservationManager;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public QrushEventAttendeesApiV1Controller(AccessService accessService,
                                              EventManager eventManager,
                                              PurchaseContextFieldManager purchaseContextFieldManager,
                                              TicketRepository ticketRepository,
                                              TicketHelper ticketHelper,
                                              TicketReservationManager ticketReservationManager,
                                              NamedParameterJdbcTemplate jdbcTemplate) {
        this.accessService = accessService;
        this.eventManager = eventManager;
        this.purchaseContextFieldManager = purchaseContextFieldManager;
        this.ticketRepository = ticketRepository;
        this.ticketHelper = ticketHelper;
        this.ticketReservationManager = ticketReservationManager;
        this.jdbcTemplate = jdbcTemplate;
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

    @PutMapping("/{slug}/qrush-ticket/{publicUuid}/assign")
    public ResponseEntity<ValidatedResponse<Boolean>> assign(@PathVariable String slug,
                                                             @PathVariable UUID publicUuid,
                                                             @RequestBody UpdateTicketOwnerForm form,
                                                             Principal principal) {
        accessService.checkEventOwnership(principal, slug);
        var event = eventManager.getSingleEvent(slug, principal.getName());
        var bindingResult = new BeanPropertyBindingResult(form, "updateTicketOwner");
        var locale = LocaleUtil.forLanguageTag(form.getUserLanguage(), event);
        // TicketHelper goes through fetchComplete: same event, COMPLETE reservation, else empty
        return ticketHelper.assignTicket(slug, publicUuid, form, Optional.of(bindingResult), locale)
            .map(r -> ResponseEntity.status(r.getLeft().isSuccess() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ValidatedResponse<>(r.getLeft(), r.getLeft().isSuccess())))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{slug}/qrush-ticket/{publicUuid}/reissue")
    public ResponseEntity<QrushReissuedTicket> reissue(@PathVariable String slug,
                                                       @PathVariable UUID publicUuid,
                                                       Principal principal) {
        accessService.checkEventOwnership(principal, slug);
        // same event, COMPLETE reservation, else 404; the status guard sits in REISSUE_TICKET
        var complete = ticketReservationManager.fetchComplete(slug, publicUuid);
        if (complete.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var newPublicUuid = UUID.randomUUID();
        var params = new MapSqlParameterSource("ticketId", complete.get().getRight().getId())
            .addValue("eventId", complete.get().getLeft().getId())
            .addValue("newUuid", UUID.randomUUID().toString())
            .addValue("newPublicUuid", newPublicUuid);
        if (jdbcTemplate.update(REISSUE_TICKET, params) != 1) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(new QrushReissuedTicket(newPublicUuid.toString()));
    }

    @GetMapping("/{slug}/qrush-ticket/{publicUuid}/code.png")
    public ResponseEntity<byte[]> qrCode(@PathVariable String slug,
                                         @PathVariable UUID publicUuid,
                                         Principal principal) {
        accessService.checkEventOwnership(principal, slug);
        return ticketReservationManager.fetchCompleteAndAssigned(slug, publicUuid)
            .map(triple -> {
                var event = triple.getLeft();
                return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noStore())
                    .body(ImageUtil.createQRCode(triple.getRight().ticketCode(event.getPrivateKey(), event.supportsQRCodeCaseInsensitive())));
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record QrushTicketSignature(int id, String signatureHash) {}

    public record QrushReissuedTicket(String publicUuid) {}
}
