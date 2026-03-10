package com.capo.sub_agent_manager.configuration;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRegistry {
	
	@Value(value="${url-html}")
	private String urlHtml;
	
	@Value(value="${url-improver}")
	private String urlImprover;
	
	@Value(value="${url-image}")
	private String urlImage;

	@Value(value="${agent-type-html:WEBFLUX}")
	private AgentType agentTypeHtml;

	@Value(value="${agent-type-improver:WEBFLUX}")
	private AgentType agentTypeImprover;

	@Value(value="${agent-type-image:SPRING_MVC}")
	private AgentType agentTypeImage;

	public Map<String, String> getAgents() {
        return Map.of(
            "html", urlHtml,
            "improver", urlImprover,
            "image", urlImage
        );
    }

	public Map<String, AgentType> getAgentTypes() {
        return Map.of(
            "html", agentTypeHtml,
            "improver", agentTypeImprover,
            "image", agentTypeImage
        );
    }
	
}
