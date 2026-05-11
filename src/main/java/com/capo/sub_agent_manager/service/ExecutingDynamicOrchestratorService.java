package com.capo.sub_agent_manager.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.capo.sub_agent_manager.configuration.AgentRegistry;
import com.capo.sub_agent_manager.configuration.AgentType;
import com.capo.sub_agent_manager.request.GenerationSyntheticDataRequest;
import com.capo.sub_agent_manager.request.SubAgentRequest;
import com.capo.sub_agent_manager.response.DataMessage;
import com.capo.sub_agent_manager.response.DecisionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Service
public class ExecutingDynamicOrchestratorService {

	// ── Redis key prefixes ───────────────────────────────────────────────────────
	private static final String CONTEXT_KEY_PREFIX   = "orchestrator:context:";
	private static final String IMAGE_KEY_PREFIX     = "image:latest:";
	private static final String LAYOUT_KEY_PREFIX    = "layout:latest:";
	private static final String JSON_DATA_KEY_PREFIX = "jsonData:latest:";
	private static final String PROMPT_KEY_PREFIX    = "prompt:latest:";
	private static final Duration CONTEXT_TTL        = Duration.ofHours(1);

	// ── Orchestration limits ─────────────────────────────────────────────────────
	private static final int MAX_DEPTH             = 10;
	private static final int MAX_STEP_OUTPUT_CHARS = 2_000;
	private static final int MAX_CONTEXT_CHARS     = 8_000;

	// ── Regex patterns ───────────────────────────────────────────────────────────
	private static final Pattern BASE64_PATTERN    = Pattern.compile("^[A-Za-z0-9+/\\s]{200,}={0,2}$");
	private static final Pattern HTML_TAG_PATTERN  = Pattern.compile("<[^>]{1,100}>");
	private static final Pattern IMAGE_KEY_PATTERN  = Pattern.compile("IMAGE_KEY:([^|\\]]+)");
	private static final Pattern STRING_KEY_PATTERN = Pattern.compile("STRING_KEY:([^|\\]]+)");
	private static final Pattern JSON_KEY_PATTERN   = Pattern.compile("JSON_KEY:([^|\\]]+)");
	private static final Pattern PROMPT_KEY_PATTERN = Pattern.compile("PROMPT_KEY:([^|\\]]+)");

	// ── SSE type references ──────────────────────────────────────────────────────
	private static final ParameterizedTypeReference<ServerSentEvent<String>>      STRING_SSE_TYPE   = new ParameterizedTypeReference<>() {};
	private static final ParameterizedTypeReference<ServerSentEvent<DataMessage>> DATA_MSG_SSE_TYPE = new ParameterizedTypeReference<>() {};

	// ── Service dependencies ─────────────────────────────────────────────────────
	private final ChatClient chatClient;
	private final WebClient webClient;
	private final AgentRegistry registry;
	private final ObjectMapper mapper;
	private final ReactiveStringRedisTemplate redisTemplate;
	private final String systemPrompt;

	public ExecutingDynamicOrchestratorService(@Qualifier("chatClientOrchestrator") ChatClient chatClient,
			WebClient webClient, AgentRegistry registry, ObjectMapper mapper,
			ReactiveStringRedisTemplate redisTemplate,
			@Qualifier("systemPrompt") String systemPrompt) {
		this.chatClient = chatClient;
		this.webClient = webClient;
		this.registry = registry;
		this.mapper = mapper;
		this.redisTemplate = redisTemplate;
		this.systemPrompt = systemPrompt;
	}

	// ── Public API ───────────────────────────────────────────────────────────────

	public Flux<ServerSentEvent<DataMessage>> handleDynamicOrchestrator(GenerationSyntheticDataRequest request) {
		Sinks.Many<ServerSentEvent<DataMessage>> userPipe = Sinks.many().unicast().onBackpressureBuffer();
		String conversationId = request.getConversationId();

		storeInboundResources(request);

		redisTemplate.opsForValue().get(CONTEXT_KEY_PREFIX + conversationId)
				.defaultIfEmpty("")
				.map(ctx -> enrichContext(ctx, request))
				.subscribe(ctx -> processStep(request.getPrompt(), ctx, userPipe, 0, conversationId));

		return userPipe.asFlux();
	}

	// ── Orchestration ────────────────────────────────────────────────────────────

