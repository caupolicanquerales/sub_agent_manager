package com.capo.sub_agent_manager.configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Objects;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfiguration {

    /**
     * Netty HttpClient for outbound OpenAI / REST calls.
     * Has explicit read/write/response timeouts — appropriate for request-response calls.
     */
    @Bean
    public HttpClient httpClient() {
        ConnectionProvider provider = ConnectionProvider.builder("openai-pool")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(60))
                .maxLifeTime(Duration.ofSeconds(120))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();
        return HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000)
                .responseTimeout(Duration.ofSeconds(180))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(180, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(180, TimeUnit.SECONDS)));
    }

    /**
     * Dedicated Netty HttpClient for long-lived SSE streams to internal sub-agents.
     * NO ReadTimeoutHandler and NO responseTimeout: SSE connections go silent during
     * tool calls (e.g. image generation), so a channel-level read timeout would kill
     * perfectly valid streams. Timeout discipline is handled at the Reactor operator
     * level inside each service instead.
     */
    @Bean
    public HttpClient sseHttpClient() {
        ConnectionProvider provider = ConnectionProvider.builder("sse-agent-pool")
                .maxConnections(50)
                .maxIdleTime(Duration.ofMinutes(10))
                .maxLifeTime(Duration.ofMinutes(30))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();
        return HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000);
        // Intentionally no responseTimeout / ReadTimeoutHandler:
        // sub-agent SSE streams can be silent for minutes while tools run.
    }

	@Bean
    public WebClient webClient(@Qualifier("webClientBuilder") WebClient.Builder builder) {
        return builder.build();
    }
	
	@Bean
    public WebClient.Builder webClientBuilder(@Qualifier("sseHttpClient") HttpClient sseHttpClient) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10 MB — large enough for image SSE payloads
                .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(Objects.requireNonNull(sseHttpClient)))
                .exchangeStrategies(strategies);
    }

    /**
     * Custom RestClient.Builder with the extended-timeout Netty client.
     * Spring AI's OpenAiAutoConfiguration injects RestClient.Builder to build OpenAiApi,
     * so this bean overrides the Spring Boot default (allow-bean-definition-overriding=true).
     * Without this, Spring AI uses Netty's 30s default read timeout and times out on slow models.
     */
    @Bean
    @Primary
    public RestClient.Builder restClientBuilder(HttpClient httpClient) {
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(Objects.requireNonNull(httpClient));
        return RestClient.builder().requestFactory(factory);
    }
}
