package com.capo.sub_agent_manager.request;

public class GenerationSyntheticDataRequest {
	
	private String prompt;
	private String conversationId;

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}
		
}
