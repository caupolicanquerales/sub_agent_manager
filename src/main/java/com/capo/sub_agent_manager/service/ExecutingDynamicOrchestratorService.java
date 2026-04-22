package com.capo.sub_agent_manager.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
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

	private final ChatClient chatClient;
	private final WebClient webClient;
	private final AgentRegistry registry;
	private final ObjectMapper mapper;
	private final ReactiveStringRedisTemplate redisTemplate;
	private final String systemPrompt;
	
	private static final String CONTEXT_KEY_PREFIX = "orchestrator:context:";
    private static final Duration CONTEXT_TTL = Duration.ofHours(1);
    
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ServerSentEvent<DataMessage>> DATA_MSG_SSE_TYPE = new ParameterizedTypeReference<>() {};
    
	public ExecutingDynamicOrchestratorService(@Qualifier("chatClientOrchestrator") ChatClient chatClient,
			WebClient webClient, AgentRegistry registry,
			ObjectMapper mapper,
			ReactiveStringRedisTemplate redisTemplate,
			@Qualifier("systemPrompt") String systemPrompt) {
		this.chatClient= chatClient;
		this.webClient= webClient;
		this.registry= registry;
		this.mapper= mapper;
		this.redisTemplate=redisTemplate;
		this.systemPrompt= systemPrompt;
	}
	
	public Flux<ServerSentEvent<DataMessage>> handleDynamicOrchestrator(GenerationSyntheticDataRequest request) {
        Sinks.Many<ServerSentEvent<DataMessage>> userPipe = Sinks.many().unicast().onBackpressureBuffer();
        String key = CONTEXT_KEY_PREFIX + request.getConversationId();

        // If the caller provides an image reference (e.g. user uploaded an image via the chat UI),
        // store it at the canonical key and seed the context so sub-agents can find it.
        List<String> imageRefs = request.getImageReferences();
        if (imageRefs != null && !imageRefs.isEmpty()) {
            String uploadedImageKey = imageRefs.get(0);
            String canonicalKey = "image:latest:" + request.getConversationId();
            // Copy the uploaded image to the canonical orchestrator key only if they differ
            if (!canonicalKey.equals(uploadedImageKey)) {
                redisTemplate.opsForValue().get(uploadedImageKey)
                    .flatMap(imageData -> redisTemplate.opsForValue().set(canonicalKey, imageData, CONTEXT_TTL))
                    .subscribe();
            }
        }

        redisTemplate.opsForValue().get(key)
            .defaultIfEmpty("")
            .map(previousContext -> {
                // Inject the canonical image key into context if an image was provided and not already tracked
                if (imageRefs != null && !imageRefs.isEmpty()
                        && !previousContext.contains("IMAGE_KEY:")) {
                    String canonicalKey = "image:latest:" + request.getConversationId();
                    return previousContext + "\n[Image available | IMAGE_KEY:" + canonicalKey + "]";
                }
                return previousContext;
            })
            .subscribe(previousContext ->
                processStep(request.getPrompt(), previousContext,
                            userPipe, 0, request.getConversationId())
            );
        
        return userPipe.asFlux();
    }
	
	private static final int MAX_DEPTH = 10;

	private void processStep(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, String conversationId) {
        
		if (depth > MAX_DEPTH) {
            pipe.tryEmitError(new RuntimeException("Max orchestration depth (" + MAX_DEPTH + ") reached without a FINAL decision"));
            return;
        }

        Map<String, Object> model = Map.of(
        	    "goal", depth == 0 ? originalGoal : "A previous step was executed. Review the Context and return FINAL if the original task is satisfied.",
        	    "context", accumulatedContext.isBlank() ? "none" : accumulatedContext,
        	    "agents", registry.getAgents().keySet()
        	);

        Mono.fromCallable(() -> chatClient.prompt()
        		.messages(new SystemMessage(systemPrompt))
        		.user(u -> u.text("Current Goal: {goal}\nContext: {context}\nAvailable: {agents}")
        	               .params(model))
        		.advisors(a -> a.param("chat_memory_conversation_id", conversationId + ":orch:" + depth))
        		.call()
        		.content())
        	.subscribeOn(Schedulers.boundedElastic())
        	.subscribe(decision -> {
        		
        		DecisionResult res;
        		try {
        			res = mapper.readValue(decision, DecisionResult.class);
        		} catch (JsonProcessingException e) {
        			pipe.tryEmitError(new RuntimeException("Failed to parse orchestrator decision as JSON: " + decision, e));
        			return;
        		}

        		if ("FINAL".equalsIgnoreCase(res.action())) {
        			pipe.tryEmitComplete();
        		} else {
        			executeAgent(originalGoal, accumulatedContext, pipe, depth, res, conversationId);
        		}
        		
        	}, pipe::tryEmitError);
    }

	private <T> void executingWebClient(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId,
			ParameterizedTypeReference<ServerSentEvent<T>> typeRef,
			BiConsumer<StringBuilder, ServerSentEvent<T>> tokenProcessor) {
		
		StringBuilder stepBuffer = new StringBuilder();
		webClient.post()
			.uri(registry.getAgents().get(res.agent()))
			.bodyValue(setSubAgentRequest(res.agent(),accumulatedContext,originalGoal))
			.accept(MediaType.TEXT_EVENT_STREAM)
			.retrieve()
			.bodyToFlux(typeRef)
			.doOnNext(token -> tokenProcessor.accept(stepBuffer, token))
			.doOnError(pipe::tryEmitError)
			.doOnComplete(() -> {
				String rawOutput = stepBuffer.toString();
				String imageRedisKey = null;
				if (Boolean.TRUE.equals(registry.getAgentProducingImage().get(res.agent()))) {
					// Agent streams base64 image via SSE – store it in Redis
					imageRedisKey = "image:latest:" + conversationId;
					redisTemplate.opsForValue().set(imageRedisKey, rawOutput, CONTEXT_TTL).subscribe();
				} else if (Boolean.TRUE.equals(registry.getAgentNeedingImageInput().get(res.agent()))) {
					// Agent consumed and internally updated the image in Redis via its tool.
					// Only track the key for the context – do NOT overwrite with the LLM text output.
					imageRedisKey = "image:latest:" + conversationId;
				}
				String longStringRedisKey = null;
				if (Boolean.TRUE.equals(registry.getAgentProducingLongString().get(res.agent()))) {
					// Agent streams a large HTML/CSS JSON string – store it in Redis
					longStringRedisKey = "layout:latest:" + conversationId;
					redisTemplate.opsForValue().set(longStringRedisKey, rawOutput, CONTEXT_TTL).subscribe();
				} else if (Boolean.TRUE.equals(registry.getAgentNeedingLongStringInput().get(res.agent()))) {
					// Agent will have consumed the stored layout string; track the key for context.
					longStringRedisKey = "layout:latest:" + conversationId;
				}
				String stepSummary = buildStepSummary(res, rawOutput, depth, imageRedisKey, longStringRedisKey);
				String nextContext = buildNextContext(accumulatedContext, stepSummary);
				String key = CONTEXT_KEY_PREFIX + conversationId;
				redisTemplate.opsForValue()
			        .set(key, nextContext, CONTEXT_TTL)
			        .subscribe();
				processStep(originalGoal, nextContext, pipe, depth + 1, conversationId);
			})
			.subscribe();
	}
	
	
	private void executeAgent(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId) {

		AgentType type = registry.getAgentTypes().getOrDefault(res.agent(), AgentType.WEBFLUX);

		if (AgentType.WEBFLUX.equals(type)) {
			executingWebClient(originalGoal, accumulatedContext, pipe, depth, res, conversationId,
					DATA_MSG_SSE_TYPE, (buf, tok) -> processingTokenToWebflux(pipe, buf, tok));
		} else {
			executingWebClient(originalGoal, accumulatedContext, pipe, depth, res, conversationId,
					STRING_SSE_TYPE, (buf, tok) -> processingTokenToMvc(pipe, buf, tok));
		}
	}
	
	private void processingTokenToWebflux(Sinks.Many<ServerSentEvent<DataMessage>> pipe, StringBuilder stepBuffer,
			ServerSentEvent<DataMessage> token) {
		DataMessage data = token.data();
		if (Objects.nonNull(data)) {
			String content = data.getMessage();
			if (content != null && !content.endsWith("-COMPLETED")) {
				stepBuffer.append(content);
				pipe.tryEmitNext(token);
			}
		}
	}
	
	private void processingTokenToMvc(Sinks.Many<ServerSentEvent<DataMessage>> pipe, StringBuilder stepBuffer,
			ServerSentEvent<String> token) {
		String rawData = token.data();
		if (Objects.nonNull(rawData) && !rawData.isBlank()) {
			if (!rawData.equals("Image generation started for prompt") && !rawData.endsWith("-COMPLETED")) {
				stepBuffer.append(rawData);
				DataMessage data = new DataMessage();
				data.setMessage(rawData);
				ServerSentEvent<DataMessage> mapped = ServerSentEvent
						.<DataMessage>builder()
						.id(token.id())
						.event(token.event())
						.data(data)
						.build();
				pipe.tryEmitNext(mapped);
			}
		}
	}
	
	private SubAgentRequest setSubAgentRequest(String agent, String accumulatedContext, String prompt) {
		SubAgentRequest request = new SubAgentRequest();
		request.setPrompt(prompt);
		// Pass imageReferences to agents that need an existing image as input
		if (Boolean.TRUE.equals(registry.getAgentNeedingImageInput().get(agent))) {
			String imageKey = extractLatestImageKey(accumulatedContext);
			if (imageKey != null) {
				request.setImageReferences(List.of(imageKey));
			}
		}
		// Pass the stored layout Redis key to agents that need a long-string (HTML/CSS) as input
		if (Boolean.TRUE.equals(registry.getAgentNeedingLongStringInput().get(agent))) {
			String layoutKey = extractLatestStringKey(accumulatedContext);
			if (layoutKey != null) {
				request.setImageReferences(List.of(layoutKey));
			}
		}
		return request;
	}

	private String extractLatestImageKey(String context) {
		if (context == null || context.isBlank()) return null;
		Matcher matcher = IMAGE_KEY_PATTERN.matcher(context);
		String lastKey = null;
		while (matcher.find()) {
			lastKey = matcher.group(1).trim();
		}
		return lastKey;
	}

	private String extractLatestStringKey(String context) {
		if (context == null || context.isBlank()) return null;
		Matcher matcher = STRING_KEY_PATTERN.matcher(context);
		String lastKey = null;
		while (matcher.find()) {
			lastKey = matcher.group(1).trim();
		}
		return lastKey;
	}
	

	/**
	 * Builds a human-readable summary of a completed agent step that is safe to
	 * feed back to the orchestrator LLM.  Raw binary/base64 payloads are replaced
	 * with a concise description so the LLM can correctly decide "FINAL" rather
	 * than being confused by garbage data and looping indefinitely.
	 */
	// Matches a string whose first 500 non-whitespace chars look like base64
	private static final Pattern BASE64_PATTERN =
			Pattern.compile("^[A-Za-z0-9+/\\s]{200,}={0,2}$");

	private static final Pattern IMAGE_KEY_PATTERN = Pattern.compile("IMAGE_KEY:([^|\\]]+)");
	private static final Pattern STRING_KEY_PATTERN = Pattern.compile("STRING_KEY:([^|\\]]+)");

	private String buildStepSummary(DecisionResult res, String rawOutput, int stepNumber, String imageRedisKey, String longStringRedisKey) {
		String truncatedInput = (res.input() != null && res.input().length() > 300)
				? res.input().substring(0, 300) + "..."
				: res.input();

		String outputSummary;
		if (rawOutput == null || rawOutput.isBlank()) {
			outputSummary = "(no output)";
		} else {
			// Detect base64 / binary payloads (no spaces, very long, base64 charset)
			String probe = rawOutput.length() > 1000
					? rawOutput.substring(0, 1000).replaceAll("\\s", "")
					: rawOutput.replaceAll("\\s", "");
			if (rawOutput.length() > 500 && BASE64_PATTERN.matcher(probe).matches()) {
				outputSummary = "[Binary/Base64 data generated successfully – "
						+ rawOutput.length() + " chars, payload omitted]";
			} else {
				outputSummary = truncateStepOutput(rawOutput);
			}
		}
		String imagePart = imageRedisKey != null ? " | IMAGE_KEY:" + imageRedisKey : "";
		String stringPart = longStringRedisKey != null ? " | STRING_KEY:" + longStringRedisKey : "";
		return String.format("[Step %d completed – Agent: '%s'%s%s | Input: %s | Result: %s]",
				stepNumber + 1, res.agent(), imagePart, stringPart, truncatedInput, outputSummary);
	}

	/**
	 * Prevents token-limit explosions by capping the step output that is fed back
	 * into the orchestrator context (e.g. base64 image blobs, huge HTML responses).
	 * If the output exceeds maxChars, only the first maxChars chars are kept plus a
	 * notice so the LLM knows the content was truncated.
	 */
	private static final int MAX_STEP_OUTPUT_CHARS = 2_000;
	private static final int MAX_CONTEXT_CHARS     = 8_000;

	private static final java.util.regex.Pattern HTML_TAG_PATTERN = java.util.regex.Pattern.compile("<[^>]{1,100}>");

	private String truncateStepOutput(String raw) {
		if (raw == null) return "";
		// Strip HTML tags so the LLM does not re-detect them as new input to route
		String sanitized = HTML_TAG_PATTERN.matcher(raw).replaceAll("[tag]");
		if (sanitized.length() <= MAX_STEP_OUTPUT_CHARS) return sanitized;
		return sanitized.substring(0, MAX_STEP_OUTPUT_CHARS)
				+ "\n[...output truncated, " + (sanitized.length() - MAX_STEP_OUTPUT_CHARS) + " chars omitted...]";
	}

	private String buildNextContext(String accumulatedContext, String stepOutput) {
		String truncatedStep = truncateStepOutput(stepOutput);
		String combined = accumulatedContext + "\n" + truncatedStep;
		if (combined.length() <= MAX_CONTEXT_CHARS) return combined;
		return "[...earlier context trimmed...]\n"
				+ combined.substring(combined.length() - MAX_CONTEXT_CHARS);
	}
	
	
}
