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

import alfio.TestConfiguration;
import alfio.controller.api.ControllerConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * P9: a request that came through the proxy reaches only the check-in API, the org-key API and the
 * health check. Servlet mocks for the path rules; [P9.13] walks every request mapping of the Spring
 * context through MockMvc.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class, ControllerConfiguration.class, QrushProxiedRequestAllowlist.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class QrushProxiedRequestAllowlistTest {

    /** Caddy sets this header on every request it forwards. */
    private static final String PROXY_HEADER = "X-Qrush-Proxy";
    private static final String CODE_PNG = "/api/v2/public/event/e/ticket/t/code.png";

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext applicationContext;

    /** What one request did: the answer the filter wrote and the chain it did or did not reach. */
    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {}

    private static Outcome pass(MockHttpServletRequest request) throws Exception {
        var registration = new QrushProxiedRequestAllowlist().qrushProxiedRequestAllowlistFilter();
        var response = spy(new MockHttpServletResponse());
        var chain = new MockFilterChain();
        registration.getFilter().doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private static MockHttpServletRequest proxied(String method, String uri, String header, String value) {
        var request = new MockHttpServletRequest(method, uri);
        request.addHeader(header, value);
        return request;
    }

    private static Outcome passProxied(String method, String uri) throws Exception {
        return pass(proxied(method, uri, PROXY_HEADER, "caddy"));
    }

    /** 404 written with setStatus, an empty and committed body, never sendError, and the chain untouched. */
    private static void assertRefused(Outcome outcome, String label) throws Exception {
        var response = outcome.response();
        assertEquals(404, response.getStatus(), label);
        assertEquals(0, response.getContentAsByteArray().length, label);
        assertTrue(response.isCommitted(), label + ": the empty answer must be committed");
        verify(response, never()).sendError(anyInt());
        verify(response, never()).sendError(anyInt(), any());
        assertNull(outcome.chain().getRequest(), label + ": the chain behind the filter must not be invoked");
    }

    /** MockFilterChain throws on a second call, so a recorded request means it ran exactly once. */
    private static void assertChainInvokedOnce(Outcome outcome, String label) {
        assertNotNull(outcome.chain().getRequest(), label + ": the chain behind the filter must be invoked once");
    }

    @Test
    @DisplayName("[P9.7] a proxied GET of the public code.png answers 404 with an empty body and never reaches the chain")
    void p9_7_proxiedPublicCodePngIsRefused() throws Exception {
        assertRefused(passProxied("GET", CODE_PNG), CODE_PNG);
    }

    @Test
    @DisplayName("[P9.8] the same request without a proxy header passes through")
    void p9_8_unproxiedPublicCodePngPassesThrough() throws Exception {
        assertChainInvokedOnce(pass(new MockHttpServletRequest("GET", CODE_PNG)), CODE_PNG);
    }

    @Test
    @DisplayName("[P9.9] each forwarded header alone marks the request as proxied: GET /admin answers 404")
    void p9_9_everyForwardedHeaderMarksTheRequestAsProxied() throws Exception {
        var headers = List.of(
            List.of("X-Forwarded-For", "203.0.113.7"),
            List.of("X-Forwarded-Proto", "https"),
            List.of("X-Forwarded-Host", "tickets.qrushapp.de"),
            List.of("Forwarded", "for=203.0.113.7"));
        for (var header : headers) {
            assertRefused(pass(proxied("GET", "/admin", header.get(0), header.get(1))), header.get(0));
        }
    }

    @Test
    @DisplayName("[P9.10] proxied requests to the org-key API, the check-in API and the health check pass through")
    void p9_10_openPathsPassThrough() throws Exception {
        assertChainInvokedOnce(passProxied("GET", "/api/v1/admin/event/e/stats"), "org-key API");
        assertChainInvokedOnce(passProxied("POST", "/admin/api/check-in/event/e/ticket/u"), "check-in API");
        assertChainInvokedOnce(passProxied("GET", "/healthz"), "health check");
    }

    @Test
    @DisplayName("[P9.11] proxied requests outside the open paths, near misses and traversals answer 404")
    void p9_11_closedPathsAndTraversalsAreRefused() throws Exception {
        var requests = List.of(
            List.of("GET", "/authentication"),
            List.of("POST", "/authenticate"),
            List.of("GET", "/api/v1/adminx"),
            List.of("GET", "/admin/api/check-inx/1"),
            List.of("GET", "/api/v1/admin/../../api/v2/public/event/e"),
            List.of("GET", "/api/v1/admin%2F..%2F..%2Fapi/v2/public/x"),
            List.of("GET", "/api/v1/admin/%2e%2e/%2e%2e/api/v2/public/x"),
            List.of("GET", "/api/v1/admin/..;/..;/api/v2/public/event/x"),
            List.of("GET", "/healthz/x"),
            List.of("GET", "/file/abc"));
        for (var line : requests) {
            assertRefused(passProxied(line.get(0), line.get(1)), line.get(0) + " " + line.get(1));
        }
    }

    @Test
    @DisplayName("[P9.12] matrix parameters are stripped before the check: a proxied ;jsessionid org-key path passes")
    void p9_12_matrixParametersAreStrippedBeforeTheCheck() throws Exception {
        assertChainInvokedOnce(passProxied("GET", "/api/v1/admin;jsessionid=1/event/e/stats"), "matrix parameter");
    }

    @Test
    @DisplayName("[P9.R1] the filter runs first (highest precedence), enabled, on every path")
    void p9_r1_registrationRunsFirstOnEveryPath() {
        var registration = new QrushProxiedRequestAllowlist().qrushProxiedRequestAllowlistFilter();

        var patterns = List.copyOf(registration.getUrlPatterns());
        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
        assertTrue(registration.isEnabled());
        // Boot maps an empty pattern set to /*
        assertTrue(patterns.isEmpty() || patterns.equals(List.of("/*")), "unexpected url patterns " + patterns);
    }

    /** One sample path per pattern: every variable, wildcard and catch-all becomes x. */
    private static String samplePath(String pattern) {
        var out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    out.append('x');
                }
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 0) {
                out.append(c);
            }
        }
        var path = out.toString().replace("**", "x").replace("*", "x");
        return path.isEmpty() ? "/" : path;
    }

    private static boolean isOpenPattern(String pattern) {
        return pattern.startsWith("/admin/api/check-in/") || pattern.startsWith("/api/v1/admin/") || pattern.equals("/healthz");
    }

    @Test
    @DisplayName("[P9.13] every request mapping outside the open paths answers a proxied request with 404 and reaches no handler")
    void p9_13_everyClosedMappingAnswersAProxiedRequestWith404() throws Exception {
        Set<String> walked = new TreeSet<>();
        List<String> reached = new ArrayList<>();
        for (var mapping : applicationContext.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (var info : mapping.getHandlerMethods().keySet()) {
                var methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of(RequestMethod.GET)
                    : info.getMethodsCondition().getMethods();
                for (var pattern : info.getPatternValues()) {
                    if (isOpenPattern(pattern)) {
                        continue;
                    }
                    walked.add(pattern);
                    var path = samplePath(pattern);
                    for (var method : methods) {
                        var label = method + " " + path + " (" + pattern + ")";
                        try {
                            var result = mockMvc.perform(request(HttpMethod.valueOf(method.name()), path)
                                    .header(PROXY_HEADER, "caddy"))
                                .andReturn();
                            var response = result.getResponse();
                            if (response.getStatus() != 404 || response.getContentAsByteArray().length != 0 || result.getHandler() != null) {
                                reached.add(label + " -> " + response.getStatus());
                            }
                        } catch (Exception e) {
                            reached.add(label + " -> " + e.getClass().getSimpleName());
                        }
                    }
                }
            }
        }

        assertFalse(walked.isEmpty(), "the walk found no request mapping");
        assertTrue(walked.contains("/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}/code.png"), "walked " + walked);
        assertTrue(walked.stream().anyMatch(p -> p.endsWith("/reservation/{reservationId}/receipt")), "walked " + walked);
        assertTrue(walked.stream().anyMatch(p -> p.endsWith("/reservation/{reservationId}/invoice")), "walked " + walked);
        assertTrue(walked.stream().anyMatch(p -> p.endsWith("/reservation/{reservationId}/re-send-email")), "walked " + walked);
        assertEquals(List.of(), reached, "proxied requests that got past the filter");
    }
}
