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
     * Shared Netty HttpClient with extended timeouts.
     * Reused by both WebClient (reactive / sub-agents) and RestClient (Spring AI → OpenAI).
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

	@Bean
    public WebClient webClient(@Qualifier("webClientBuilder") WebClient.Builder builder) {
        return builder.build();
    }
	
	@Bean
    public WebClient.Builder webClientBuilder(HttpClient httpClient) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)) // 10 MB — large enough for image SSE payloads
                .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(Objects.requireNonNull(httpClient)))
                .exchangeStrategies(strategies);
    }

    /**
     * Custom RestClient.Builder with the same extended-timeout Netty client.
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
