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
package alfio.config;

import alfio.config.authentication.support.APITokenAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8: the public ticket-assign PUT needs an authenticated org API key. Plain servlet mocks, no
 * Spring context: every line builds the registration, runs one request through its filter and
 * reads what the guard wrote and whether the chain behind it was reached.
 */
class QrushPublicTicketAssignGuardTest {

    private static final String U = "c0ffee00-0000-4000-8000-000000000001";
    private static final String ASSIGN_PATH = "/api/v2/public/event/party-1/ticket/" + U;

    /** What one request did: the answer the guard wrote and the chain it did or did not reach. */
    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {}

    @BeforeEach
    void startWithEmptyContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Outcome pass(MockHttpServletRequest request) throws Exception {
        var registration = new QrushPublicTicketAssignGuard().qrushPublicTicketAssignGuard();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        registration.getFilter().doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private static Outcome pass(String method, String uri) throws Exception {
        return pass(new MockHttpServletRequest(method, uri));
    }

    private static void assertRefused(Outcome outcome) {
        assertEquals(403, outcome.response().getStatus());
        assertNull(outcome.chain().getRequest(), "the chain behind the guard must not be invoked");
    }

    /** MockFilterChain throws on a second call, so a recorded request means it ran exactly once. */
    private static void assertChainInvokedOnce(Outcome outcome) {
        assertNotNull(outcome.chain().getRequest(), "the chain behind the guard must be invoked once");
    }

    @Test
    @DisplayName("[T22.101] public ticket-assign PUT without any authentication is refused with 403")
    void t22_101_putWithEmptyContextIsRefused() throws Exception {
        var outcome = pass("PUT", ASSIGN_PATH);

        assertRefused(outcome);
    }

    @Test
    @DisplayName("[T22.102] public ticket-assign PUT as the anonymous user is refused with 403")
    void t22_102_putAsAnonymousIsRefused() throws Exception {
        authenticate(new AnonymousAuthenticationToken("anonymous-key", "anonymousUser",
            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        var outcome = pass("PUT", ASSIGN_PATH);

        assertRefused(outcome);
    }

    @Test
    @DisplayName("[T22.103] public ticket-assign PUT as a logged-in admin session is refused with 403")
    void t22_103_putAsSessionAdminIsRefused() throws Exception {
        authenticate(new UsernamePasswordAuthenticationToken("admin", null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        var outcome = pass("PUT", ASSIGN_PATH);

        assertRefused(outcome);
    }

    @Test
    @DisplayName("[T22.104] public ticket-assign PUT with a system API key is refused with 403")
    void t22_104_putAsSystemApiKeyIsRefused() throws Exception {
        authenticate(new APITokenAuthentication("sys-key", null,
            List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_API_CLIENT"))));

        var outcome = pass("PUT", ASSIGN_PATH);

        assertRefused(outcome);
    }

    @Test
    @DisplayName("[T22.105] (pin) public ticket-assign PUT with an org API key passes through untouched")
    void t22_105_putAsOrgApiKeyPassesThrough() throws Exception {
        authenticate(new APITokenAuthentication("org-key", null,
            List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));

        var outcome = pass("PUT", ASSIGN_PATH);

        assertChainInvokedOnce(outcome);
        assertEquals(200, outcome.response().getStatus());
    }

    @Test
    @DisplayName("[T22.106] an Authorization apikey header alone, with no authenticated principal, is refused with 403")
    void t22_106_forgedApikeyHeaderWithoutPrincipalIsRefused() throws Exception {
        var request = new MockHttpServletRequest("PUT", ASSIGN_PATH);
        request.addHeader("Authorization", "apikey forged-key");

        var outcome = pass(request);

        assertRefused(outcome);
    }

    @Test
    @DisplayName("[T22.107] public ticket-assign PUT with a trailing slash is refused with 403")
    void t22_107_putWithTrailingSlashIsRefused() throws Exception {
        var outcome = pass("PUT", ASSIGN_PATH + "/");

        assertRefused(outcome);
    }

    @Test
    @DisplayName("[T22.108] (pin) GET of the public code.png passes through")
    void t22_108_getCodePngPassesThrough() throws Exception {
        var outcome = pass("GET", ASSIGN_PATH + "/code.png");

        assertChainInvokedOnce(outcome);
    }

    @Test
    @DisplayName("[T22.109] (pin) GET of the public ticket info passes through")
    void t22_109_getTicketInfoPassesThrough() throws Exception {
        var outcome = pass("GET", ASSIGN_PATH);

        assertChainInvokedOnce(outcome);
    }

    @Test
    @DisplayName("[T22.110] (pin) POST of the admin reissue route passes through, the API-token chain decides")
    void t22_110_postAdminReissuePassesThrough() throws Exception {
        var outcome = pass("POST", "/api/v1/admin/event/party-1/qrush-ticket/" + U + "/reissue");

        assertChainInvokedOnce(outcome);
    }

    @Test
    @DisplayName("[T22.111] the guard is ordered after Spring Security so the principal is already set")
    void t22_111_registrationRunsAfterSpringSecurity() {
        var registration = new QrushPublicTicketAssignGuard().qrushPublicTicketAssignGuard();

        assertTrue(registration.getOrder() > SecurityProperties.DEFAULT_FILTER_ORDER,
            "order " + registration.getOrder() + " must be greater than " + SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    @Test
    @DisplayName("[T22.112] the guard is enabled and maps to /* or /api/v2/public/*, never to a mid-path wildcard")
    void t22_112_registrationIsEnabledWithAServletSafeMapping() {
        var registration = new QrushPublicTicketAssignGuard().qrushPublicTicketAssignGuard();

        // Boot maps an empty pattern set to /*; a servlet url-pattern cannot hold a wildcard mid-path
        var patterns = List.copyOf(registration.getUrlPatterns());
        assertTrue(registration.isEnabled());
        assertTrue(patterns.isEmpty() || patterns.equals(List.of("/*")) || patterns.equals(List.of("/api/v2/public/*")),
            "unexpected url patterns " + patterns);
    }
}
