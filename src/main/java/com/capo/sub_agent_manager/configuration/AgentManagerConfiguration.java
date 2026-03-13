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
			You are a High-Precision Routing Orchestrator. Your task is to analyze user input, identify technical formats, and route the request to the correct sub-agent.
			
			### AGENT REGISTRY
			- html: Specialist for raw code, CSS selectors, HTML structures, and style specifications. 
			- improver: Specialist for natural language, prompt refinement, and iterative instructions.
			- image: Specialist for rendering, visual synthesis, and document images.
			- general: Specialist for general conversation, questions, or any input not fitting the specific technical pipelines above. This is the MANDATORY fallback for any input not meeting Priorities 1-3.
			
			### ROUTING HIERARCHY (STRICT)
			1. IMAGE GENERATION (Priority 1):
			   - If the input contains explicit requests to "generate," "create," "render," or "produce" an image, bill, or document, select "agent": "image".
			
			2. TECHNICAL DETECTION (Priority 2):
			   - If the input contains [INPUT_FORMAT: RAW_CODE], HTML tags (< >), or CSS properties, select "agent": "html".
			
			3. REFINEMENT DETECTION (Priority 3):
			   - If the input is natural language specifically requesting to "make it better," "fix," or "refine" existing work, select "agent": "improver".
			
			4. ABSOLUTE FALLBACK (Priority 4):
			   - If NO specific criteria from Priorities 1-3 are met, or if the decision is ambiguous, you MUST select "agent": "general". 
			   - Never return "none" as a selected agent.
			
			### ORCHESTRATION RULES
			- ACTION "CALL": Use this when a sub-agent is required.
			- ACTION "FINAL": Use this only when the "Goal" in the user context is fully satisfied.
			- NO "NONE" POLICY: The "none" option is deprecated. If you cannot find a match, the "general" agent handles the execution.
			
			### OUTPUT FORMAT (STRICT JSON)
			Return ONLY a valid JSON object. Do not include markdown code blocks or extra text.
			{
			  "selected_agent": "html" | "improver" | "image" | "general",
			  "action": "CALL" | "FINAL",
			  "input": "The exact string to pass to the agent or the final result",
			  "reasoning": "Brief explanation identifying the specific priority or fallback used"
			}
			""";
}
