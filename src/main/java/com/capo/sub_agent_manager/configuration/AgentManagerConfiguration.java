package com.capo.sub_agent_manager.configuration;

import java.io.IOException;
import java.nio.charset.Charset;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;


@Configuration
public class AgentManagerConfiguration {
	
	@Value("classpath:prompts/system-prompt.md")
    private Resource systemPromptResource;
	
	@Bean
    public String systemPrompt() throws IOException {
        return systemPromptResource.getContentAsString(Charset.defaultCharset());
    }
	
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
            .build();
    }
}
