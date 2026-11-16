package com.virtualization.servicevirtualizationplatform.ui;

import com.virtualization.servicevirtualizationplatform.engine.drift.DriftSimulator;
import com.virtualization.servicevirtualizationplatform.engine.router.StubRouter;
import com.virtualization.servicevirtualizationplatform.engine.state.SessionStateStore;
import com.virtualization.servicevirtualizationplatform.engine.traffic.TrafficLogger;
import com.virtualization.servicevirtualizationplatform.model.StubDefinition;
import com.virtualization.servicevirtualizationplatform.model.TrafficEntry;
import com.virtualization.servicevirtualizationplatform.model.TrafficRecord;
import com.virtualization.servicevirtualizationplatform.recording.RecordingService;
import com.virtualization.servicevirtualizationplatform.registry.StubSearchService;
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
    private final StubSearchService stubSearchService;
    private final SessionStateStore sessionStateStore;
    private final TrafficLogger trafficLogger;
    private final DriftSimulator driftSimulator;
    private final RecordingService recordingService;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public DashboardController(StubRouter stubRouter,
                                StubSearchService stubSearchService,
                                SessionStateStore sessionStateStore,
                                TrafficLogger trafficLogger,
                                DriftSimulator driftSimulator,
                                RecordingService recordingService) {
        this.stubRouter = stubRouter;
        this.stubSearchService = stubSearchService;
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

        return "dashboard";
    }

    // ═══ STUBS ═══

    @GetMapping("/ui/stubs")
    public String stubs(Model model,
                        @RequestParam(required = false) String query,
                        @RequestParam(required = false) String protocol,
                        @RequestParam(required = false) String method,
                        @RequestParam(required = false) String enabled,
                        @RequestParam(required = false, defaultValue = "specificity") String sortBy) {
        model.addAttribute("activePage", "stubs");
        model.addAttribute("pageTitle", "Stubs");

        Collection<StubDefinition> allStubs = stubRouter.getAllStubs();
        Boolean enabledFilter = (enabled == null || enabled.isBlank()) ? null : Boolean.valueOf(enabled);

        // Delegates to the same StubSearchService the REST API uses — the UI previously
        // hand-rolled an independent filter/sort stream here that could silently drift out of
        // sync with the API's actual search behavior (e.g. the API's 4 sort modes were never
        // reachable from this page at all). One implementation, two callers now.
        List<StubDefinition> filtered = stubSearchService.search(
                query, protocol, method, null, enabledFilter, sortBy, Integer.MAX_VALUE);

        model.addAttribute("stubs", filtered);
        model.addAttribute("totalCount", allStubs.size());
        model.addAttribute("activeCount", stubRouter.getActiveCount());
        model.addAttribute("disabledCount", stubRouter.getDisabledCount());

        // Preserve filter values
        model.addAttribute("query", query);
        model.addAttribute("protocol", protocol);
        model.addAttribute("method", method);
        model.addAttribute("enabled", enabled);
        model.addAttribute("sortBy", sortBy);

        return "stubs";
    }

    // ═══ SESSIONS ═══

    @GetMapping("/ui/sessions")
    public String sessions(Model model, @RequestParam(required = false) String query) {
        model.addAttribute("activePage", "sessions");
        model.addAttribute("pageTitle", "Sessions");

        Set<String> sessionIds = sessionStateStore.listSessions();
        long now = System.currentTimeMillis();
        String q = (query == null) ? null : query.trim().toLowerCase();

        List<SessionView> sessions = sessionIds.stream()
                .filter(id -> q == null || q.isBlank() || id.toLowerCase().contains(q))
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
        model.addAttribute("totalSessionCount", sessionIds.size());
        model.addAttribute("query", query);
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
        model.addAttribute("protocolConfigs", driftSimulator.getProtocolConfigs());
        model.addAttribute("routeConfigs", driftSimulator.getRouteConfigs());
        model.addAttribute("methodConfigs", driftSimulator.getMethodConfigs());
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

    // ═══ PENDING REVIEW ═══

    @GetMapping("/ui/pending-review")
    public String pendingReview(Model model) {
        model.addAttribute("activePage", "pending-review");
        model.addAttribute("pageTitle", "Pending Review");

        List<RecordingView> pendingViews = recordingService.getHistory().stream()
                .filter(r -> r.status().name().equals("PENDING_REVIEW"))
                .map(r -> new RecordingView(
                        r.id(),
                        TIME_FMT.format(r.timestamp()),
                        r.rawLog().request().path(),
                        r.rawLog().request().method(),
                        r.status().name()
                ))
                .collect(Collectors.toList());

        model.addAttribute("pendingRecords", pendingViews);
        model.addAttribute("totalPending", pendingViews.size());
        return "pending-review";
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
