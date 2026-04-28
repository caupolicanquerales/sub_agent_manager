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
	
	@Value(value="${url-general-chat}")
	private String urlGeneralChat;

	@Value(value="${url-build-template}")
	private String urlBuildTemplate;
	
	@Value(value="${url-visual-effects}")
	private String urlVisualEffects;
	
	@Value(value="${url-layout-architect}")
	private String urlLayoutArchitect;
	
	@Value(value="${url-synthetic-data}")
	private String urlSyntheticData;
	
	@Value(value="${agent-type-html:WEBFLUX}")
	private AgentType agentTypeHtml;

	@Value(value="${agent-type-improver:WEBFLUX}")
	private AgentType agentTypeImprover;

	@Value(value="${agent-type-general-chat:WEBFLUX}")
	private AgentType agentTypeGeneralChat;
	
	@Value(value="${agent-type-image:SPRING_MVC}")
	private AgentType agentTypeImage;
	
	@Value(value="${agent-type-build-template:SPRING_MVC}")
	private AgentType agentTypeBuildTemplate;
	
	@Value(value="${agent-type-visual-effects:SPRING_MVC}")
	private AgentType agentTypeVisualEffects;
	
	@Value(value="${agent-type-layout-architect:SPRING_MVC}")
	private AgentType agentTypeLayoutArchitect;
	
	@Value(value="${agent-type-synthetic-data:WEBFLUX}")
	private AgentType agentTypeSyntheticData;

	public Map<String, String> getAgents() {
        return Map.of(
            "html", urlHtml,
            "improver", urlImprover,
            "image", urlImage,
            "general", urlGeneralChat,
            "buildTemplate",urlBuildTemplate,
            "visualEffects",urlVisualEffects,
            "layoutArchitect",urlLayoutArchitect,
            "syntheticData",urlSyntheticData
        );
    }

	public Map<String, AgentType> getAgentTypes() {
        return Map.of(
            "html", agentTypeHtml,
            "improver", agentTypeImprover,
            "image", agentTypeImage,
            "general", agentTypeGeneralChat,
            "buildTemplate",agentTypeBuildTemplate,
            "visualEffects",agentTypeVisualEffects,
            "layoutArchitect",agentTypeLayoutArchitect,
            "syntheticData",agentTypeSyntheticData
        );
    }
	
	/**
	 * Agents whose SSE output IS a large JSON/HTML+CSS string that should be stored in Redis
	 * under the key "layout:latest:{conversationId}" so that other agents (or a subsequent
	 * call to layoutArchitect itself) can retrieve it as the current working template.
	 */
	public Map<String, Boolean> getAgentProducingLongString() {
        return Map.of(
            "layoutArchitect", Boolean.TRUE,
            "image", Boolean.FALSE,
            "buildTemplate", Boolean.FALSE,
            "visualEffects", Boolean.FALSE,
            "html", Boolean.FALSE,
            "improver", Boolean.FALSE,
            "general", Boolean.FALSE,
            "syntheticData", Boolean.FALSE
        );
    }

	/**
	 * Agents whose SSE output IS a raw base64 image that should be stored in Redis.
	 * Do NOT include agents whose output is LLM text even if they transform images
	 * (e.g. visualEffects stores the result internally via its own Redis tool call).
	 */
	public Map<String, Boolean> getAgentProducingImage() {
        return Map.of(
            "image", Boolean.TRUE,
            "buildTemplate", Boolean.TRUE,
            "visualEffects", Boolean.FALSE,
            "html", Boolean.FALSE,
            "improver", Boolean.FALSE,
            "general", Boolean.FALSE,
			"layoutArchitect", Boolean.FALSE,
			"syntheticData", Boolean.FALSE
        );
    }

	/**
	 * Agents that require an existing image Redis key as input (imageReferences).
	 * These agents read the current image from Redis but may or may not stream base64 back.
	 */
	public Map<String, Boolean> getAgentNeedingImageInput() {
        return Map.of(
            "visualEffects", Boolean.TRUE,
            "image", Boolean.FALSE,
            "buildTemplate", Boolean.FALSE,
            "html", Boolean.FALSE,
            "improver", Boolean.FALSE,
            "general", Boolean.FALSE,
			"layoutArchitect", Boolean.FALSE,
			"syntheticData", Boolean.FALSE
        );
    }

	public Map<String, Boolean> getAgentNeedingLongStringInput() {
        return Map.of(
            "visualEffects", Boolean.FALSE,
            "image", Boolean.FALSE,
            "buildTemplate", Boolean.FALSE,
            "html", Boolean.FALSE,
            "improver", Boolean.FALSE,
            "general", Boolean.FALSE,
			"layoutArchitect", Boolean.TRUE,
			"syntheticData", Boolean.FALSE
        );
    }

	/**
	 * Agents whose SSE output IS synthetic data that should be stored in Redis
	 * under the key "jsonData:latest:{conversationId}" so that iteration calls
	 * can retrieve the previously generated dataset.
	 */
	public Map<String, Boolean> getAgentProducingJsonData() {
        return Map.of(
            "syntheticData", Boolean.TRUE,
            "layoutArchitect", Boolean.FALSE,
            "image", Boolean.FALSE,
            "buildTemplate", Boolean.FALSE,
            "visualEffects", Boolean.FALSE,
            "html", Boolean.FALSE,
            "improver", Boolean.FALSE,
            "general", Boolean.FALSE
        );
    }

	/**
	 * Agents that require an existing JSON data Redis key as input (imageReferences).
	 * These agents read the current JSON schema or generated dataset from Redis.
	 */
	public Map<String, Boolean> getAgentNeedingJsonDataInput() {
        return Map.of(
            "syntheticData", Boolean.TRUE,
            "layoutArchitect", Boolean.FALSE,
            "image", Boolean.FALSE,
            "buildTemplate", Boolean.FALSE,
            "visualEffects", Boolean.FALSE,
            "html", Boolean.FALSE,
            "improver", Boolean.FALSE,
            "general", Boolean.FALSE
        );
    }
	
}