	private void processStep(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, String conversationId) {

		if (depth > MAX_DEPTH) {
			pipe.tryEmitError(new RuntimeException(
					"Max orchestration depth (" + MAX_DEPTH + ") reached without a FINAL decision"));
			return;
		}

		Map<String, Object> model = Map.of(
				"goal", depth == 0 ? originalGoal : "A previous step was executed. Review the Context and return FINAL if the original task is satisfied.",
				"context", accumulatedContext.isBlank() ? "none" : accumulatedContext,
				"agents", registry.getAgents().keySet());

		Mono.fromCallable(() -> chatClient.prompt()
				.messages(new SystemMessage(systemPrompt))
				.user(u -> u.text("Current Goal: {goal}\nContext: {context}\nAvailable: {agents}").params(model))
				.advisors(a -> a.param("chat_memory_conversation_id", conversationId + ":orch:" + depth))
				.call()
				.content())
				.subscribeOn(Schedulers.boundedElastic())
				.subscribe(decision -> {
					DecisionResult res;
					try {
						res = mapper.readValue(decision, DecisionResult.class);
					} catch (JsonProcessingException e) {
						pipe.tryEmitError(new RuntimeException(
								"Failed to parse orchestrator decision as JSON: " + decision, e));
						return;
					}
					if ("FINAL".equalsIgnoreCase(res.action())) {
						pipe.tryEmitComplete();
					} else {
						executeAgent(originalGoal, accumulatedContext, pipe, depth, res, conversationId);
					}
				}, pipe::tryEmitError);
	}

	private void executeAgent(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId) {

		AgentType type = registry.getAgentTypes().getOrDefault(res.agent(), AgentType.WEBFLUX);
		if (AgentType.WEBFLUX.equals(type)) {
			executingWebClient(originalGoal, accumulatedContext, pipe, depth, res, conversationId,
					DATA_MSG_SSE_TYPE, (buf, tok) -> processingTokenToWebflux(pipe, buf, tok),
					tok -> {
						DataMessage d = tok.data();
						return d != null && d.getMessage() != null && d.getMessage().endsWith("-COMPLETED");
					});
		} else {
			executingWebClient(originalGoal, accumulatedContext, pipe, depth, res, conversationId,
					STRING_SSE_TYPE, (buf, tok) -> processingTokenToMvc(pipe, buf, tok),
					tok -> false);
		}
	}

	private <T> void executingWebClient(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId,
			ParameterizedTypeReference<ServerSentEvent<T>> typeRef,
			BiConsumer<StringBuilder, ServerSentEvent<T>> tokenProcessor,
			Predicate<ServerSentEvent<T>> completionPredicate) {

		StringBuilder stepBuffer = new StringBuilder();
		buildSubAgentRequestAsync(res.agent(), accumulatedContext, originalGoal)
				.flatMapMany(subReq -> webClient.post()
						.uri(registry.getAgents().get(res.agent()))
						.bodyValue(subReq)
						.accept(MediaType.TEXT_EVENT_STREAM)
						.retrieve()
						.bodyToFlux(typeRef))
				.takeWhile(token -> !completionPredicate.test(token))
				.doOnNext(token -> tokenProcessor.accept(stepBuffer, token))
				.doOnError(pipe::tryEmitError)
				.doOnComplete(() -> handleStepCompletion(
						res, stepBuffer.toString(), depth, conversationId, originalGoal, accumulatedContext, pipe))
				.subscribe();
	}

	private void handleStepCompletion(DecisionResult res, String rawOutput, int depth, String conversationId,
			String originalGoal, String accumulatedContext, Sinks.Many<ServerSentEvent<DataMessage>> pipe) {

		String imageKey  = resolveRedisKey(res.agent(), registry.getAgentProducingImage(),
				registry.getAgentNeedingImageInput(), IMAGE_KEY_PREFIX, conversationId, rawOutput);
		String layoutKey = resolveRedisKey(res.agent(), registry.getAgentProducingLongString(),
				registry.getAgentNeedingLongStringInput(), LAYOUT_KEY_PREFIX, conversationId, rawOutput);
		String jsonKey   = resolveRedisKey(res.agent(), registry.getAgentProducingJsonData(),
				registry.getAgentNeedingJsonDataInput(), JSON_DATA_KEY_PREFIX, conversationId, rawOutput);
		String promptKey = resolveRedisKey(res.agent(), registry.getAgentProducingPrompt(),
				registry.getAgentNeedingPromptInput(), PROMPT_KEY_PREFIX, conversationId, rawOutput);

		String nextContext = buildNextContext(accumulatedContext,
				buildStepSummary(res, rawOutput, depth, imageKey, layoutKey, jsonKey, promptKey));

		redisTemplate.opsForValue().set(CONTEXT_KEY_PREFIX + conversationId, nextContext, CONTEXT_TTL).subscribe();
		processStep(originalGoal, nextContext, pipe, depth + 1, conversationId);
	}

