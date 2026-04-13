package com.capo.sub_agent_manager.request;

import java.util.List;

public class GenerationSyntheticDataRequest {
	
	private String prompt;
	private String conversationId;
	private List<String> imageReferences;

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

	public List<String> getImageReferences() {
		return imageReferences;
	}

	public void setImageReferences(List<String> imageReferences) {
		this.imageReferences = imageReferences;
	}
		
}
