package com.capo.sub_agent_manager.service;

import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.capo.sub_agent_manager.configuration.AgentRegistry;
import com.capo.sub_agent_manager.request.GenerationSyntheticDataRequest;
import com.capo.sub_agent_manager.request.SubAgentRequest;
import com.capo.sub_agent_manager.response.DataMessage;
import com.capo.sub_agent_manager.response.DecisionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

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
        processStep(request.getPrompt(), "", userPipe, 0);
        return userPipe.asFlux();
    }
	
	private static final int MAX_DEPTH = 10;

	private void processStep(String originalGoal, String accumulatedContext, Sinks.Many<ServerSentEvent<DataMessage>> pipe, int depth) {
        
		if (depth > MAX_DEPTH) {
            pipe.tryEmitError(new RuntimeException("Max orchestration depth (" + MAX_DEPTH + ") reached without a FINAL decision"));
            return;
        }

        Map<String, Object> model = Map.of(
        	    "goal", originalGoal,
        	    "context", accumulatedContext,
        	    "agents", registry.getAgents()
        	);

        // chatClient.call().content() is blocking — offload to boundedElastic so Netty IO threads are never blocked
        Mono.fromCallable(() -> chatClient.prompt()
        		.user(u -> u.text("Current Goal: {goal}\nContext: {context}\nAvailable: {agents}")
        	               .params(model))
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
        					processStep(originalGoal, accumulatedContext + "\n" + stepBuffer, pipe, depth + 1);
        				})
        				.subscribe();
        		}
        		
        	}, pipe::tryEmitError);
    }
	
	private SubAgentRequest setSubAgentRequest(String prompt) {
		SubAgentRequest request= new SubAgentRequest();
		request.setPrompt(prompt);
		return request;
	}
	
}
