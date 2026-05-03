package top.nextnet.paper.monitor.web;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.Feed;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.model.LogicalFeedAccessGrant;
import top.nextnet.paper.monitor.model.Paper;
import top.nextnet.paper.monitor.model.UserSettings;
import top.nextnet.paper.monitor.repo.AppUserRepository;
import top.nextnet.paper.monitor.repo.PaperEventRepository;
import top.nextnet.paper.monitor.repo.FeedRepository;
import top.nextnet.paper.monitor.repo.LogicalFeedRepository;
import top.nextnet.paper.monitor.repo.PaperRepository;
import top.nextnet.paper.monitor.service.AuthService;
import top.nextnet.paper.monitor.service.BackupService;
import top.nextnet.paper.monitor.service.CurrentUserContext;
import top.nextnet.paper.monitor.service.DoiMetadataService;
import top.nextnet.paper.monitor.service.FeedPollingService;
import top.nextnet.paper.monitor.service.GithubAuthService;
import top.nextnet.paper.monitor.service.GithubRepositoryService;
import top.nextnet.paper.monitor.service.JsonCodec;
import top.nextnet.paper.monitor.service.LogicalFeedAccessService;
import top.nextnet.paper.monitor.service.MarkdownConversionService;
import top.nextnet.paper.monitor.service.NotificationService;
import top.nextnet.paper.monitor.service.OidcService;
import top.nextnet.paper.monitor.service.PaperEventService;
import top.nextnet.paper.monitor.service.PaperGitSyncService;
import top.nextnet.paper.monitor.service.PaperPdfImportService;
import top.nextnet.paper.monitor.service.PaperStorageService;
import top.nextnet.paper.monitor.service.ReviewService;
import top.nextnet.paper.monitor.service.TtsService;
import top.nextnet.paper.monitor.service.WorkflowStateConfig;

@Path("/")
@ApplicationScoped
public class HomeResource {

    private final Template home;
    private final Template admin;
    private final Template logs;
    private final Template login;
    private final Template quickSetup;
    private final LogicalFeedRepository logicalFeedRepository;
    private final FeedRepository feedRepository;
    private final PaperRepository paperRepository;
    private final PaperEventRepository paperEventRepository;
    private final AppUserRepository appUserRepository;
    private final FeedPollingService feedPollingService;
    private final DoiMetadataService doiMetadataService;
    private final PaperEventService paperEventService;
    private final PaperGitSyncService paperGitSyncService;
    private final PaperPdfImportService paperPdfImportService;
    private final PaperStorageService paperStorageService;
    private final TtsService ttsService;
    private final BackupService backupService;
    private final AuthService authService;
    private final OidcService oidcService;
    private final GithubAuthService githubAuthService;
    private final GithubRepositoryService githubRepositoryService;
    private final LogicalFeedAccessService logicalFeedAccessService;
    private final MarkdownConversionService markdownConversionService;
    private final NotificationService notificationService;
    private final ReviewService reviewService;
    private final Instance<CurrentUserContext> currentUserContext;
    private final String baseUrl;
    private final String paperDataExtractorBaseUrl;

    public HomeResource(
            @Location("home") Template home,
            @Location("admin") Template admin,
            @Location("logs") Template logs,
            @Location("login") Template login,
            @Location("quick-setup") Template quickSetup,
            LogicalFeedRepository logicalFeedRepository,
            FeedRepository feedRepository,
            PaperRepository paperRepository,
            PaperEventRepository paperEventRepository,
            AppUserRepository appUserRepository,
            FeedPollingService feedPollingService,
            DoiMetadataService doiMetadataService,
            PaperEventService paperEventService,
            PaperGitSyncService paperGitSyncService,
            PaperPdfImportService paperPdfImportService,
            PaperStorageService paperStorageService,
            TtsService ttsService,
            BackupService backupService,
            AuthService authService,
            OidcService oidcService,
            GithubAuthService githubAuthService,
            GithubRepositoryService githubRepositoryService,
            LogicalFeedAccessService logicalFeedAccessService,
            MarkdownConversionService markdownConversionService,
            ReviewService reviewService,
            NotificationService notificationService,
            Instance<CurrentUserContext> currentUserContext,
            @ConfigProperty(name = "paper-monitor.base-url", defaultValue = "http://localhost:8080") String baseUrl,
            @ConfigProperty(name = "paper-monitor.pde.base-url", defaultValue = "http://localhost:8091") String paperDataExtractorBaseUrl
    ) {
        this.home = home;
        this.admin = admin;
        this.logs = logs;
        this.login = login;
        this.quickSetup = quickSetup;
        this.logicalFeedRepository = logicalFeedRepository;
        this.feedRepository = feedRepository;
        this.paperRepository = paperRepository;
        this.paperEventRepository = paperEventRepository;
        this.appUserRepository = appUserRepository;
        this.feedPollingService = feedPollingService;
        this.doiMetadataService = doiMetadataService;
        this.paperEventService = paperEventService;
        this.paperGitSyncService = paperGitSyncService;
        this.paperPdfImportService = paperPdfImportService;
        this.paperStorageService = paperStorageService;
        this.ttsService = ttsService;
        this.backupService = backupService;
        this.authService = authService;
        this.oidcService = oidcService;
        this.githubAuthService = githubAuthService;
        this.githubRepositoryService = githubRepositoryService;
        this.logicalFeedAccessService = logicalFeedAccessService;
        this.markdownConversionService = markdownConversionService;
        this.reviewService = reviewService;
        this.notificationService = notificationService;
        this.currentUserContext = currentUserContext;
        this.baseUrl = baseUrl == null ? "http://localhost:8080" : baseUrl.trim();
        this.paperDataExtractorBaseUrl = paperDataExtractorBaseUrl == null ? "http://localhost:8091" : paperDataExtractorBaseUrl.trim();
    }

    @GET
    @Path("/login")
    @Transactional
    public TemplateInstance login(
            @QueryParam("returnTo") String returnTo,
            @QueryParam("info") String info,
            @QueryParam("error") String error
    ) {
        return login.data("returnTo", safeReturnTo(returnTo))
                .data("oidcEnabled", oidcService.isEnabled())
                .data("githubEnabled", githubAuthService.isEnabled())
                .data("bootstrapLocalAdmin", appUserRepository.countLocalAccounts() == 0)
                .data("infoMessage", normalize(info))
                .data("errorMessage", normalize(error));
    }

