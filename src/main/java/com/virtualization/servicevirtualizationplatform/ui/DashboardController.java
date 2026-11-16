package com.virtualization.servicevirtualizationplatform.ui;

import com.virtualization.servicevirtualizationplatform.engine.drift.DriftSimulator;
import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.engine.traffic.TrafficLogger;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import com.virtualization.servicevirtualizationplatform.model.TrafficEntry;
import com.virtualization.servicevirtualizationplatform.model.TrafficRecord;
import com.virtualization.servicevirtualizationplatform.recording.RecordingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring MVC controller serving all UI pages.
 * Delegates data loading to engine services and populates Thymeleaf model attributes.
 */
@Controller
public class DashboardController {

    private final StubRouter stubRouter;
    private final SessionStateStore sessionStateStore;
    private final TrafficLogger trafficLogger;
    private final DriftSimulator driftSimulator;
    private final RecordingService recordingService;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public DashboardController(StubRouter stubRouter,
                                SessionStateStore sessionStateStore,
                                TrafficLogger trafficLogger,
                                DriftSimulator driftSimulator,
                                RecordingService recordingService) {
        this.stubRouter = stubRouter;
        this.sessionStateStore = sessionStateStore;
        this.trafficLogger = trafficLogger;
        this.driftSimulator = driftSimulator;
        this.recordingService = recordingService;
    }

    // ═══ DASHBOARD ═══

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("pageTitle", "Dashboard");

        // Stub stats
        model.addAttribute("totalStubs", stubRouter.totalSize());
        model.addAttribute("activeStubs", stubRouter.getActiveCount());
        model.addAttribute("disabledStubs", stubRouter.getDisabledCount());

        // Session count
        model.addAttribute("sessionCount", sessionStateStore.listSessions().size());

        // Recent traffic (last 5)
        List<TrafficEntry> recentTraffic = trafficLogger.getRecentEntries(5);
        model.addAttribute("recentTraffic", recentTraffic);

        // Drift config summary
        model.addAttribute("driftConfig", driftSimulator.getGlobalConfig());

        return "dashboard";
    }

    // ═══ STUBS ═══

    @GetMapping("/ui/stubs")
    public String stubs(Model model,
                        @RequestParam(required = false) String query,
                        @RequestParam(required = false) String protocol,
                        @RequestParam(required = false) String method,
                        @RequestParam(required = false) String enabled) {
        model.addAttribute("activePage", "stubs");
        model.addAttribute("pageTitle", "Stubs");

        Collection<StubDefinition> allStubs = stubRouter.getAllStubs();

        // Apply filters
        List<StubDefinition> filtered = allStubs.stream()
                .filter(s -> query == null || query.isBlank() ||
                        s.stubId().toLowerCase().contains(query.toLowerCase()) ||
                        s.requestMatcher().pathPattern().toLowerCase().contains(query.toLowerCase()))
                .filter(s -> protocol == null || protocol.isBlank() ||
                        s.requestMatcher().protocol().equalsIgnoreCase(protocol))
                .filter(s -> method == null || method.isBlank() ||
                        s.requestMatcher().method().equalsIgnoreCase(method))
                .filter(s -> enabled == null || enabled.isBlank() ||
                        s.isEnabled() == Boolean.parseBoolean(enabled))
                .sorted(Comparator.comparingInt(StubDefinition::priority).reversed()
                        .thenComparing(StubDefinition::stubId))
                .collect(Collectors.toList());

        model.addAttribute("stubs", filtered);
        model.addAttribute("totalCount", allStubs.size());
        model.addAttribute("activeCount", stubRouter.getActiveCount());
        model.addAttribute("disabledCount", stubRouter.getDisabledCount());

        // Preserve filter values
        model.addAttribute("query", query);
        model.addAttribute("protocol", protocol);
        model.addAttribute("method", method);
        model.addAttribute("enabled", enabled);

        return "stubs";
    }

    // ═══ SESSIONS ═══

    @GetMapping("/ui/sessions")
    public String sessions(Model model) {
        model.addAttribute("activePage", "sessions");
        model.addAttribute("pageTitle", "Sessions");

        Set<String> sessionIds = sessionStateStore.listSessions();
        long now = System.currentTimeMillis();

        List<SessionView> sessions = sessionIds.stream()
                .map(id -> {
                    long lastAccess = sessionStateStore.getLastAccessedAt(id);
                    long created = sessionStateStore.getCreatedAt(id);
                    long ttl = sessionStateStore.getTtlForSession(id);
                    long idleMs = now - lastAccess;
                    long remainingMs = Math.max(0, ttl - idleMs);

                    return new SessionView(
                            id,
                            sessionStateStore.getEntityCount(id),
                            created > 0 ? TIME_FMT.format(Instant.ofEpochMilli(created)) : "--",
                            formatDuration(idleMs),
                            formatDuration(remainingMs),
                            remainingMs
                    );
                })
                .sorted(Comparator.comparing(SessionView::sessionId))
                .collect(Collectors.toList());

        model.addAttribute("sessions", sessions);
        return "sessions";
    }

    // ═══ TRAFFIC ═══

    @GetMapping("/ui/traffic")
    public String traffic(Model model) {
        model.addAttribute("activePage", "traffic");
        model.addAttribute("pageTitle", "Traffic");
        model.addAttribute("recentTraffic", trafficLogger.getRecentEntries(50));
        model.addAttribute("totalLogged", trafficLogger.getTotalLogged());
        return "traffic";
    }

    // ═══ DRIFT ═══

    @GetMapping("/ui/drift")
    public String drift(Model model) {
        model.addAttribute("activePage", "drift");
        model.addAttribute("pageTitle", "Drift");
        model.addAttribute("globalConfig", driftSimulator.getGlobalConfig());
        model.addAttribute("routeConfigs", driftSimulator.getRouteConfigs());
        model.addAttribute("metrics", driftSimulator.getMetrics().getSnapshot());
        return "drift";
    }

    // ═══ RECORDING ═══

    @GetMapping("/ui/recording")
    public String recording(Model model) {
        model.addAttribute("activePage", "recording");
        model.addAttribute("pageTitle", "Recording");

        List<RecordingView> historyViews = recordingService.getHistory().stream()
                .map(r -> new RecordingView(
                        r.id(),
                        TIME_FMT.format(r.timestamp()),
                        r.rawLog().request().path(),
                        r.rawLog().request().method(),
                        r.status().name()
                ))
                .collect(Collectors.toList());

        model.addAttribute("history", historyViews);
        return "recording";
    }

    // ═══ HELPERS ═══

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long seconds = ms / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    /**
     * View model for session data displayed in the sessions page.
     */
    public record SessionView(
            String sessionId,
            int entityCount,
            String createdAtFormatted,
            String idleFormatted,
            String ttlRemainingFormatted,
            long ttlRemainingMs
    ) {}

    /**
     * View model for recording history entries.
     */
    public record RecordingView(
            String id,
            String timestampFormatted,
            String path,
            String method,
            String status
    ) {}
}
