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
import alfio.config.authentication.FormBasedWebSecurity;
import alfio.config.authentication.OpenIdUserSynchronizer;
import alfio.controller.OnlineCheckInController;
import alfio.controller.api.pass.PassKitApiController;
import alfio.controller.api.v2.user.EventApiV2Controller;
import alfio.controller.api.v2.user.ReservationApiV2Controller;
import alfio.controller.api.v2.user.TicketApiV2Controller;
import alfio.controller.api.wallet.GoogleWalletApiController;
import alfio.manager.RecaptchaService;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * P11: the guard in front of the real form-login security chain, in the order Boot registers both
 * (springSecurityFilterChain at SecurityProperties.DEFAULT_FILTER_ORDER), and a walk over every
 * request mapping of alf.io's public ticket, reservation, pass and wallet controllers.
 */
@SpringBootTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class,
    QrushPublicTicketAssignGuardChainTest.PublicControllersConfiguration.class,
    WebSecurityConfig.class, QrushPublicTicketAssignGuardChainTest.SessionRegistryConfiguration.class,
    QrushPublicTicketAssignGuardChainTest.FormSecurityConfiguration.class, QrushPublicTicketAssignGuard.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_DISABLE_JOBS, Initializer.PROFILE_INTEGRATION_TEST})
class QrushPublicTicketAssignGuardChainTest {

    private static final String U = "c0ffee00-0000-4000-8000-000000000001";

    // ControllerConfiguration's scan also picks up TestCheckRestApiStability's permit-all security chain
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @ComponentScan(basePackages = "alfio.controller", excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
        pattern = "alfio\\.controller\\.(IndexController|api\\.ControllerConfiguration|api\\.TestCheckRestApiStability.*)"))
    static class PublicControllersConfiguration {
    }

    static class SessionRegistryConfiguration {
        @Bean
        public SpringSessionBackedSessionRegistry<?> sessionRegistry(FindByIndexNameSessionRepository<?> sessionRepository) {
            return new SpringSessionBackedSessionRegistry<>(sessionRepository);
        }
    }

    static class FormSecurityConfiguration extends FormBasedWebSecurity {
        public FormSecurityConfiguration(Environment environment,
                                         UserManager userManager,
                                         RecaptchaService recaptchaService,
                                         ConfigurationManager configurationManager,
                                         CsrfTokenRepository csrfTokenRepository,
                                         DataSource dataSource,
                                         PasswordEncoder passwordEncoder,
                                         SpringSessionBackedSessionRegistry<?> sessionRegistry,
                                         OpenIdUserSynchronizer openIdUserSynchronizer) {
            super(environment, userManager, recaptchaService, configurationManager, csrfTokenRepository, dataSource,
                passwordEncoder, sessionRegistry, openIdUserSynchronizer, null, null);
        }
    }

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ApplicationContext applicationContext;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;

    /** The two filters in the order Boot runs them in production. */
    private List<Filter> filtersInProductionOrder() {
        var guard = new QrushPublicTicketAssignGuard().qrushPublicTicketAssignGuardFilter();
        var ordered = new ArrayList<>(List.of(
            Map.entry(SecurityProperties.DEFAULT_FILTER_ORDER, springSecurityFilterChain),
            Map.entry(guard.getOrder(), guard.getFilter())));
        ordered.sort(Comparator.comparing(Map.Entry::getKey));
        return ordered.stream().map(Map.Entry::getValue).toList();
    }

    @Test
    @DisplayName("[P11.6] an unkeyed public ticket-assign PUT through the real security chain answers 403 itself, never a CSRF forward that ends as 404")
    void p11_6_unkeyedPutThroughTheSecurityChainAnswers403() throws Exception {
        var mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(filtersInProductionOrder().toArray(Filter[]::new))
            .build();

        var result = mockMvc.perform(put("/api/v2/public/event/party-1/ticket/" + U)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertNull(result.getResponse().getForwardedUrl(), "the refusal must not be forwarded to /session-expired");
        assertNull(result.getHandler(), "no controller may be reached");
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
        return out.toString().replace("**", "x").replace("*", "x");
    }

    private static boolean mustBeRefused(Class<?> controller, String pattern) {
        if (controller == TicketApiV2Controller.class || controller == ReservationApiV2Controller.class
            || controller == OnlineCheckInController.class || controller == GoogleWalletApiController.class) {
            return true;
        }
        if (controller == PassKitApiController.class) {
            return pattern.contains("/version/passes/");
        }
        return controller == EventApiV2Controller.class && pattern.endsWith("/reserve-tickets");
    }

    @Test
    @DisplayName("[P11.7] every request mapping of the public ticket, reservation, online check-in, pass and wallet controllers and reserve-tickets is refused with 403")
    void p11_7_everyPublicTicketAndReservationMappingIsRefused() throws Exception {
        var guard = new QrushPublicTicketAssignGuard().qrushPublicTicketAssignGuardFilter().getFilter();
        Set<String> walked = new TreeSet<>();
        List<String> passed = new ArrayList<>();
        for (var mapping : applicationContext.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (var entry : mapping.getHandlerMethods().entrySet()) {
                HandlerMethod handler = entry.getValue();
                var info = entry.getKey();
                var methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of(RequestMethod.GET)
                    : info.getMethodsCondition().getMethods();
                for (var pattern : info.getPatternValues()) {
                    if (!mustBeRefused(handler.getBeanType(), pattern)) {
                        continue;
                    }
                    walked.add(pattern);
                    for (var method : methods) {
                        var response = new MockHttpServletResponse();
                        var chain = new MockFilterChain();
                        guard.doFilter(new MockHttpServletRequest(method.name(), samplePath(pattern)), response, chain);
                        if (response.getStatus() != 403 || chain.getRequest() != null) {
                            passed.add(method + " " + pattern + " -> " + response.getStatus());
                        }
                    }
                }
            }
        }

        assertFalse(walked.isEmpty(), "the walk found no request mapping");
        assertEquals(List.of(), passed, "public routes the guard let through (walked " + walked + ")");
    }
}