	/**
	 * Resolves the Redis key for a given output type.
	 * If the agent produces the output, stores it in Redis and returns the key.
	 * If the agent only consumes it, returns the key for context tracking without writing.
	 */
	private String resolveRedisKey(String agent, Map<String, Boolean> producers, Map<String, Boolean> consumers,
			String keyPrefix, String conversationId, String rawOutput) {
		String key = keyPrefix + conversationId;
		if (Boolean.TRUE.equals(producers.get(agent))) {
			redisTemplate.opsForValue().set(key, rawOutput, CONTEXT_TTL).subscribe();
			return key;
		} else if (Boolean.TRUE.equals(consumers.get(agent))) {
			return key;
		}
		return null;
	}

	// ── Token processors ─────────────────────────────────────────────────────────

	private void processingTokenToWebflux(Sinks.Many<ServerSentEvent<DataMessage>> pipe,
			StringBuilder stepBuffer, ServerSentEvent<DataMessage> token) {
		DataMessage data = token.data();
		if (Objects.nonNull(data)) {
			String content = data.getMessage();
			if (content != null && !content.endsWith("-COMPLETED")) {
				stepBuffer.append(content);
				pipe.tryEmitNext(token);
			}
		}
	}

	private void processingTokenToMvc(Sinks.Many<ServerSentEvent<DataMessage>> pipe,
			StringBuilder stepBuffer, ServerSentEvent<String> token) {
		String rawData = token.data();
		if (Objects.nonNull(rawData) && !rawData.isBlank()
				&& !rawData.equals("Image generation started for prompt")) {
			stepBuffer.append(rawData);
			DataMessage data = new DataMessage();
			data.setMessage(rawData);
			ServerSentEvent<DataMessage> mapped = ServerSentEvent.<DataMessage>builder()
					.id(token.id())
					.event(token.event())
					.data(data)
					.build();
			pipe.tryEmitNext(mapped);
		}
	}

	// ── Request building ─────────────────────────────────────────────────────────

	private Mono<SubAgentRequest> buildSubAgentRequestAsync(String agent, String accumulatedContext, String prompt) {
		SubAgentRequest request = new SubAgentRequest();
		if (Boolean.TRUE.equals(registry.getAgentNeedingImageInput().get(agent))) {
			String key = extractLatestKey(IMAGE_KEY_PATTERN, accumulatedContext);
			if (key != null) request.setImageReferences(List.of(key));
		}
		if (Boolean.TRUE.equals(registry.getAgentNeedingLongStringInput().get(agent))) {
			String key = extractLatestKey(STRING_KEY_PATTERN, accumulatedContext);
			if (key != null) request.setImageReferences(List.of(key));
		}
		if (Boolean.TRUE.equals(registry.getAgentNeedingJsonDataInput().get(agent))) {
			String key = extractLatestKey(JSON_KEY_PATTERN, accumulatedContext);
			if (key != null) request.setImageReferences(List.of(key));
		}
		if (Boolean.TRUE.equals(registry.getAgentNeedingPromptInput().get(agent))) {
			String key = extractLatestKey(PROMPT_KEY_PATTERN, accumulatedContext);
			if (key != null) {
				return redisTemplate.opsForValue().get(key)
						.defaultIfEmpty(prompt)
						.map(resolvedPrompt -> {
							request.setPrompt(resolvedPrompt);
							return request;
						});
			}
		}
		request.setPrompt(prompt);
		return Mono.just(request);
	}

	// ── Inbound resource management ──────────────────────────────────────────────

