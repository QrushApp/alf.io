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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * qrush fork patches P8 and P11 — additive-only, see QRUSH-FORK.md.
 * alf.io's public ticket, reservation, online check-in, pass and wallet routes answer 403 for every
 * caller and every method; qrush reaches tickets only through the org-key routes under /api/v1/admin/.
 * Ordered before springSecurityFilterChain, so no CSRF refusal or redirect answers first.
 */
@Configuration
public class QrushPublicTicketAssignGuard {

    private static final List<PathPattern> REFUSED_ROUTES = Stream.of(
        "/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}",
        "/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}/full",
        "/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}/code.png",
        "/event/{eventName}/ticket/{ticketIdentifier}/code.png",
        "/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}/download-ticket",
        "/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}/send-ticket-by-email",
        "/api/v2/public/event/{eventName}/ticket/{ticketIdentifier}/code/{checkInCode}/check-in-info",
        "/event/{shortName}/ticket/{publicUUID}/check-in/{ticketCodeHash}",
        "/api/v2/public/reservation/**",
        "/api/v2/public/event/{eventName}/reservation/{reservationId}/receipt",
        "/api/v2/public/event/{eventName}/reservation/{reservationId}/invoice",
        "/api/v2/public/{purchaseContextType}/{publicIdentifier}/reservation/{reservationId}/re-send-email",
        "/api/v2/public/event/{eventName}/reserve-tickets",
        "/api/pass/event/{eventName}/v1/version/passes/{ticketUuid}",
        "/api/pass/event/{eventName}/v1/version/passes/{passTypeIdentifier}/{serialNumber}",
        "/api/wallet/event/{eventName}/v1/version/passes/{uuid}"
    ).map(PathPatternParser.defaultInstance::parse).toList();

    // a bean named like this @Configuration class collides with it at boot
    @Bean
    public FilterRegistrationBean<Filter> qrushPublicTicketAssignGuardFilter() {
        Filter filter = QrushPublicTicketAssignGuard::guard;
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }

    static boolean isRefusedRoute(HttpServletRequest request) {
        // match the path MVC routes: context stripped, each segment decoded, matrix parameters dropped
        var path = RequestPath.parse(request.getRequestURI(), request.getContextPath()).pathWithinApplication();
        var elements = path.elements();
        var withoutTrailingSlash = !elements.isEmpty() && elements.get(elements.size() - 1) instanceof PathContainer.Separator
            ? path.subPath(0, elements.size() - 1)
            : null;
        return REFUSED_ROUTES.stream().anyMatch(route -> route.matches(path)
            || (withoutTrailingSlash != null && route.matches(withoutTrailingSlash)));
    }

    private static void guard(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest
            && response instanceof HttpServletResponse httpResponse
            && isRefusedRoute(httpRequest)) {
            // setStatus, never sendError: an error dispatch would run the security chain and could answer otherwise
            httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
            httpResponse.setContentLength(0);
            httpResponse.flushBuffer();
            return;
        }
        chain.doFilter(request, response);
    }
}
