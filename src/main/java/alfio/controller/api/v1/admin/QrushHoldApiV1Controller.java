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

import alfio.model.api.v1.admin.AttendeesByCategory;
import alfio.model.api.v1.admin.ReservationUser;
import alfio.model.modification.AdditionalServiceReservationModification;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * qrush fork patch P10 — additive-only, see QRUSH-FORK.md.
 */
@RestController
@RequestMapping("/api/v1/admin/event")
public class QrushHoldApiV1Controller {

    @PostMapping("/{slug}/qrush-hold")
    public ResponseEntity<?> hold(@PathVariable String slug,
                                  @RequestBody QrushHoldRequest request,
                                  Principal principal) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
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