	private void storeInboundResources(GenerationSyntheticDataRequest request) {
		String conversationId = request.getConversationId();
		List<String> imageRefs = request.getImageReferences();
		if (imageRefs != null && !imageRefs.isEmpty()) {
			String uploadedKey = imageRefs.get(0);
			String canonicalKey = IMAGE_KEY_PREFIX + conversationId;
			if (!canonicalKey.equals(uploadedKey)) {
				redisTemplate.opsForValue().get(uploadedKey)
						.flatMap(imageData -> redisTemplate.opsForValue().set(canonicalKey, imageData, CONTEXT_TTL))
						.subscribe();
			}
		}
		String rawPrompt = request.getPrompt();
		if (rawPrompt != null && rawPrompt.contains("[INPUT_DATA: RAW_DATA]")) {
			String jsonData = rawPrompt.substring(
					rawPrompt.indexOf("[INPUT_DATA: RAW_DATA]") + "[INPUT_DATA: RAW_DATA]".length()).trim();
			if (!jsonData.isBlank()) {
				redisTemplate.opsForValue().set(JSON_DATA_KEY_PREFIX + conversationId, jsonData, CONTEXT_TTL)
						.subscribe();
			}
		}
	}

	private String enrichContext(String ctx, GenerationSyntheticDataRequest request) {
		String updated = ctx;
		String conversationId = request.getConversationId();
		List<String> imageRefs = request.getImageReferences();
		if (imageRefs != null && !imageRefs.isEmpty() && !updated.contains("IMAGE_KEY:")) {
			updated += "\n[Image available | IMAGE_KEY:" + IMAGE_KEY_PREFIX + conversationId + "]";
		}
		String rawPrompt = request.getPrompt();
		if (rawPrompt != null && rawPrompt.contains("[INPUT_DATA: RAW_DATA]") && !updated.contains("JSON_KEY:")) {
			updated += "\n[JSON data available | JSON_KEY:" + JSON_DATA_KEY_PREFIX + conversationId + "]";
		}
		return updated;
	}

	// ── Context utilities ────────────────────────────────────────────────────────

	private String extractLatestKey(Pattern pattern, String context) {
		if (context == null || context.isBlank()) return null;
		Matcher matcher = pattern.matcher(context);
		String lastKey = null;
		while (matcher.find()) {
			lastKey = matcher.group(1).trim();
		}
		return lastKey;
	}

	private String buildStepSummary(DecisionResult res, String rawOutput, int stepNumber,
			String imageRedisKey, String longStringRedisKey, String jsonDataRedisKey, String promptRedisKey) {
		String truncatedInput = (res.input() != null && res.input().length() > 300)
				? res.input().substring(0, 300) + "..."
				: res.input();
		String outputSummary = summariseOutput(rawOutput);
		String imagePart    = imageRedisKey     != null ? " | IMAGE_KEY:"   + imageRedisKey     : "";
		String stringPart   = longStringRedisKey != null ? " | STRING_KEY:"  + longStringRedisKey : "";
		String jsonDataPart = jsonDataRedisKey  != null ? " | JSON_KEY:"    + jsonDataRedisKey  : "";
		String promptPart   = promptRedisKey    != null ? " | PROMPT_KEY:"  + promptRedisKey    : "";
		return String.format("[Step %d completed – Agent: '%s'%s%s%s%s | Input: %s | Result: %s]",
				stepNumber + 1, res.agent(), imagePart, stringPart, jsonDataPart, promptPart, truncatedInput, outputSummary);
	}

	private String summariseOutput(String rawOutput) {
		if (rawOutput == null || rawOutput.isBlank()) return "(no output)";
		String probe = rawOutput.length() > 1000
				? rawOutput.substring(0, 1000).replaceAll("\\s", "")
				: rawOutput.replaceAll("\\s", "");
		if (rawOutput.length() > 500 && BASE64_PATTERN.matcher(probe).matches()) {
			return "[Binary/Base64 data generated successfully – " + rawOutput.length() + " chars, payload omitted]";
		}
		return truncateStepOutput(rawOutput);
	}

	private String truncateStepOutput(String raw) {
		if (raw == null) return "";
		String sanitized = HTML_TAG_PATTERN.matcher(raw).replaceAll("[tag]");
		if (sanitized.length() <= MAX_STEP_OUTPUT_CHARS) return sanitized;
		return sanitized.substring(0, MAX_STEP_OUTPUT_CHARS)
				+ "\n[...output truncated, " + (sanitized.length() - MAX_STEP_OUTPUT_CHARS) + " chars omitted...]";
	}

	private String buildNextContext(String accumulatedContext, String stepOutput) {
		String combined = accumulatedContext + "\n" + truncateStepOutput(stepOutput);
		if (combined.length() <= MAX_CONTEXT_CHARS) return combined;
		return "[...earlier context trimmed...]\n" + combined.substring(combined.length() - MAX_CONTEXT_CHARS);
	}
}
