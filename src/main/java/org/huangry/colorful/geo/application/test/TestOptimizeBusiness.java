package org.huangry.colorful.geo.application.test;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRequest;
import org.huangry.colorful.geo.domain.model.ContentOptimizationResult;
import org.huangry.colorful.geo.domain.service.optimization.ContentOptimizationService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 *
 * @author huangry
 * Created in 2026/9/15 18:44
 */
@Component
public class TestOptimizeBusiness {

	@Resource
	private ContentOptimizationService contentOptimizationService;

	public ContentOptimizationResult testOptimize(String text) {

		ContentOptimizationRequest request = new ContentOptimizationRequest(
				text, "出海陪跑", List.of(), List.of()
		);

		return contentOptimizationService.optimizeContent(request);
	}

}
