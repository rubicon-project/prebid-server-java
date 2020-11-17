package org.prebid.server.analytics.pubstack;

import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.prebid.server.analytics.AnalyticsReporter;
import org.prebid.server.analytics.model.AmpEvent;
import org.prebid.server.analytics.model.AuctionEvent;
import org.prebid.server.analytics.model.CookieSyncEvent;
import org.prebid.server.analytics.model.SetuidEvent;
import org.prebid.server.analytics.model.VideoEvent;
import org.prebid.server.analytics.pubstack.model.EventType;
import org.prebid.server.analytics.pubstack.model.PubstackAnalyticsProperties;
import org.prebid.server.analytics.pubstack.model.PubstackConfig;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.util.HttpUtil;
import org.prebid.server.vertx.Initializable;
import org.prebid.server.vertx.http.HttpClient;
import org.prebid.server.vertx.http.model.HttpClientResponse;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class PubstackAnalyticsReporter implements AnalyticsReporter, Initializable {

    private static final String EVENT_REPORT_ENDPOINT_PATH = "/intake";
    private static final Logger logger = LoggerFactory.getLogger(PubstackAnalyticsReporter.class);
    private static final Map<String, EventType> CLASS_TO_EVENT_TYPE;

    static {
        CLASS_TO_EVENT_TYPE = new HashMap<>();
        CLASS_TO_EVENT_TYPE.put(AuctionEvent.class.getName(), EventType.auction);
        CLASS_TO_EVENT_TYPE.put(AmpEvent.class.getName(), EventType.amp);
        CLASS_TO_EVENT_TYPE.put(VideoEvent.class.getName(), EventType.video);
        CLASS_TO_EVENT_TYPE.put(SetuidEvent.class.getName(), EventType.setuid);
        CLASS_TO_EVENT_TYPE.put(CookieSyncEvent.class.getName(), EventType.cookiesync);
    }

    private static final String CONFIG_URL_SUFFIX = "/bootstrap?scopeId=";

    private final long configurationRefreshDelay;
    private final long timeout;
    private final Map<EventType, PubstackEventHandler> eventHandlers;
    private final HttpClient httpClient;
    private final JacksonMapper jacksonMapper;
    private final Vertx vertx;
    private PubstackConfig pubstackConfig;

    public PubstackAnalyticsReporter(PubstackAnalyticsProperties pubstackAnalyticsProperties,
                                     Map<EventType, PubstackEventHandler> eventHandlers,
                                     HttpClient httpClient,
                                     JacksonMapper jacksonMapper,
                                     Vertx vertx) {
        this.configurationRefreshDelay =
                Objects.requireNonNull(pubstackAnalyticsProperties.getConfigurationRefreshDelayMs());
        this.timeout = Objects.requireNonNull(pubstackAnalyticsProperties.getTimeoutMs());
        this.eventHandlers = Objects.requireNonNull(eventHandlers);
        this.httpClient = Objects.requireNonNull(httpClient);
        this.jacksonMapper = Objects.requireNonNull(jacksonMapper);
        this.vertx = Objects.requireNonNull(vertx);

        this.pubstackConfig = PubstackConfig.of(pubstackAnalyticsProperties.getScopeId(),
                pubstackAnalyticsProperties.getEndpoint(), Collections.emptyMap());

    }

    public <T> void processEvent(T event) {
        final EventType eventType = CLASS_TO_EVENT_TYPE.get(event.getClass().getName());
        if (eventType != null) {
            eventHandlers.get(eventType).handle(event);
        }
    }

    @Override
    public void initialize() {
        vertx.setPeriodic(configurationRefreshDelay, id -> fetchRemoteConfig());
        fetchRemoteConfig();
    }

    public void shutdown() {
        eventHandlers.values().forEach(PubstackEventHandler::reportEvents);
    }

    private void fetchRemoteConfig() {
        logger.info("[pubstack] Updating config: {0}", pubstackConfig);
        httpClient.get(makeEventEndpointUrl(pubstackConfig.getEndpoint(), pubstackConfig.getScopeId()), timeout)
                .map(this::processRemoteConfigurationResponse)
                .setHandler(this::updateConfigsOnChange);
    }

    private PubstackConfig processRemoteConfigurationResponse(HttpClientResponse response) {
        final int statusCode = response.getStatusCode();
        if (statusCode != 200) {
            throw new PreBidException(String.format("[pubstack] Failed to fetch config, reason: HTTP status code %d",
                    statusCode));
        }
        final String body = response.getBody();
        try {
            return jacksonMapper.mapper().readValue(body, PubstackConfig.class);
        } catch (IOException e) {
            throw new PreBidException(String.format("[pubstack] Failed to fetch config, reason: failed to parse"
                    + " response: %s", body), e);
        }
    }

    private void updateConfigsOnChange(AsyncResult<PubstackConfig> asyncConfigResult) {
        if (asyncConfigResult.failed()) {
            logger.error("[pubstask] Fail to fetch remote configuration: {0}", asyncConfigResult.cause().getMessage());
        } else if (!Objects.equals(pubstackConfig, asyncConfigResult.result())) {
            final PubstackConfig pubstackConfig = asyncConfigResult.result();
            eventHandlers.values().forEach(PubstackEventHandler::reportEvents);
            this.pubstackConfig = pubstackConfig;
            updateHandlers(pubstackConfig);
        }
    }

    private void updateHandlers(PubstackConfig pubstackConfig) {
        final Map<EventType, Boolean> handlersEnabled = MapUtils.emptyIfNull(pubstackConfig.getFeatures());
        eventHandlers.forEach((eventType, eventHandler) -> eventHandler.updateConfig(
                BooleanUtils.toBooleanDefaultIfNull(handlersEnabled.get(eventType), false),
                makeEventHandlerEndpoint(pubstackConfig.getEndpoint(), eventType),
                pubstackConfig.getScopeId()));
    }

    private static String makeEventEndpointUrl(String endpoint, String scopeId) {
        try {
            return HttpUtil.validateUrl(endpoint + CONFIG_URL_SUFFIX + scopeId);
        } catch (IllegalArgumentException e) {
            final String message = String.format("[pubstack] Failed to create remote config server url for endpoint:"
                    + " %s", endpoint);
            logger.error(message);
            throw new PreBidException(message);
        }
    }

    private String makeEventHandlerEndpoint(String endpoint, EventType eventType) {
        try {
            return HttpUtil.validateUrl(endpoint + EVENT_REPORT_ENDPOINT_PATH + "/" + eventType.name());
        } catch (IllegalArgumentException e) {
            final String message = String.format("[pubstack] Failed to create event report url for endpoint: %s",
                    endpoint);
            logger.error(message);
            throw new PreBidException(message);
        }
    }
}
