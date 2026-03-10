package com.capo.sub_agent_manager.service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
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
	
	public ExecutingDynamicOrchestratorService(@Qualifier("chatClientOrchestrator") ChatClient chatClient,
			WebClient webClient, AgentRegistry registry,
			ObjectMapper mapper) {
		this.chatClient= chatClient;
		this.webClient= webClient;
		this.registry= registry;
		this.mapper= mapper;
	}
	
	public Flux<ServerSentEvent<DataMessage>> handleDynamicOrchestrator(GenerationSyntheticDataRequest request) {
        Sinks.Many<ServerSentEvent<DataMessage>> userPipe = Sinks.many().unicast().onBackpressureBuffer();
        // Each orchestration run gets its own conversation ID so that the
        // ChatMemory advisor does not bleed history across runs or users.
        String conversationId = UUID.randomUUID().toString();
        processStep(request.getPrompt(), "", userPipe, 0, conversationId);
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
        	    "goal", originalGoal,
        	    "context", accumulatedContext,
        	    "agents", registry.getAgents()
        	);

        Mono.fromCallable(() -> chatClient.prompt()
        		.user(u -> u.text("Current Goal: {goal}\nContext: {context}\nAvailable: {agents}")
        	               .params(model))
        		.advisors(a -> a.param("chat_memory_conversation_id", conversationId))
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

        		if ("FINAL".equals(res.action())) {
        			pipe.tryEmitComplete();
        		} else {
        			executeAgent(originalGoal, accumulatedContext, pipe, depth, res, conversationId);
        		}
        		
        	}, pipe::tryEmitError);
    }

	private void executingWebClientToWebFlux(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId) {
		
		ParameterizedTypeReference<ServerSentEvent<DataMessage>> typeRef = 
			    new ParameterizedTypeReference<>() {};
		
		StringBuilder stepBuffer = new StringBuilder();
		webClient.post()
			.uri(registry.getAgents().get(res.agent()))
			.bodyValue(setSubAgentRequest(res.input()))
			.accept(MediaType.TEXT_EVENT_STREAM)
			.retrieve()
			.bodyToFlux(typeRef)
			.doOnNext(token -> {
				DataMessage data = token.data();
				if (Objects.nonNull(data)) {
					String content = data.getMessage();
					pipe.tryEmitNext(token);
					stepBuffer.append(content);
				}
			})
			.doOnError(pipe::tryEmitError)
			.doOnComplete(() -> {
				String stepSummary = buildStepSummary(res, stepBuffer.toString());
				processStep(originalGoal, buildNextContext(accumulatedContext, stepSummary), pipe, depth + 1, conversationId);
			})
			.subscribe();
	}
	
	/**
	 * Routes the call to the correct method based on the agent's type registered
	 * in AgentRegistry. WEBFLUX agents emit ServerSentEvent<DataMessage> with the
	 * data already serialized as JSON, so Spring's codec can deserialize it directly.
	 * SPRING_MVC agents use SseEmitter and emit ServerSentEvent<String> where the
	 * data field contains raw JSON that must be parsed manually.
	 */
	private void executeAgent(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId) {

		AgentType type = registry.getAgentTypes()
				.getOrDefault(res.agent(), AgentType.WEBFLUX);

		if (AgentType.WEBFLUX.equals(type)) {
			executingWebClientToWebFlux(originalGoal, accumulatedContext, pipe, depth, res, conversationId);
		} else {
			executingWebClientSpringMvc(originalGoal, accumulatedContext, pipe, depth, res, conversationId);
		}
	}

	/**
	 * Handles Spring MVC agents that use SseEmitter.
	 * SseEmitter sends the data as a raw JSON string inside the SSE "data:" field,
	 * so we receive ServerSentEvent<String> and deserialize manually.
	 */
	private void executingWebClientSpringMvc(String originalGoal, String accumulatedContext,
			Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth, DecisionResult res, String conversationId) {

		ParameterizedTypeReference<ServerSentEvent<String>> typeRef =
				new ParameterizedTypeReference<>() {};

		StringBuilder stepBuffer = new StringBuilder();
		webClient.post()
				.uri(registry.getAgents().get(res.agent()))
				.bodyValue(setSubAgentRequest(res.input()))
				.accept(MediaType.TEXT_EVENT_STREAM)
				.retrieve()
				.bodyToFlux(typeRef)
				.doOnNext(token -> {
					String rawData = token.data();
					if (Objects.nonNull(rawData) && !rawData.isBlank() && !rawData.equals("Image generation started for prompt")) {
						DataMessage data = new DataMessage();
						data.setMessage(rawData);
						ServerSentEvent<DataMessage> mapped = ServerSentEvent
								.<DataMessage>builder()
								.id(token.id())
								.event(token.event())
								.data(data)
								.build();
						pipe.tryEmitNext(mapped);
						stepBuffer.append(rawData);
					}
				})
				.doOnError(pipe::tryEmitError)
				.doOnComplete(() -> {
					String stepSummary = buildStepSummary(res, stepBuffer.toString());
					processStep(originalGoal, buildNextContext(accumulatedContext, stepSummary), pipe, depth + 1, conversationId);
				})
				.subscribe();
	}
	
	private SubAgentRequest setSubAgentRequest(String prompt) {
		SubAgentRequest request= new SubAgentRequest();
		request.setPrompt(prompt);
		return request;
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

	private String buildStepSummary(DecisionResult res, String rawOutput) {
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
		return String.format("[Step completed – Agent: '%s' | Input: %s | Result: %s]",
				res.agent(), truncatedInput, outputSummary);
	}

	/**
	 * Prevents token-limit explosions by capping the step output that is fed back
	 * into the orchestrator context (e.g. base64 image blobs, huge HTML responses).
	 * If the output exceeds maxChars, only the first maxChars chars are kept plus a
	 * notice so the LLM knows the content was truncated.
	 */
	private static final int MAX_STEP_OUTPUT_CHARS = 2_000;
	private static final int MAX_CONTEXT_CHARS     = 8_000;

	private String truncateStepOutput(String raw) {
		if (raw == null) return "";
		if (raw.length() <= MAX_STEP_OUTPUT_CHARS) return raw;
		return raw.substring(0, MAX_STEP_OUTPUT_CHARS)
				+ "\n[...output truncated, " + (raw.length() - MAX_STEP_OUTPUT_CHARS) + " chars omitted...]";
	}

	private String buildNextContext(String accumulatedContext, String stepOutput) {
		String truncatedStep = truncateStepOutput(stepOutput);
		String combined = accumulatedContext + "\n" + truncatedStep;
		if (combined.length() <= MAX_CONTEXT_CHARS) return combined;
		// Keep the tail so the most recent information survives
		return "[...earlier context trimmed...]\n"
				+ combined.substring(combined.length() - MAX_CONTEXT_CHARS);
	}
	
	
}
