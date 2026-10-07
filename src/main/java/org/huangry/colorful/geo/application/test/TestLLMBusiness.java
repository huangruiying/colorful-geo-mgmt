package org.huangry.colorful.geo.application.test;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.llmquery.LLMQueryService;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.springframework.stereotype.Component;

/**
 *
 * @author huangry
 * Created in 2026/9/15 18:44
 */
@Component
public class TestLLMBusiness {

	@Resource
	private LLMQueryService llmQueryService;

	public LLMResponse llmQuery(String text) {
		return llmQueryService.llmQuery(text);
	}

}
