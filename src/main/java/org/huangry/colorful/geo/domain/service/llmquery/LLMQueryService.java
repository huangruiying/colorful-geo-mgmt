package org.huangry.colorful.geo.domain.service.llmquery;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.springframework.stereotype.Service;

/**
 *
 * @author huangry
 * Created in 2026/9/15 18:42
 */
@Service
public class LLMQueryService {

	@Resource
	private LLMClient llmClient;

	public LLMResponse llmQuery(String text) {
		return llmClient.query(text);
	}

}