    @POST
    @Path("/login/local")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response loginLocal(
            @RestForm("username") String username,
            @RestForm("password") String password,
            @RestForm("returnTo") String returnTo
    ) {
        try {
            AppUser user = authService.loginLocal(username, password);
            return loginResponse(user, safeReturnTo(returnTo));
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/signup/local")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response signUpLocal(
            @RestForm("username") String username,
            @RestForm("displayName") String displayName,
            @RestForm("email") String email,
            @RestForm("password") String password
    ) {
        try {
            authService.signUpLocal(username, displayName, email, password);
            return seeOther("/login?info=" + urlEncode("Check your email to verify your account, then wait for admin approval"));
        } catch (IllegalArgumentException e) {
            return seeOther("/login?error=" + urlEncode(e.getMessage()));
        }
    }

    @GET
    @Path("/signup/verify")
    @Transactional
    public Response verifySignupEmail(@QueryParam("token") String token) {
        try {
            AppUser user = authService.verifyEmail(token);
            String info = user.approved
                    ? "Email verified. You can now sign in"
                    : "Email verified. Your account now awaits admin approval";
            return seeOther("/login?info=" + urlEncode(info));
        } catch (IllegalArgumentException e) {
            return seeOther("/login?error=" + urlEncode(e.getMessage()));
        }
    }

    @GET
    @Path("/auth/oidc/start")
    @Transactional
    public Response startOidcLogin(@QueryParam("returnTo") String returnTo) {
        try {
            return Response.status(Response.Status.SEE_OTHER)
                    .header(HttpHeaders.LOCATION, oidcService.startLogin(returnTo).toString())
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/auth/oidc/callback")
    @Transactional
    public Response finishOidcLogin(
            @QueryParam("state") String state,
            @QueryParam("code") String code
    ) {
        try {
            OidcService.OidcLoginResult loginResult = oidcService.finishLogin(state, code);
            return loginResponse(loginResult.user(), loginResult.returnTo());
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/auth/github/start")
    @Transactional
    public Response startGithubLogin(@QueryParam("returnTo") String returnTo) {
        try {
            return Response.status(Response.Status.SEE_OTHER)
                    .header(HttpHeaders.LOCATION, githubAuthService.startLogin(returnTo).toString())
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/auth/github/callback")
    @Transactional
    public Response finishGithubLogin(
            @QueryParam("state") String state,
            @QueryParam("code") String code
    ) {
        try {
            GithubAuthService.GithubLoginResult loginResult = githubAuthService.finishLogin(state, code);
            if (!loginResult.user().isApproved()) {
                return seeOther("/login?info=" + urlEncode("GitHub sign-in succeeded. Your account now awaits admin approval."));
            }
            return loginResponse(loginResult.user(), loginResult.returnTo());
        } catch (IOException | IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/logout")
    @Transactional
    public Response logout() {
        CurrentUserContext context = currentUserContext.get();
        if (context.session() != null) {
            authService.logout(context.session().token);
        }
        return Response.status(Response.Status.SEE_OTHER)
                .header(HttpHeaders.LOCATION, "/login")
                .cookie(authService.clearCookie())
                .build();
    }

    @GET
    @Transactional
    public TemplateInstance index(
            @jakarta.ws.rs.QueryParam("paperId") Long paperId,
            @jakarta.ws.rs.QueryParam("logicalFeedId") Long logicalFeedId,
            @QueryParam("info") String info,
            @QueryParam("error") String error
    ) {
        AppUser currentUser = currentUserContext.get().user();
        if (currentUser == null) {
            return login.data("returnTo", "/")
                    .data("oidcEnabled", oidcService.isEnabled())
                    .data("githubEnabled", githubAuthService.isEnabled())
                    .data("bootstrapLocalAdmin", appUserRepository.countLocalAccounts() == 0)
                    .data("infoMessage", normalize(info))
                    .data("errorMessage", normalize(error));
        }
        List<LogicalFeed> logicalFeeds = logicalFeedAccessService.readableLogicalFeeds(currentUser).stream()
                .filter((logicalFeed) -> !logicalFeed.archived)
                .toList();
        populateLogicalFeedAccessFlags(logicalFeeds, currentUser);
        paperGitSyncService.syncLogicalFeeds(logicalFeeds);
        List<LogicalFeed> adminLogicalFeeds = logicalFeeds.stream()
                .filter((logicalFeed) -> logicalFeed.viewerCanAdmin)
                .toList();
        populatePaperCounts(logicalFeeds);
        populateLogicalFeedDashboardStats(logicalFeeds);
        return home.data("recentPapers", List.of())
                .data("initialPaperId", paperId)
                .data("initialLogicalFeedId", logicalFeedId)
                .data("logicalFeeds", logicalFeeds)
                .data("adminLogicalFeeds", adminLogicalFeeds)
                .data("currentUser", currentUser)
                .data("canAdmin", currentUserContext.get().isAdmin())
                .data("authenticated", currentUser != null)
                .data("paperDataExtractorBaseUrl", paperDataExtractorBaseUrl)
                .data("infoMessage", normalize(info))
                .data("errorMessage", normalize(error))
                .data("shareMode", false)
                .data("sharedPaper", null)
                .data("sharedPaperUrl", null);
    }

    @POST
    @Path("/logical-feeds/{id}/archive")
    @Transactional
    public Response archiveLogicalFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(id, requireCurrentUser());
        logicalFeed.archived = true;
        return seeOther("/?info=" + urlEncode("Archived paper feed: " + logicalFeed.name));
    }

    @GET
    @Path("/api/papers/browser")
    @Transactional
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> browserPapers(@QueryParam("logicalFeedId") Long logicalFeedId) {
        AppUser currentUser = currentUserContext.get().user();
        if (currentUser == null) {
            throw new NotFoundException();
        }
        if (logicalFeedId == null) {
            return List.of();
        }
        LogicalFeed logicalFeed = logicalFeedAccessService.requireReadableLogicalFeed(logicalFeedId, currentUser);
        List<Paper> papers = new ArrayList<>(paperRepository.findAllForReader(logicalFeed));
        populatePaperBadges(papers);
        for (Paper paper : papers) {
            paper.viewerCanEdit = logicalFeedAccessService.canAdmin(paper.logicalFeed, currentUser);
        }
        return papers.stream().map(this::paperBrowserItem).toList();
    }

    @GET
    @Path("/share/paper/{token}")
    @Transactional
    public Response sharedPaper(@jakarta.ws.rs.PathParam("token") String token) {
        Paper paper = paperRepository.findByShareToken(token).orElseThrow(NotFoundException::new);
        AppUser currentUser = currentUserContext.get().user();
        if (paper.logicalFeed == null) {
            throw new NotFoundException();
        }
        boolean canRead = logicalFeedAccessService.canRead(paper.logicalFeed, currentUser);
        if (!paper.logicalFeed.publicReadable && !canRead) {
            throw new NotFoundException();
        }
        if (currentUser != null && canRead) {
            return seeOther("/?paperId=" + paper.id + "&logicalFeedId=" + paper.logicalFeed.id);
        }
        List<LogicalFeed> logicalFeeds = List.of(paper.logicalFeed);
        populateLogicalFeedAccessFlags(logicalFeeds, currentUser);
        populatePaperCounts(logicalFeeds);
        populateLogicalFeedDashboardStats(logicalFeeds);
        populatePaperBadges(List.of(paper));
        paper.viewerCanEdit = logicalFeedAccessService.canAdmin(paper.logicalFeed, currentUser);
        List<LogicalFeed> adminLogicalFeeds = paper.viewerCanEdit ? logicalFeeds : List.of();
        TemplateInstance page = home.data("recentPapers", List.of(paper))
                .data("initialPaperId", paper.id)
                .data("initialLogicalFeedId", paper.logicalFeed.id)
                .data("logicalFeeds", logicalFeeds)
                .data("adminLogicalFeeds", adminLogicalFeeds)
                .data("currentUser", currentUser)
                .data("canAdmin", currentUserContext.get().isAdmin())
                .data("authenticated", currentUser != null)
                .data("shareMode", true)
                .data("sharedPaper", paper)
                .data("sharedPaperUrl", normalizeBaseUrl() + "/share/paper/" + paper.shareToken);
        return Response.ok(page.render(), MediaType.TEXT_HTML).build();
    }

    @GET
    @Path("/share/feed/{token}")
    @Transactional
    public Response sharedFeed(@jakarta.ws.rs.PathParam("token") String token) {
        LogicalFeed logicalFeed = logicalFeedRepository.findByPublicShareToken(token).orElseThrow(NotFoundException::new);
        AppUser currentUser = currentUserContext.get().user();
        boolean canRead = logicalFeedAccessService.canRead(logicalFeed, currentUser);
        if (!logicalFeed.publicReadable && !canRead) {
            throw new NotFoundException();
        }
        if (currentUser != null && canRead) {
            return seeOther("/?logicalFeedId=" + logicalFeed.id);
        }
        ensurePublicShareToken(logicalFeed);
        populateLogicalFeedAccessFlags(List.of(logicalFeed), currentUser);
        populatePaperCounts(List.of(logicalFeed));
        populateLogicalFeedDashboardStats(List.of(logicalFeed));
        List<Paper> papers = new ArrayList<>(paperRepository.findAllForExport(logicalFeed));
        populatePaperBadges(papers);
        for (Paper paper : papers) {
            paper.viewerCanEdit = false;
        }
        TemplateInstance page = home.data("recentPapers", papers)
                .data("initialPaperId", null)
                .data("initialLogicalFeedId", logicalFeed.id)
                .data("logicalFeeds", List.of(logicalFeed))
                .data("adminLogicalFeeds", List.of())
                .data("currentUser", currentUser)
                .data("canAdmin", false)
                .data("authenticated", false)
                .data("shareMode", true)
                .data("sharedPaper", null)
                .data("sharedPaperUrl", null);
        return Response.ok(page.render(), MediaType.TEXT_HTML).build();
    }

    @GET
    @Path("/admin")
    @Transactional
    public TemplateInstance admin() {
        AppUser currentUser = requireCurrentUser();
        List<LogicalFeed> readableLogicalFeeds = logicalFeedAccessService.readableLogicalFeeds(currentUser);
        List<Long> readableLogicalFeedIds = readableLogicalFeeds.stream().map((feed) -> feed.id).toList();
        List<LogicalFeed> logicalFeeds = logicalFeedRepository.findAllForAdminView().stream()
                .filter((feed) -> readableLogicalFeedIds.contains(feed.id))
                .toList();
        populateLogicalFeedAccessFlags(logicalFeeds, currentUser);
        paperGitSyncService.syncLogicalFeeds(logicalFeeds);
        List<LogicalFeed> adminLogicalFeeds = logicalFeeds.stream()
                .filter((logicalFeed) -> logicalFeed.viewerCanAdmin)
                .toList();
        List<Feed> feeds = logicalFeedAccessService.readableFeeds(currentUser);
        List<String> ttsVoices = List.of();
        String ttsVoicesError = null;
        try {
            ttsVoices = ttsService.availableVoices();
        } catch (IOException e) {
            ttsVoicesError = e.getMessage();
        }
        return admin.data("logicalFeeds", logicalFeeds)
                .data("adminLogicalFeeds", adminLogicalFeeds)
                .data("feeds", feeds)
                .data("ttsSettings", getOrCreateUserSettings())
                .data("ttsVoices", ttsVoices)
                .data("ttsVoicesError", ttsVoicesError)
                .data("recentPapers", paperRepository.findRecent(30))
                .data("currentUser", currentUser)
                .data("canAdmin", currentUserContext.get().isAdmin())
                .data("allUsers", authService.allUsers())
                .data("userManagementUsers", currentUserContext.get().isAdmin() ? authService.allUsers() : List.of(currentUser))
                .data("oidcEnabled", oidcService.isEnabled())
                .data("githubEnabled", githubAuthService.isEnabled());
    }

    @GET
    @Path("/admin/quick-setup")
    @Transactional
    public TemplateInstance quickSetupConfirm(
            @QueryParam("paperFeedName") String paperFeedName,
            @QueryParam("rssUrl") String rssUrl
    ) {
        requireCurrentUser();
        String normalizedPaperFeedName = normalizeRequired(paperFeedName, "paperFeedName is required");
        String normalizedRssUrl = normalizeRequired(rssUrl, "rssUrl is required");
        return quickSetup.data("mode", "confirm")
                .data("paperFeedName", normalizedPaperFeedName)
                .data("rssUrl", normalizedRssUrl)
                .data("workflowYaml", """
                        - DISCARDED
                        - NEW
                        - TODO
                        - DONE
                        """)
                .data("defaultPaperStatus", "NEW")
                .data("confirmUrl", "/admin/quick-setup/run?paperFeedName=" + urlEncode(normalizedPaperFeedName)
                        + "&rssUrl=" + urlEncode(normalizedRssUrl))
                .data("cancelUrl", "/admin");
    }

    @GET
    @Path("/admin/quick-setup/run")
    @Transactional
    public TemplateInstance quickSetupRun(
            @QueryParam("paperFeedName") String paperFeedName,
            @QueryParam("rssUrl") String rssUrl
    ) {
        requireCurrentUser();
        String normalizedPaperFeedName = normalizeRequired(paperFeedName, "paperFeedName is required");
        String normalizedRssUrl = normalizeRequired(rssUrl, "rssUrl is required");
        return quickSetup.data("mode", "running")
                .data("paperFeedName", normalizedPaperFeedName)
                .data("rssUrl", normalizedRssUrl)
                .data("executeUrl", "/admin/quick-setup/execute?paperFeedName=" + urlEncode(normalizedPaperFeedName)
                        + "&rssUrl=" + urlEncode(normalizedRssUrl))
                .data("cancelUrl", "/admin");
    }

    @GET
    @Path("/admin/quick-setup/execute")
    @Transactional
    @Produces(MediaType.TEXT_PLAIN)
    public Response quickSetupExecute(
            @QueryParam("paperFeedName") String paperFeedName,
            @QueryParam("rssUrl") String rssUrl
    ) {
        AppUser currentUser = requireCurrentUser();
        try {
            String normalizedPaperFeedName = normalizeRequired(paperFeedName, "paperFeedName is required");
            String normalizedRssUrl = normalizeRequired(rssUrl, "rssUrl is required");
            if (logicalFeedRepository.find("name", normalizedPaperFeedName).firstResultOptional().isPresent()) {
                throw new WebApplicationException("A paper feed with this name already exists", Response.Status.BAD_REQUEST);
            }
            if (feedRepository.findByUrl(normalizedRssUrl).isPresent()) {
                throw new WebApplicationException("An RSS feed with this URL already exists", Response.Status.BAD_REQUEST);
            }
            LogicalFeed logicalFeed = createQuickSetupPaperFeed(currentUser, normalizedPaperFeedName, normalizedRssUrl);
            return Response.ok("/?logicalFeedId=" + logicalFeed.id).type(MediaType.TEXT_PLAIN).build();
        } catch (WebApplicationException e) {
            return rethrowOrPlainText(e);
        }
    }

    @POST
    @Path("/admin/tts")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateTtsSettings(
            @RestForm("voice") String voice,
            @RestForm("speedMultiplier") Double speedMultiplier
    ) {
        UserSettings settings = getOrCreateUserSettings();
        settings.voice = normalize(voice);
        settings.speedMultiplier = normalizeSpeedMultiplier(speedMultiplier);
        return seeOther("/admin");
    }

    @POST
    @Path("/admin/setup")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response setupLogicalFeed(
            @RestForm("logicalFeedName") String logicalFeedName,
            @RestForm("logicalFeedDescription") String logicalFeedDescription,
            @RestForm("workflowStates") String workflowStates,
            @RestForm("rssFeedName") String rssFeedName,
            @RestForm("rssFeedUrl") String rssFeedUrl,
            @RestForm("pollIntervalMinutes") Integer pollIntervalMinutes,
            @RestForm("publicReadable") String publicReadable,
            @RestForm("notifyOnNewRssPapers") String notifyOnNewRssPapers
    ) {
        AppUser currentUser = requireCurrentUser();

        String normalizedLogicalFeedName = normalize(logicalFeedName);
        if (normalizedLogicalFeedName == null) {
            throw new WebApplicationException("Logical feed name is required", Response.Status.BAD_REQUEST);
        }

        String normalizedWorkflowStates = normalizeWorkflowStates(
                workflowStates == null || workflowStates.isBlank()
                        ? """
                        - Discarded
                        - New
                        - Todo
                        - Done
                        """
                        : workflowStates);

        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = normalizedLogicalFeedName;
       logicalFeed.description = normalize(logicalFeedDescription);
        logicalFeed.workflowStates = normalizedWorkflowStates;
        logicalFeed.owner = currentUser;
        logicalFeed.publicReadable = "on".equalsIgnoreCase(publicReadable);
        logicalFeed.notifyOnNewRssPapers = !"off".equalsIgnoreCase(notifyOnNewRssPapers);
        ensurePublicShareToken(logicalFeed);
        logicalFeedRepository.persist(logicalFeed);

        Feed feed = new Feed();
        feed.name = normalize(rssFeedName);
        if (feed.name == null) {
            feed.name = logicalFeed.name + " RSS";
        }
        feed.url = normalizeRequired(rssFeedUrl, "RSS feed URL is required");
        feed.pollIntervalMinutes = pollIntervalMinutes == null ? 60 : pollIntervalMinutes;
        feed.logicalFeed = logicalFeed;
        feedRepository.persist(feed);

        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @POST
    @Path("/admin/local-users")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createLocalUser(
            @RestForm("username") String username,
            @RestForm("displayName") String displayName,
            @RestForm("email") String email,
            @RestForm("password") String password,
            @RestForm("admin") String admin
    ) {
        try {
            authService.createLocalUser(username, displayName, email, password, "on".equalsIgnoreCase(admin));
            return seeOther("/admin");
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/admin/users/{id}/update")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateUser(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("displayName") String displayName,
            @RestForm("email") String email,
            @RestForm("password") String password,
            @RestForm("admin") String admin
    ) {
        try {
            authService.updateUser(id, displayName, email, "on".equalsIgnoreCase(admin), password);
            return seeOther("/admin");
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/admin/users/{id}/approve")
    @Transactional
    public Response approveUser(@jakarta.ws.rs.PathParam("id") Long id) {
        try {
            authService.approveUser(id);
            return seeOther("/admin#users");
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/admin/users/{id}/delete")
    @Transactional
    public Response deleteUser(@jakarta.ws.rs.PathParam("id") Long id) {
        try {
            authService.deleteUser(id);
            return seeOther("/admin");
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/logical-feeds/{id}/share")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response shareLogicalFeed(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("userId") Long userId,
            @RestForm("role") String role
    ) {
        AppUser currentUser = requireCurrentUser();
        LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(id, currentUser);
        if (userId == null) {
            throw new WebApplicationException("User is required", Response.Status.BAD_REQUEST);
        }
        try {
            AppUser target = appUserRepository.findByIdOptional(userId)
                    .orElseThrow(() -> new WebApplicationException("Unknown user", Response.Status.BAD_REQUEST));
            LogicalFeedAccessGrant grant = logicalFeedAccessService.grant(logicalFeed, target, role);
            notificationService.sendFeedAccessNotification(target, logicalFeed, grant.role, currentUser);
            return seeOther("/admin");
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/logical-feeds/{id}/share/{userId}/delete")
    @Transactional
    public Response revokeLogicalFeedAccess(
            @jakarta.ws.rs.PathParam("id") Long id,
            @jakarta.ws.rs.PathParam("userId") Long userId
    ) {
        AppUser currentUser = requireCurrentUser();
        LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(id, currentUser);
        AppUser target = appUserRepository.findById(userId);
        if (target != null) {
            logicalFeedAccessService.revoke(logicalFeed, target);
        }
        return seeOther("/admin");
    }

    @GET
    @Path("/logs")
    @Transactional
    public TemplateInstance logs() {
        AppUser currentUser = requireCurrentUser();
        List<?> readableFeedIds = logicalFeedAccessService.readableLogicalFeeds(currentUser).stream().map((feed) -> feed.id).toList();
        List<?> events = paperEventRepository.findRecent(300).stream()
                .filter((event) -> event.paper != null
                        && event.paper.logicalFeed != null
                        && readableFeedIds.contains(event.paper.logicalFeed.id))
                .toList();
        return logs.data("events", events)
                .data("currentUser", currentUser)
                .data("canAdmin", currentUserContext.get().isAdmin());
    }

    @POST
    @Path("/tts/speak")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces("audio/wav")
    public Response speak(
            @RestForm("text") String text,
            @RestForm("voice") String voice
    ) {
        try {
            byte[] audio = ttsService.speak(text, voice);
            return Response.ok(audio, "audio/wav").build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/admin/export")
    public Response exportBackup() {
        try {
            AppUser currentUser = requireCurrentUser();
            byte[] zip = backupService.exportZip(logicalFeedAccessService.readableLogicalFeeds(currentUser), currentUser);
            return Response.ok(zip, "application/zip")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"paper-monitor-" + slug(currentUser.displayLabel()) + "-backup.zip\"")
                    .build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to export backup", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("/papers/import-doi")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importPaperFromDoi(
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("doi") String doi,
            @RestForm("pdf") FileUpload pdf
    ) {
        AppUser currentUser = requireCurrentUser();
        LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(logicalFeedId, currentUser);
        List<String> normalizedDois = normalizeDoiInputs(doi);
        if (normalizedDois.size() > 1 && pdf != null && pdf.fileName() != null && !pdf.fileName().isBlank()) {
            throw new WebApplicationException("Attach a PDF only when importing a single DOI", Response.Status.BAD_REQUEST);
        }

        List<Paper> importedPapers = new ArrayList<>();
        List<String> importedDois = new ArrayList<>();
        List<String> existingDois = new ArrayList<>();
        List<String> failedDois = new ArrayList<>();
        Paper existingTarget = null;
        for (String normalizedDoi : normalizedDois) {
            try {
                Paper paper = importPaperFromNormalizedDoi(logicalFeed, normalizedDoi, pdf, importedPapers.isEmpty());
                if (paper == null) {
                    Paper existing = paperRepository.findByLogicalFeedAndSourceLink(logicalFeed, normalizedDoiSourceLink(normalizedDoi))
                            .orElseThrow(() -> new WebApplicationException("Existing paper lookup failed", Response.Status.INTERNAL_SERVER_ERROR));
                    existingDois.add(normalizedDoi);
                    if (existingTarget == null) {
                        existingTarget = existing;
                    }
                    continue;
                }
                importedPapers.add(paper);
                importedDois.add(normalizedDoi);
            } catch (WebApplicationException e) {
                failedDois.add(formatDoiFailure(normalizedDoi, e));
            }
        }
        if (!importedPapers.isEmpty()) {
            paperGitSyncService.syncLogicalFeed(logicalFeed);
        }

        String location = singleDoiRedirect(logicalFeed.id, normalizedDois.size() == 1
                ? importedPapers.isEmpty() ? existingTarget : importedPapers.get(0)
                : null);
        String infoMessage = buildDoiImportInfoMessage(importedDois, existingDois);
        String errorMessage = buildDoiImportErrorMessage(failedDois);
        if (infoMessage != null) {
            location = appendQueryParam(location, "info", infoMessage);
        }
        if (errorMessage != null) {
            location = appendQueryParam(location, "error", errorMessage);
        }
        return seeOther(location);
    }

    @GET
    @Path("/papers/import-doi/preview")
    @Produces(MediaType.APPLICATION_JSON)
    public Response previewPaperFromDoi(@QueryParam("doi") String doi) {
        requireCurrentUser();
        String normalizedDoi = normalizeDoiInput(doi);
        DoiMetadataService.DoiMetadata metadata = fetchDoiMetadata(normalizedDoi);
        String responseJson = JsonCodec.stringify(Map.of(
                "doi", normalizedDoi,
                "sourceLink", stringOrDefault(metadata.doiUrl(), normalizedDoiSourceLink(normalizedDoi)),
                "title", stringOrDefault(metadata.title(), ""),
                "authors", stringOrDefault(metadata.authors(), ""),
                "publisher", stringOrDefault(metadata.publisher(), ""),
                "summary", stringOrDefault(metadata.summary(), ""),
                "openAccessUrl", stringOrDefault(metadata.openAccessUrl(), ""),
                "publishedOn", metadata.publishedOn() == null ? "" : metadata.publishedOn().toString()
        ));
        return Response.ok(responseJson, MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/papers/import-doi/preview-pdf")
    @Produces("application/pdf")
    public Response previewPaperPdfFromUrl(
            @QueryParam("url") String url,
            @QueryParam("title") String title
    ) {
        requireCurrentUser();
        String normalizedUrl = normalizeRequired(url, "PDF URL is required");
        try {
            PaperPdfImportService.DownloadedPdf downloadedPdf = paperPdfImportService.downloadPdf(normalizedUrl, normalize(title));
            return Response.ok(downloadedPdf.content(), "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + downloadedPdf.fileName() + "\"")
                    .build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WebApplicationException("Failed to fetch remote PDF", Response.Status.BAD_GATEWAY);
        }
    }

    @GET
    @Path("/exports/tab")
    @Transactional
    @Produces("text/markdown")
    public Response exportTab(
            @jakarta.ws.rs.QueryParam("logicalFeedId") Long logicalFeedId,
            @jakarta.ws.rs.QueryParam("status") String status,
            @jakarta.ws.rs.QueryParam("statuses") String statuses,
            @jakarta.ws.rs.QueryParam("tags") String tags,
            @DefaultValue("report") @jakarta.ws.rs.QueryParam("kind") String kind,
            @DefaultValue("md") @jakarta.ws.rs.QueryParam("format") String format
    ) {
        if (logicalFeedId == null) {
            throw new WebApplicationException("logicalFeedId is required", Response.Status.BAD_REQUEST);
        }
        LogicalFeed logicalFeed = logicalFeedAccessService.requireReadableLogicalFeed(logicalFeedId, requireCurrentUser());
        String normalizedKind = normalizeExportKind(kind);
        String normalizedFormat = normalizeExportFormat(format, normalizedKind);
        List<String> normalizedTags = normalizeExportTags(tags);
        List<String> normalizedStatuses = normalizeNullableExportStatuses(logicalFeed, statuses, status);
        List<Paper> papers = selectPapersForExport(logicalFeed, normalizedStatuses, normalizedTags);
        String filterLabel = exportFilterLabel(normalizedStatuses, normalizedTags);
        String baseFileName = slug(logicalFeed.name) + "-" + slug(filterLabel) + "-" + normalizedKind;

        try {
            if ("all".equals(normalizedKind)) {
                byte[] zip = exportCurrentPapersArchive(logicalFeed, filterLabel, papers);
                return Response.ok(zip, "application/zip")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + baseFileName + ".zip\"")
                        .build();
            }

            if ("csv".equals(normalizedFormat)) {
                String csv = renderExportCsv(papers);
                return Response.ok(csv, "text/csv; charset=UTF-8")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + baseFileName + ".csv\"")
                        .build();
            }

            String markdown = "notes".equals(normalizedKind)
                    ? renderNotesExportMarkdown(logicalFeed, filterLabel, papers)
                    : renderTabExportMarkdown(logicalFeed, filterLabel, papers);
            if ("md".equals(normalizedFormat)) {
                return Response.ok(markdown, "text/markdown; charset=UTF-8")
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + baseFileName + ".md\"")
                        .build();
            }

            byte[] converted = convertMarkdownWithPandoc(markdown, normalizedFormat);
            return Response.ok(converted, exportContentType(normalizedFormat))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + baseFileName + "." + normalizedFormat + "\"")
                    .build();
        } catch (IOException e) {
            Log.errorf(e, "Tab export failed for logicalFeedId=%d statuses=%s tags=%s kind=%s format=%s",
                    logicalFeed.id, normalizedStatuses, normalizedTags, normalizedKind, normalizedFormat);
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/admin/import")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importBackup(@RestForm("backupZip") FileUpload backupZip) {
        if (backupZip == null) {
            throw new WebApplicationException("Backup zip is required", Response.Status.BAD_REQUEST);
        }
        try (var inputStream = java.nio.file.Files.newInputStream(backupZip.uploadedFile())) {
            backupService.importZip(inputStream);
            return seeOther("/admin");
        } catch (IOException e) {
            throw new WebApplicationException("Failed to import backup", Response.Status.INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/logical-feeds")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createLogicalFeed(
            @RestForm("name") String name,
            @RestForm("description") String description,
            @RestForm("workflowStates") String workflowStates,
            @RestForm("publicReadable") String publicReadable,
            @RestForm("notifyOnNewRssPapers") String notifyOnNewRssPapers
    ) {
        try {
            LogicalFeed logicalFeed = new LogicalFeed();
            logicalFeed.name = name == null ? null : name.trim();
            logicalFeed.description = normalize(description);
            logicalFeed.workflowStates = normalizeWorkflowStates(workflowStates);
            logicalFeed.owner = requireCurrentUser();
            logicalFeed.publicReadable = "on".equalsIgnoreCase(publicReadable);
            logicalFeed.notifyOnNewRssPapers = !"off".equalsIgnoreCase(notifyOnNewRssPapers);
            ensurePublicShareToken(logicalFeed);
            logicalFeedRepository.persist(logicalFeed);
            return seeOther("/admin");
        } catch (WebApplicationException e) {
            return rethrowOrPlainText(e);
        }
    }

    @POST
    @Path("/logical-feeds/{id}/update")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateLogicalFeed(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("name") String name,
            @RestForm("description") String description,
            @RestForm("workflowStates") String workflowStates,
            @RestForm("publicReadable") String publicReadable,
            @RestForm("notifyOnNewRssPapers") String notifyOnNewRssPapers
    ) {
        try {
            LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(id, requireCurrentUser());
            logicalFeed.name = name == null ? null : name.trim();
            logicalFeed.description = normalize(description);
            logicalFeed.workflowStates = normalizeWorkflowStates(workflowStates);
            logicalFeed.publicReadable = "on".equalsIgnoreCase(publicReadable);
            logicalFeed.notifyOnNewRssPapers = !"off".equalsIgnoreCase(notifyOnNewRssPapers);
            ensurePublicShareToken(logicalFeed);
            return seeOther("/admin");
        } catch (WebApplicationException e) {
            return rethrowOrPlainText(e);
        }
    }

    @POST
    @Path("/logical-feeds/{id}/github/create")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createLogicalFeedGithubRepository(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("repoName") String repoName,
            @RestForm("branch") String branch,
            @RestForm("privateRepo") String privateRepo
    ) {
        AppUser currentUser = requireCurrentUser();
        LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(id, currentUser);
        try {
            githubRepositoryService.createRepositoryForLogicalFeed(
                    currentUser,
                    logicalFeed,
                    repoName,
                    privateRepo != null,
                    branch);
            paperGitSyncService.syncLogicalFeed(logicalFeed);
            return seeOther("/admin?info=" + urlEncode("Created GitHub repository for paper feed: " + logicalFeed.name) + "#feeds");
        } catch (IllegalArgumentException | IOException e) {
            return seeOther("/admin?error=" + urlEncode(e.getMessage()) + "#feeds");
        }
    }

    @POST
    @Path("/logical-feeds/{id}/delete")
    @Transactional
    public Response deleteLogicalFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        LogicalFeed logicalFeed = logicalFeedAccessService.requireAdminLogicalFeed(id, requireCurrentUser());
        if (logicalFeed != null) {
            reviewService.deleteReviewsForLogicalFeed(logicalFeed);
            logicalFeed.delete();
        }
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createFeed(
            @RestForm("name") String name,
            @RestForm("url") String url,
            @RestForm("pollIntervalMinutes") Integer pollIntervalMinutes,
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("defaultPaperStatus") String defaultPaperStatus
    ) {
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        Feed feed = new Feed();
        feed.name = name == null ? null : name.trim();
        feed.url = url == null ? null : url.trim();
        feed.pollIntervalMinutes = pollIntervalMinutes == null ? 60 : pollIntervalMinutes;
        feed.logicalFeed = logicalFeed;
        feed.defaultPaperStatus = normalizeFeedDefaultStatus(logicalFeed, defaultPaperStatus);
        feedRepository.persist(feed);
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/update")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateFeed(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("name") String name,
            @RestForm("url") String url,
            @RestForm("pollIntervalMinutes") Integer pollIntervalMinutes,
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("defaultPaperStatus") String defaultPaperStatus
    ) {
        Feed feed = logicalFeedAccessService.requireAdminFeed(id, requireCurrentUser());
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        feed.name = name == null ? null : name.trim();
        feed.url = url == null ? null : url.trim();
        feed.pollIntervalMinutes = pollIntervalMinutes == null ? 60 : pollIntervalMinutes;
        LogicalFeed previousLogicalFeed = feed.logicalFeed;
        feed.logicalFeed = logicalFeed;
        feed.defaultPaperStatus = normalizeFeedDefaultStatus(logicalFeed, defaultPaperStatus);
        if (previousLogicalFeed != null) {
            paperGitSyncService.syncLogicalFeed(previousLogicalFeed);
        }
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/poll")
    public Response pollFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        logicalFeedAccessService.requireAdminFeed(id, requireCurrentUser());
        feedPollingService.pollFeedById(id);
        Feed feed = feedRepository.findById(id);
        if (feed != null) {
            paperGitSyncService.syncLogicalFeed(feed.logicalFeed);
        }
        return seeOther("/admin");
    }

    @POST
    @Path("/feeds/{id}/delete")
    @Transactional
    public Response deleteFeed(@jakarta.ws.rs.PathParam("id") Long id) {
        Feed feed = logicalFeedAccessService.requireAdminFeed(id, requireCurrentUser());
        if (feed != null) {
            feed.delete();
        }
        return seeOther("/admin");
    }

    @POST
    @Path("/papers/upload")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPaper(
            @RestForm("pdf") FileUpload pdf,
            @RestForm("logicalFeedId") Long logicalFeedId,
            @RestForm("title") String title,
            @RestForm("authors") String authors,
            @RestForm("publisher") String publisher,
            @RestForm("publishedOn") LocalDate publishedOn,
            @RestForm("summary") String summary,
            @RestForm("sourceLink") String sourceLink
    ) {
        LogicalFeed logicalFeed = requireLogicalFeed(logicalFeedId);
        PaperStorageService.StoredPdf storedPdf;
        try {
            storedPdf = paperStorageService.storePdf(pdf);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to store PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }

        Paper paper = new Paper();
        paper.title = normalize(title);
        if (paper.title == null) {
            paper.title = stripPdfSuffix(storedPdf.originalFileName());
        }
        paper.sourceLink = normalize(sourceLink);
        if (paper.sourceLink == null) {
            paper.sourceLink = "upload:" + UUID.randomUUID();
        }
        paper.summary = normalize(summary);
        paper.authors = normalize(authors);
        paper.publisher = normalize(publisher);
        paper.publishedOn = publishedOn;
        paper.status = logicalFeed.initialPaperStatus();
        paper.discoveredAt = Instant.now();
        paper.feed = getOrCreateManualUploadFeed(logicalFeed);
        paper.logicalFeed = logicalFeed;
        paper.uploadedPdfPath = storedPdf.storedPath();
        paper.uploadedPdfFileName = storedPdf.originalFileName();
        paperRepository.persist(paper);
        paperEventService.log(paper, "PDF_UPLOADED", "Uploaded from admin");
        paperGitSyncService.syncLogicalFeed(logicalFeed);
        return seeOther("/admin");
    }

    @GET
    @Path("/papers/{id}/pdf")
    @Produces("application/pdf")
    public Response downloadPaperPdf(
            @jakarta.ws.rs.PathParam("id") Long id,
            @DefaultValue("inline") @jakarta.ws.rs.QueryParam("disposition") String disposition
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null || paper.uploadedPdfPath == null) {
            throw new NotFoundException();
        }
        AppUser currentUser = currentUserContext.get().user();
        if (!logicalFeedAccessService.canRead(paper.logicalFeed, currentUser)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        java.nio.file.Path pdfPath = paperStorageService.resolve(paper.uploadedPdfPath);
        if (!Files.exists(pdfPath)) {
            throw new NotFoundException();
        }

        String fileName = paper.uploadedPdfFileName == null ? "paper.pdf" : paper.uploadedPdfFileName;
        try {
            Response.ResponseBuilder response = Response.ok(Files.newInputStream(pdfPath), "application/pdf");
            if ("attachment".equalsIgnoreCase(disposition)) {
                response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            } else {
                response.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
            }
            return response.build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to read PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("/papers/{id}/pdf")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadPaperPdf(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("pdf") FileUpload pdf
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        PaperStorageService.StoredPdf storedPdf;
        try {
            storedPdf = paperStorageService.storePdf(pdf);
            paperStorageService.deleteIfExists(paper.uploadedPdfPath);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to store PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }

        paper.uploadedPdfPath = storedPdf.storedPath();
        paper.uploadedPdfFileName = storedPdf.originalFileName();
        paperEventService.log(paper, "PDF_UPLOADED", "Attached PDF " + storedPdf.originalFileName());
        paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/pdf/import-supported")
    @Transactional
    public Response importSupportedPaperPdf(@jakarta.ws.rs.PathParam("id") Long id) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        try {
            PaperPdfImportService.ImportedPdf importedPdf = paperPdfImportService.importSupportedPdf(paper);
            paperStorageService.deleteIfExists(paper.uploadedPdfPath);
            paper.uploadedPdfPath = importedPdf.storedPdf().storedPath();
            paper.uploadedPdfFileName = importedPdf.storedPdf().originalFileName();
            paperEventService.log(paper, "PDF_UPLOADED", "Imported PDF from " + importedPdf.sourceUrl());
            paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WebApplicationException("Failed to import remote PDF", Response.Status.BAD_GATEWAY);
        }
    }

    @POST
    @Path("/papers/{id}/pdf/delete")
    @Transactional
    public Response deletePaperPdf(@jakarta.ws.rs.PathParam("id") Long id) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        try {
            paperStorageService.deleteIfExists(paper.uploadedPdfPath);
        } catch (IOException e) {
            throw new WebApplicationException("Failed to delete PDF", Response.Status.INTERNAL_SERVER_ERROR);
        }
        paper.uploadedPdfPath = null;
        paper.uploadedPdfFileName = null;
        paperEventService.log(paper, "PDF_UPLOADED", "Removed attached PDF");
        paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/viewed")
    @Transactional
    public Response markPaperViewed(@jakarta.ws.rs.PathParam("id") Long id) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        AppUser currentUser = currentUserContext.get().user();
        if (!logicalFeedAccessService.canRead(paper.logicalFeed, currentUser)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (!paperEventRepository.existsByPaperIdAndType(id, "NOTE_VIEWED")) {
            paperEventService.log(paper, "NOTE_VIEWED", "Opened in note view");
        }
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/share-link")
    @Transactional
    @Produces(MediaType.TEXT_PLAIN)
    public Response createPaperShareLink(@jakarta.ws.rs.PathParam("id") Long id) {
        Paper paper = paperRepository.findForReader(id).orElseThrow(NotFoundException::new);
        AppUser currentUser = requireCurrentUser();
        if (!logicalFeedAccessService.canRead(paper.logicalFeed, currentUser)) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (!paper.logicalFeed.publicReadable) {
            throw new WebApplicationException("Paper feed must be public to create an anonymous share link",
                    Response.Status.BAD_REQUEST);
        }
        if (paper.shareToken == null || paper.shareToken.isBlank()) {
            paper.shareToken = UUID.randomUUID().toString();
        }
        return Response.ok(normalizeBaseUrl() + "/share/paper/" + paper.shareToken, MediaType.TEXT_PLAIN).build();
    }

    @POST
    @Path("/papers/{id}/notes")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updatePaperNotes(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("notes") String notes
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        paper.notes = notes;
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/tags")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updatePaperTags(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("tags") String tags
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        paper.tags = Paper.normalizeTags(tags);
        return Response.noContent().build();
    }

    @POST
    @Path("/papers/{id}/status")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updatePaperStatus(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("status") String status
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        try {
            String normalizedStatus = normalizePaperStatus(status);
            if (!logicalFeedWorkflow(logicalFeedRepository.findById(paper.logicalFeed.id)).contains(normalizedStatus)) {
                throw new IllegalArgumentException();
            }
            String previousStatus = paper.status;
            if (normalizedStatus.equals(previousStatus)) {
                return Response.noContent().build();
            }
            paper.status = normalizedStatus;
            paperEventService.log(paper, "STATE_CHANGED", previousStatus + " -> " + normalizedStatus);
            paperGitSyncService.syncLogicalFeed(paper.logicalFeed);
        } catch (Exception e) {
            throw new WebApplicationException("Invalid paper status", Response.Status.BAD_REQUEST);
        }
        return Response.noContent().build();
    }

    private void populatePaperBadges(List<Paper> papers) {
        Instant newThreshold = Instant.now().minus(7, ChronoUnit.DAYS);
        List<Long> paperIds = papers.stream().map((paper) -> paper.id).toList();
        Map<Long, List<top.nextnet.paper.monitor.model.PaperEvent>> eventsByPaperId = paperEventRepository.findByPaperIds(paperIds);
        for (Paper paper : papers) {
            paper.newBadge = paper.discoveredAt != null && !paper.discoveredAt.isBefore(newThreshold);
            boolean noteViewed = false;
            boolean userStateChanged = false;
            for (top.nextnet.paper.monitor.model.PaperEvent event : eventsByPaperId.getOrDefault(paper.id, List.of())) {
                if ("NOTE_VIEWED".equals(event.type)) {
                    noteViewed = true;
                }
                if ("STATE_CHANGED".equals(event.type) && (event.details == null || !event.details.contains("(git)"))) {
                    userStateChanged = true;
                }
            }
            paper.freshBadge = paper.newBadge
                    && "NEW".equals(paper.topLevelStatus())
                    && !noteViewed
                    && !userStateChanged;
        }
    }

    @POST
    @Path("/papers/{id}/notes/images")
    @Transactional
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadNoteImage(
            @jakarta.ws.rs.PathParam("id") Long id,
            @RestForm("image") FileUpload image
    ) {
        Paper paper = paperRepository.findById(id);
        if (paper == null) {
            throw new NotFoundException();
        }
        if (!logicalFeedAccessService.canAdmin(paper.logicalFeed, requireCurrentUser())) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }

        try {
            PaperStorageService.StoredAsset storedAsset = paperStorageService.storeImage(image);
            String assetPath = "/assets/" + storedAsset.storedPath();
            return Response.ok(assetPath, MediaType.TEXT_PLAIN).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (Exception e) {
            throw new WebApplicationException("Failed to store image", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("/assets/{name}")
    public Response getAsset(@jakarta.ws.rs.PathParam("name") String name) {
        java.nio.file.Path assetPath = paperStorageService.resolve(name);
        if (!Files.exists(assetPath)) {
            throw new NotFoundException();
        }

        try {
            String contentType = Files.probeContentType(assetPath);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return Response.ok(Files.newInputStream(assetPath), contentType).build();
        } catch (IOException e) {
            throw new WebApplicationException("Failed to read asset", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private Response seeOther(String location) {
        return Response.status(Response.Status.SEE_OTHER)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    private Response rethrowOrPlainText(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response == null || response.getStatus() >= 500) {
            throw exception;
        }
        String message = exception.getMessage();
        return Response.status(response.getStatus())
                .type(MediaType.TEXT_PLAIN)
                .entity(message == null ? "" : message)
                .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeRequired(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new WebApplicationException(message, Response.Status.BAD_REQUEST);
        }
        return normalized;
    }

    private Double normalizeSpeedMultiplier(Double speedMultiplier) {
        if (speedMultiplier == null) {
            return 1.1d;
        }
        if (speedMultiplier != 1.1d
                && speedMultiplier != 1.2d
                && speedMultiplier != 1.3d
                && speedMultiplier != 1.5d) {
            throw new WebApplicationException("Invalid TTS speed multiplier", Response.Status.BAD_REQUEST);
        }
        return speedMultiplier;
    }

    private UserSettings getOrCreateUserSettings() {
        return authService.ensureSettings(requireCurrentUser());
    }

    private String normalizeWorkflowStates(String value) {
        try {
            return WorkflowStateConfig.parse(value).toYaml();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    private String normalizePaperStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException();
        }
        String trimmed = status.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException();
        }
        if (!trimmed.contains("/")) {
            return WorkflowStateConfig.normalizeStateSegment(trimmed);
        }
        String[] segments = trimmed.split("/");
        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : segments) {
            normalizedSegments.add(WorkflowStateConfig.normalizeStateSegment(segment));
        }
        return String.join("/", normalizedSegments);
    }

    private List<String> logicalFeedWorkflow(LogicalFeed logicalFeed) {
        if (logicalFeed == null) {
            return List.of();
        }
        return logicalFeed.workflowStateList();
    }

    private LogicalFeed requireLogicalFeed(Long logicalFeedId) {
        if (logicalFeedId == null) {
            throw new WebApplicationException("logicalFeedId is required", Response.Status.BAD_REQUEST);
        }
        try {
            return logicalFeedAccessService.requireAdminLogicalFeed(logicalFeedId, requireCurrentUser());
        } catch (NotFoundException e) {
            throw new WebApplicationException("logicalFeedId does not reference an existing logical feed",
                    Response.Status.BAD_REQUEST);
        } catch (jakarta.ws.rs.ForbiddenException e) {
            throw new WebApplicationException("You do not have admin access to this logical feed",
                    Response.Status.FORBIDDEN);
        }
    }

    private Feed getOrCreateManualUploadFeed(LogicalFeed logicalFeed) {
        String syntheticUrl = "upload://logical-feed/" + logicalFeed.id;
        return feedRepository.findByUrl(syntheticUrl).orElseGet(() -> {
            Feed feed = new Feed();
            feed.name = "Manual upload: " + logicalFeed.name;
            feed.url = syntheticUrl;
            feed.pollIntervalMinutes = 525600;
            feed.logicalFeed = logicalFeed;
            feedRepository.persist(feed);
            return feed;
        });
    }

    private Feed getOrCreateDoiImportFeed(LogicalFeed logicalFeed) {
        String syntheticUrl = "doi://logical-feed/" + logicalFeed.id;
        return feedRepository.findByUrl(syntheticUrl).orElseGet(() -> {
            Feed feed = new Feed();
            feed.name = "DOI import: " + logicalFeed.name;
            feed.url = syntheticUrl;
            feed.pollIntervalMinutes = 525600;
            feed.logicalFeed = logicalFeed;
            feedRepository.persist(feed);
            return feed;
        });
    }

    private LogicalFeed createQuickSetupPaperFeed(AppUser owner, String paperFeedName, String rssUrl) {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = paperFeedName;
        logicalFeed.description = "Created from quick setup";
        logicalFeed.workflowStates = """
                - DISCARDED
                - NEW
                - TODO
                - DONE
                """;
        logicalFeed.owner = owner;
        logicalFeed.publicReadable = false;
        logicalFeedRepository.persist(logicalFeed);

        Feed feed = new Feed();
        feed.name = paperFeedName + " RSS";
        feed.url = rssUrl;
        feed.pollIntervalMinutes = 60;
        feed.defaultPaperStatus = "NEW";
        feed.logicalFeed = logicalFeed;
        feedRepository.persist(feed);

        paperGitSyncService.syncLogicalFeed(logicalFeed);
        feedPollingService.pollFeedById(feed.id);
        return logicalFeed;
    }

    private String stripPdfSuffix(String fileName) {
        if (fileName == null) {
            return "Uploaded paper";
        }
        return fileName.toLowerCase().endsWith(".pdf")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    private String normalizeFeedDefaultStatus(LogicalFeed logicalFeed, String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return null;
        }
        String normalizedStatus = normalizePaperStatus(normalized);
        if (!logicalFeedWorkflow(logicalFeed).contains(normalizedStatus)) {
            throw new WebApplicationException("defaultPaperStatus must belong to the selected logical feed workflow",
                    Response.Status.BAD_REQUEST);
        }
        return normalizedStatus;
    }

    private String renderTabExportMarkdown(LogicalFeed logicalFeed, String filterLabel, List<Paper> papers) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(escapeMarkdownText(logicalFeed.name)).append("\n\n");
        markdown.append("* export date: ").append(ZonedDateTime.now()).append("\n");
        markdown.append("* number of papers: ").append(papers.size()).append("\n");
        markdown.append("* filter: ").append(escapeMarkdownText(filterLabel)).append("\n\n");

        for (Paper paper : papers) {
            markdown.append("# ").append(escapeMarkdownText(paper.title)).append("\n\n");
            if (paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank()) {
                markdown.append("* [pdf version](")
                        .append(normalizeBaseUrl())
                        .append("/papers/")
                        .append(paper.id)
                        .append("/pdf?disposition=attachment")
                        .append(")\n");
            }
            markdown.append("* authors: ").append(escapeMarkdownText(stringOrDefault(paper.authors, "Unknown authors"))).append("\n");
            markdown.append("* publication date: ").append(escapeMarkdownText(stringOrDefault(paper.publishedOn, "unknown"))).append("\n");
            markdown.append("* venue: ").append(escapeMarkdownText(stringOrDefault(paper.publisher, "Unknown venue"))).append("\n");
            if (paper.tags != null && !paper.tags.isBlank()) {
                markdown.append("* tags: ").append(escapeMarkdownText(String.join(", ", paper.tagList()))).append("\n");
            }
            if (paper.sourceLink != null && !paper.sourceLink.startsWith("upload:")) {
                markdown.append("* source: ").append(paper.sourceLink).append("\n");
            }
            if (paper.openAccessLink != null && !paper.openAccessLink.isBlank()) {
                markdown.append("* open access: ").append(paper.openAccessLink).append("\n");
            }
            markdown.append("\n## abstract\n\n");
            markdown.append(stringOrDefault(paper.summary, "No abstract available.")).append("\n\n");
            markdown.append("## Notes\n\n");
            markdown.append(indentMarkdownHeadings(stringOrDefault(paper.notes, "No notes yet."))).append("\n\n");
        }
        return markdown.toString().trim() + "\n";
    }

    private String renderNotesExportMarkdown(LogicalFeed logicalFeed, String filterLabel, List<Paper> papers) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(escapeMarkdownText(logicalFeed.name)).append(" Notes\n\n");
        markdown.append("* export date: ").append(ZonedDateTime.now()).append("\n");
        markdown.append("* number of papers: ").append(papers.size()).append("\n");
        markdown.append("* filter: ").append(escapeMarkdownText(filterLabel)).append("\n\n");
        for (Paper paper : papers) {
            markdown.append("## ").append(escapeMarkdownText(paper.title)).append("\n\n");
            markdown.append("* authors: ").append(escapeMarkdownText(stringOrDefault(paper.authors, "Unknown authors"))).append("\n");
            markdown.append("* publication date: ").append(escapeMarkdownText(stringOrDefault(paper.publishedOn, "unknown"))).append("\n");
            markdown.append("* venue: ").append(escapeMarkdownText(stringOrDefault(paper.publisher, "Unknown venue"))).append("\n\n");
            markdown.append("### Notes\n\n");
            markdown.append(indentMarkdownHeadings(stringOrDefault(paper.notes, "No notes yet."))).append("\n\n");
        }
        return markdown.toString().trim() + "\n";
    }

    private String renderExportCsv(List<Paper> papers) {
        StringBuilder csv = new StringBuilder();
        csv.append("title,authors,publication_date,venue,status,discovered_at,source_link,open_access_link,feed,paper_feed,tags,has_pdf\n");
        for (Paper paper : papers) {
            csv.append(csvField(paper.title)).append(',')
                    .append(csvField(paper.authors)).append(',')
                    .append(csvField(paper.publishedOn)).append(',')
                    .append(csvField(paper.publisher)).append(',')
                    .append(csvField(paper.status)).append(',')
                    .append(csvField(paper.discoveredAt)).append(',')
                    .append(csvField(paper.sourceLink)).append(',')
                    .append(csvField(paper.openAccessLink)).append(',')
                    .append(csvField(paper.feed != null ? paper.feed.name : null)).append(',')
                    .append(csvField(paper.logicalFeed != null ? paper.logicalFeed.name : null)).append(',')
                    .append(csvField(paper.tagsToken())).append(',')
                    .append(csvField(Boolean.toString(paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank())))
                    .append('\n');
        }
        return csv.toString();
    }

    private String indentMarkdownHeadings(String markdown) {
        return markdown.lines()
                .map((line) -> {
                    int depth = 0;
                    while (depth < line.length() && line.charAt(depth) == '#') {
                        depth++;
                    }
                    if (depth == 0 || depth >= line.length() || !Character.isWhitespace(line.charAt(depth))) {
                        return line;
                    }
                    return "#".repeat(Math.min(6, depth + 2)) + line.substring(depth);
                })
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String normalizeBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String normalizeExportKind(String kind) {
        String normalized = kind == null ? "report" : kind.trim().toLowerCase();
        if (!List.of("report", "notes", "all").contains(normalized)) {
            throw new WebApplicationException("Unsupported export kind", Response.Status.BAD_REQUEST);
        }
        return normalized;
    }

    private String normalizeExportFormat(String format, String kind) {
        String normalized = format == null ? "md" : format.trim().toLowerCase();
        List<String> allowed = "all".equals(kind)
                ? List.of("zip")
                : "notes".equals(kind)
                    ? List.of("md", "pdf", "docx")
                    : List.of("md", "pdf", "docx", "csv");
        if (!allowed.contains(normalized)) {
            throw new WebApplicationException("Unsupported export format", Response.Status.BAD_REQUEST);
        }
        return normalized;
    }

    private String exportContentType(String format) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "csv" -> "text/csv; charset=UTF-8";
            case "zip" -> "application/zip";
            default -> "text/markdown; charset=UTF-8";
        };
    }

    private byte[] convertMarkdownWithPandoc(String markdown, String format) throws IOException {
        return markdownConversionService.convertWithPandoc(markdown, format);
    }

    private String escapeMarkdownText(Object value) {
        String text = String.valueOf(value == null ? "" : value).replace('\n', ' ').trim();
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("#", "\\#");
    }

    private String slug(String value) {
        String normalized = value == null ? "export" : value.toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "export" : normalized;
    }

    private String stringOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private String normalizeDoiInput(String doi) {
        return normalizeDoiInputs(doi).get(0);
    }

    private List<String> normalizeDoiInputs(String doi) {
        String normalized = normalizeRequired(doi, "DOI is required");
        String[] parts = normalized.split("[,\\s]+");
        Set<String> normalizedDois = new LinkedHashSet<>();
        for (String part : parts) {
            String candidate = normalize(part);
            if (candidate != null) {
                normalizedDois.add(candidate);
            }
        }
        if (normalizedDois.isEmpty()) {
            throw new WebApplicationException("DOI is required", Response.Status.BAD_REQUEST);
        }
        return List.copyOf(normalizedDois);
    }

    private String normalizedDoiSourceLink(String normalizedDoi) {
        return normalizedDoi.startsWith("http://") || normalizedDoi.startsWith("https://")
                ? normalizedDoi
                : "https://doi.org/" + normalizedDoi.replaceFirst("^(?i)doi:", "");
    }

    private DoiMetadataService.DoiMetadata fetchDoiMetadata(String normalizedDoi) {
        try {
            return doiMetadataService.fetch(normalizedDoi
                    .replaceFirst("^(?i)https?://doi.org/", "")
                    .replaceFirst("^(?i)doi:", ""));
        } catch (IOException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_GATEWAY);
        }
    }

    private String buildDoiImportInfoMessage(List<String> importedDois, List<String> existingDois) {
        List<String> parts = new ArrayList<>();
        if (!importedDois.isEmpty()) {
            parts.add("Imported DOIs: " + String.join(", ", importedDois));
        }
        if (!existingDois.isEmpty()) {
            parts.add("Already present DOIs: " + String.join(", ", existingDois));
        }
        return parts.isEmpty() ? null : String.join(" | ", parts);
    }

    private String buildDoiImportErrorMessage(List<String> failedDois) {
        return failedDois.isEmpty() ? null : "Failed DOIs: " + String.join("; ", failedDois);
    }

    private String formatDoiFailure(String doi, WebApplicationException exception) {
        String message = normalize(exception.getMessage());
        if (message == null && exception.getResponse() != null) {
            message = normalize(exception.getResponse().getStatusInfo().getReasonPhrase());
        }
        return message == null ? doi : doi + " (" + message + ")";
    }

    private String singleDoiRedirect(Long logicalFeedId, Paper targetPaper) {
        if (targetPaper != null) {
            return "/?paperId=" + targetPaper.id + "&logicalFeedId=" + logicalFeedId;
        }
        return "/?logicalFeedId=" + logicalFeedId;
    }

    private String appendQueryParam(String location, String key, String value) {
        return location + (location.contains("?") ? "&" : "?") + key + "=" + urlEncode(value);
    }

    private Paper importPaperFromNormalizedDoi(LogicalFeed logicalFeed, String normalizedDoi, FileUpload pdf, boolean attachPdf) {
        String sourceLink = normalizedDoiSourceLink(normalizedDoi);
        Paper existing = paperRepository.findByLogicalFeedAndSourceLink(logicalFeed, sourceLink).orElse(null);
        if (existing != null) {
            return null;
        }

        DoiMetadataService.DoiMetadata metadata = fetchDoiMetadata(normalizedDoi);
        Paper paper = new Paper();
        paper.title = normalize(metadata.title());
        if (paper.title == null) {
            throw new WebApplicationException("DOI metadata did not return a title", Response.Status.BAD_GATEWAY);
        }
        paper.sourceLink = metadata.doiUrl() == null ? sourceLink : metadata.doiUrl();
        paper.openAccessLink = normalize(metadata.openAccessUrl());
        paper.summary = normalize(metadata.summary());
        paper.authors = normalize(metadata.authors());
        paper.publisher = normalize(metadata.publisher());
        paper.publishedOn = metadata.publishedOn();
        paper.status = logicalFeed.initialPaperStatus();
        paper.discoveredAt = Instant.now();
        paper.feed = getOrCreateDoiImportFeed(logicalFeed);
        paper.logicalFeed = logicalFeed;
        if (attachPdf && pdf != null && pdf.fileName() != null && !pdf.fileName().isBlank()) {
            PaperStorageService.StoredPdf storedPdf;
            try {
                storedPdf = paperStorageService.storePdf(pdf);
            } catch (IllegalArgumentException e) {
                throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
            } catch (Exception e) {
                throw new WebApplicationException("Failed to store PDF", Response.Status.INTERNAL_SERVER_ERROR);
            }
            paper.uploadedPdfPath = storedPdf.storedPath();
            paper.uploadedPdfFileName = storedPdf.originalFileName();
        }
        paperRepository.persist(paper);
        paperEventService.log(paper, "FETCH", "Imported from DOI " + normalizedDoi);
        if (paper.uploadedPdfFileName != null) {
            paperEventService.log(paper, "PDF_UPLOADED", "Attached PDF " + paper.uploadedPdfFileName);
        }
        return paper;
    }

    private String csvField(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private List<String> normalizeNullableExportStatuses(LogicalFeed logicalFeed, String statuses, String fallbackStatus) {
        String raw = normalize(statuses);
        if (raw == null) {
            String fallback = normalize(fallbackStatus);
            if (fallback == null) {
                return List.of();
            }
            raw = fallback;
        }
        List<String> normalizedStatuses = new ArrayList<>();
        for (String part : raw.split(",")) {
            String normalized = normalize(part);
            if (normalized == null) {
                continue;
            }
            String normalizedStatus = normalizePaperStatus(normalized);
            if (!logicalFeed.workflowStateList().contains(normalizedStatus)
                    && !logicalFeed.topLevelWorkflowStateList().contains(normalizedStatus)) {
                throw new WebApplicationException("status must belong to the selected logical feed workflow",
                        Response.Status.BAD_REQUEST);
            }
            if (!normalizedStatuses.contains(normalizedStatus)) {
                normalizedStatuses.add(normalizedStatus);
            }
        }
        return normalizedStatuses;
    }

    private List<String> normalizeExportTags(String tags) {
        String normalized = normalize(tags);
        if (normalized == null) {
            return List.of();
        }
        return List.of(normalized.split(","))
                .stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.startsWith("state:")
                        ? "state:" + normalizePaperStatus(value.substring("state:".length()))
                        : value)
                .distinct()
                .sorted()
                .toList();
    }

    private List<Paper> selectPapersForExport(LogicalFeed logicalFeed, List<String> statuses, List<String> tags) {
        List<Paper> papers = paperRepository.findAllForExport(logicalFeed);
        if (!tags.isEmpty()) {
            return papers.stream()
                    .filter(paper -> exportTagsMatch(paper, tags))
                    .sorted(Comparator.comparing((Paper paper) -> paper.publishedOn, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(paper -> paper.discoveredAt, Comparator.reverseOrder()))
                    .toList();
        }
        if (statuses == null || statuses.isEmpty()) {
            return papers;
        }
        return papers.stream()
                .filter(paper -> paper.status != null
                        && statuses.stream().anyMatch((status) -> paper.status.equals(status) || paper.status.startsWith(status + "/")))
                .toList();
    }

    private boolean exportTagsMatch(Paper paper, List<String> tags) {
        Set<String> keys = new HashSet<>(paper.tagList());
        keys.add("state:" + paper.topLevelStatus());
        if (paper.uploadedPdfPath != null && !paper.uploadedPdfPath.isBlank()) {
            keys.add("has-pdf");
        }
        return tags.stream().allMatch(keys::contains);
    }

    private String exportFilterLabel(List<String> statuses, List<String> tags) {
        if (!tags.isEmpty()) {
            return "tags-" + String.join("-", tags);
        }
        if (statuses == null || statuses.isEmpty()) {
            return "all-papers";
        }
        return statuses.stream()
                .map(String::toLowerCase)
                .sorted()
                .collect(java.util.stream.Collectors.joining("-"));
    }

    private byte[] exportCurrentPapersArchive(LogicalFeed logicalFeed, String filterLabel, List<Paper> papers) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            String baseName = slug(logicalFeed.name) + "-" + slug(filterLabel);
            String reportMarkdown = renderTabExportMarkdown(logicalFeed, filterLabel, papers);
            addStoredZipEntry(zip, baseName + "/reports/" + baseName + "-report.md", reportMarkdown.getBytes(StandardCharsets.UTF_8));
            addStoredZipEntry(zip, baseName + "/reports/" + baseName + "-report.pdf", convertMarkdownWithPandoc(reportMarkdown, "pdf"));
            addStoredZipEntry(zip, baseName + "/reports/" + baseName + "-report.docx", convertMarkdownWithPandoc(reportMarkdown, "docx"));
            addStoredZipEntry(zip, baseName + "/reports/" + baseName + "-report.csv", renderExportCsv(papers).getBytes(StandardCharsets.UTF_8));

            for (Paper paper : papers) {
                if (paper.uploadedPdfPath == null || paper.uploadedPdfPath.isBlank()) {
                    continue;
                }
                try (var input = paperStorageService.open(paper.uploadedPdfPath)) {
                    addStoredZipEntry(zip,
                            baseName + "/papers/" + slug(paper.title) + "-" + paper.id + ".pdf",
                            input.readAllBytes());
                }
            }
        }
        return output.toByteArray();
    }

    private void addStoredZipEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        entry.setCrc(crc32.getValue());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private AppUser requireCurrentUser() {
        AppUser user = currentUserContext.get().user();
        if (user == null) {
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }
        return user;
    }

    private Response loginResponse(AppUser user, String returnTo) {
        var session = authService.createSession(user);
        NewCookie cookie = authService.loginCookie(session.token);
        return Response.status(Response.Status.SEE_OTHER)
                .header(HttpHeaders.LOCATION, safeReturnTo(returnTo))
                .cookie(cookie)
                .build();
    }

    private String safeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/";
        }
        return returnTo;
    }

    private void populatePaperCounts(List<LogicalFeed> logicalFeeds) {
        Map<Long, Map<String, Long>> countsByLogicalFeed = paperRepository.countByLogicalFeedAndStatus();
        for (LogicalFeed logicalFeed : logicalFeeds) {
            logicalFeed.paperCountsByState = new LinkedHashMap<>();
            Map<String, Long> exactCounts = countsByLogicalFeed.getOrDefault(logicalFeed.id, Map.of());
            for (String state : logicalFeed.topLevelWorkflowStateList()) {
                long count = exactCounts.entrySet().stream()
                        .filter((entry) -> entry.getKey().equals(state) || entry.getKey().startsWith(state + "/"))
                        .mapToLong(Map.Entry::getValue)
                        .sum();
                logicalFeed.paperCountsByState.put(state, count);
            }
            logicalFeed.totalPaperCount = logicalFeed.paperCountsByState.values().stream()
                    .mapToLong(Long::longValue)
                    .sum();
        }
    }

    private void populateLogicalFeedDashboardStats(List<LogicalFeed> logicalFeeds) {
        Map<Long, LogicalFeed> logicalFeedById = logicalFeeds.stream()
                .collect(java.util.stream.Collectors.toMap((logicalFeed) -> logicalFeed.id, (logicalFeed) -> logicalFeed));
        List<Paper> papers = paperRepository.findAllForLogicalFeedIds(new ArrayList<>(logicalFeedById.keySet()));
        Instant newThreshold = Instant.now().minus(7, ChronoUnit.DAYS);
        Map<Long, List<top.nextnet.paper.monitor.model.PaperEvent>> eventsByPaperId = paperEventRepository.findByPaperIds(
                papers.stream().map((paper) -> paper.id).toList());
        for (LogicalFeed logicalFeed : logicalFeeds) {
            logicalFeed.recentNewPaperCount = 0L;
        }
        for (Paper paper : papers) {
            if (paper.logicalFeed == null || paper.discoveredAt == null || paper.discoveredAt.isBefore(newThreshold)) {
                continue;
            }
            if (!"NEW".equals(paper.topLevelStatus())) {
                continue;
            }
            if (paper.feed == null || paper.feed.url == null
                    || paper.feed.url.startsWith("upload://")
                    || paper.feed.url.startsWith("doi://")) {
                continue;
            }
            boolean userStateChanged = false;
            for (top.nextnet.paper.monitor.model.PaperEvent event : eventsByPaperId.getOrDefault(paper.id, List.of())) {
                if ("STATE_CHANGED".equals(event.type) && (event.details == null || !event.details.contains("(git)"))) {
                    userStateChanged = true;
                    break;
                }
            }
            if (userStateChanged) {
                continue;
            }
            LogicalFeed logicalFeed = logicalFeedById.get(paper.logicalFeed.id);
            if (logicalFeed != null) {
                logicalFeed.recentNewPaperCount += 1L;
            }
        }
    }

    private void populateLogicalFeedAccessFlags(List<LogicalFeed> logicalFeeds, AppUser currentUser) {
        for (LogicalFeed logicalFeed : logicalFeeds) {
            logicalFeed.viewerCanAdmin = logicalFeedAccessService.canAdmin(logicalFeed, currentUser);
            logicalFeed.publicUrl = logicalFeed.publicReadable
                    ? normalizeBaseUrl() + "/share/feed/" + ensurePublicShareToken(logicalFeed)
                    : null;
        }
    }

    private Map<String, Object> paperBrowserItem(Paper paper) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", paper.id);
        item.put("logicalFeedName", paper.logicalFeed != null ? paper.logicalFeed.name : "");
        item.put("logicalFeedId", paper.logicalFeed != null ? paper.logicalFeed.id : null);
        item.put("paperStatus", paper.status != null ? paper.status : "NEW");
        item.put("paperTopStatus", paper.topLevelStatus());
        item.put("pdfUrl", paper.uploadedPdfPath != null ? "/papers/" + paper.id + "/pdf" : "");
        item.put("paperTitle", paper.title);
        item.put("paperAuthors", paper.authors != null ? paper.authors : "Unknown authors");
        item.put("paperPublishedOn", paper.publishedOn != null ? paper.publishedOn : "unknown");
        item.put("paperVenue", paper.publisher != null ? paper.publisher : "Unknown venue");
        item.put("paperFeedName", paper.feed != null ? paper.feed.name : "");
        item.put("paperSummary", paper.summary != null ? paper.summary : "");
        item.put("paperSourceLink", paper.sourceLink != null ? paper.sourceLink : "");
        item.put("paperOpenAccessLink", paper.openAccessLink != null ? paper.openAccessLink : "");
        item.put("paperTags", paper.tagsToken());
        item.put("paperCanEditTags", paper.viewerCanEdit);
        item.put("paperNotes", paper.notes != null ? paper.notes : "");
        item.put("paperIsNew", paper.newBadge);
        item.put("paperIsFresh", paper.freshBadge);
        return item;
    }

    private String ensurePublicShareToken(LogicalFeed logicalFeed) {
        if (logicalFeed == null || !logicalFeed.publicReadable) {
            return logicalFeed == null ? null : logicalFeed.publicShareToken;
        }
        if (logicalFeed.publicShareToken == null || logicalFeed.publicShareToken.isBlank()) {
            logicalFeed.publicShareToken = UUID.randomUUID().toString();
        }
        return logicalFeed.publicShareToken;
    }
}
