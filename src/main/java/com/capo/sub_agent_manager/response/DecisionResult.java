package com.capo.sub_agent_manager.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DecisionResult(
	    @JsonProperty("selected_agent") String agent, // Maps JSON "selected_agent" to Java "agent"
	    String action, 
	    String input, 
	    String reasoning
	) {}
