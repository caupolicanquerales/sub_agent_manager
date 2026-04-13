package com.capo.sub_agent_manager.request;

import java.util.List;

public class SubAgentRequest {
	
	private String prompt;
	private List<String> imageReferences;

	public String getPrompt() {
		return prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public List<String> getImageReferences() {
		return imageReferences;
	}

	public void setImageReferences(List<String> imageReferences) {
		this.imageReferences = imageReferences;
	}
	
}
