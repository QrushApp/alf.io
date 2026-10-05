# qrush fork policy

Fork of alfio-event/alf.io. Working branch: qrush/2.0-M5-2606, cut from tag 2.0-M5-2606.
Deployed image: ghcr.io/qrushapp/alfio:qrush-2.0-M5-2606 (built by .github/workflows/qrush-build.yml).

## Rules
1. ADDITIVE ONLY. Patches are new files (controllers under alfio.controller.api.v1.admin, tests,
   workflow, this doc). Never edit upstream files — no core managers, no security config, no SQL.
   Gate: `git diff --name-only 2.0-M5-2606..HEAD` lists fork-owned files only.
2. Pinned tag. The branch tracks tag 2.0-M5-2606 until a deliberate rebase. Never merge upstream
   main ad hoc.
3. Security model: new endpoints live under /api/v1/admin/** (auto hasRole(API_CLIENT) via
   APITokenAuthWebSecurity) and MUST call accessService.checkReservationOwnership /
   checkEventOwnership as their FIRST statement. AdminReservationManager does not re-check
   ownership — the controller guard is the entire boundary.
4. alf.io money is non-authoritative (topology B): fork endpoints always pass
   refund=false, notify=false, creditNoteRequested=false.

## Patch inventory
- P1 POST /api/v1/admin/reservation/{eventSlug}/{reservationId}/refund-void — full/partial void.
- P2 GET  /api/v1/admin/reservation/{eventSlug}/{reservationId} — status + per-ticket detail
  (numeric id, internal and public uuid, assigned, checked-in, holder name, category id and name).
  The internal uuid is the check-in identifier, so this endpoint stays org-key only and its answer
  is never forwarded to a client. Overlaps upstream ReservationApiV1Controller#retrieveDetail (exists at the tag); kept because
  retrieveDetail hides ticket resources for PENDING tickets and buries UUIDs in URI templates.
  Re-evaluate replacement at every rebase gate.
- P3 GET  /api/v1/admin/event/{slug}/qrush-attendees (QrushEventAttendeesApiV1Controller, wave 2) —
  byte-compatible twin of upstream download-attendees whose additional-field values lookup is
  status-agnostic (findAllValuesByTicketIds), so CHECKED_IN/TO_BE_PAID tickets keep their
  Ticketnummer where upstream fetches ACQUIRED-only. Plain read: the refund=false/notify=false
  rule above applies to the reservation-action endpoints, not here.
- Further wave-2 candidates: see share/Fable/ticket/wave-2-backlog.md in the qrush repo.
- `src/test/resources/api/descriptor.json` — REGENERATED (the one non-additive change; upstream's
  own sanctioned mechanism): `TestCheckRestApiStability` diffs the REST surface against this
  snapshot, so adding P1/P2 requires regenerating it (flip `updateDescriptor=true` in the test,
  run `./gradlew test --tests '*CheckRestApiStability*' -Dpgsql.version=16`, flip back, commit the
  json). Re-regenerate after EVERY new fork endpoint and at every rebase gate. The regen rewrites
  the whole file (serialization churn) — the comparator is semantic, only real API diffs fail CI.
- `src/test/java/alfio/BaseTestConfiguration.java` — stripe-mock pinned to `v0.202.0` (test infra
  only): `latest` moved to v0.203.0 on 2026-08-26 and rejects stripe-java 25.5.0's requests, which
  failed four upstream Stripe integration tests in CI on 2026-10-05. Re-check the pin at every rebase gate.

## Rebase gate (next: 2.0-M6)
1. pg_dump the production DB (Flyway is forward-only) — qrush_tickets runbook.
2. New branch qrush/<new-tag> from the new tag; cherry-pick fork-owned files (additive => trivial).
3. MANDATORY: full run of QrushReservationApiV1ControllerTest AND
   QrushEventAttendeesApiV1ControllerTest — the cross-org tests
   (crossOrgKeyCannotRead/Void*, unknownSlug*, crossOrgKeyCannotReadForeignAttendees) must pass
   unmodified. A guard-signature change upstream fails compilation loudly; a behavior change
   fails these tests. Never relax them.
4. Re-check the P2 overlap decision, CI pgsql matrix version, and Dockerfile drift.
   Also EDIT `.github/workflows/qrush-build.yml` image tags: they are hardcoded to
   `qrush-2.0-M5-2606`, so a `qrush/2.0-M6` branch would silently republish the -2606
   tags unless you bump them to `qrush-<new-tag>` here.
5. Build + push the new image tag; qrush_tickets compose bump is a separate, deliberate deploy.

## Upstream PR strategy
- refund-void (P1) is the upstreamable patch: the org-key surface has no headless cancel/void at
  the tag (all refund/cancel endpoints are admin-session). Offer it upstream after the pilot
  proves the API shape; carrying it in-tree is cheap either way.
- P2 read: NOT offered upstream (near-duplicate of retrieveDetail); private convenience only.
