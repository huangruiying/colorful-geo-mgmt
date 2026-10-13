package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.AbstractResponsesApiStrategy;
import org.springframework.stereotype.Component;

/** 兼容 Responses（普通问答）适配，复用父类交互及解析，由 Spring 注册；不选择渠道。 */
@Component
public class CompatibleResponsesApiStrategy extends AbstractResponsesApiStrategy {
}
