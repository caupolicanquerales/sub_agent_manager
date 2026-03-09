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
	
	public Map<String, String> getAgents() {
        return Map.of(
            "html", urlHtml,
            "improver", urlImprover
        );
    }
	
}
