package com.capo.sub_agent_manager.controller;

import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capo.sub_agent_manager.request.GenerationSyntheticDataRequest;
import com.capo.sub_agent_manager.response.DataMessage;
import com.capo.sub_agent_manager.service.ExecutingDynamicOrchestratorService;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("sub-agent-manager-chat")
@CrossOrigin(origins = "${app.frontend.url}")
public class SubAgentController {
	
	private final ExecutingDynamicOrchestratorService executingAgentService;
	
	public SubAgentController(ExecutingDynamicOrchestratorService executingAgentService) {
		this.executingAgentService= executingAgentService;
	}
	
	@PostMapping(path = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<DataMessage>> chatClient(@RequestBody GenerationSyntheticDataRequest request) {
		return Flux.just(request)
	    		.filter(req -> Objects.nonNull(req.getPrompt())) 
	            .filter(req ->  !req.getPrompt().trim().isEmpty())
	            .doOnNext(req->{System.out.println(req);} )
	            .flatMap(executingAgentService::handleDynamicOrchestrator);
	}
	
}
