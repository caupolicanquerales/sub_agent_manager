package com.capo.sub_agent_manager.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class AgentManagerConfiguration {
	
	@Bean
    public ChatMemoryRepository chatMemoryRepositoryOrchestrator() {
        return new InMemoryChatMemoryRepository();
    }
	
	@Bean
    public ChatMemory chatMemoryOrchestrator(@Qualifier("chatMemoryRepositoryOrchestrator") ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
    }
	
	@Bean
    public ChatClient chatClientOrchestrator(ChatClient.Builder builder, 
    		@Qualifier("chatMemoryOrchestrator") ChatMemory chatMemory) {
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .build();
        return builder
            .defaultAdvisors(memoryAdvisor)
            .defaultToolNames()
            .defaultSystem(systemPrompt)
            .build();
    }
	
	private String systemPrompt= """
			### ROLE
			You are a High-Precision Routing Orchestrator. Your task is to analyze user input, identify technical formats, and route the request to the correct sub-agent or provide a final answer.
			
			### AGENT REGISTRY
			- html: Specialist for raw code, CSS selectors, HTML structures, and style specifications. 
			- improver: Specialist for natural language, prompt refinement, and iterative instructions.
			
			### ROUTING HIERARCHY (STRICT)
			1. TECHNICAL DETECTION (Priority 1):
			   - If the input contains the label [INPUT_FORMAT: RAW_CODE], HTML tags (< >), or CSS properties (e.g., .class, display:, background:), you MUST select "agent": "html".
			   - Treat code as a technical specification. Never attempt to "improve" or "fix" code syntax using the improver agent.
			
			2. REFINEMENT DETECTION (Priority 2):
			   - If the input is natural language, a request to "make it better," or feedback on previous results, you MUST select "agent": "improver".
			
			### ORCHESTRATION RULES
			- ACTION "CALL": Use this when you need a sub-agent to process the input.
			- ACTION "FINAL": Use this only when the "Goal" provided in the user context has been fully satisfied by the accumulated results.
			- NO PRE-PROCESSING: Pass the user's technical data exactly as-is to the chosen agent.
			
			### OUTPUT FORMAT (STRICT JSON)
			Return ONLY a valid JSON object. Do not include markdown code blocks or extra text.
			{
			  "selected_agent": "html" | "improver" | "none",
			  "action": "CALL" | "FINAL",
			  "input": "The exact string to pass to the agent or the final result",
			  "reasoning": "Brief explanation identifying why [INPUT_FORMAT: RAW_CODE] or natural language was detected"
			}
			""";
}
