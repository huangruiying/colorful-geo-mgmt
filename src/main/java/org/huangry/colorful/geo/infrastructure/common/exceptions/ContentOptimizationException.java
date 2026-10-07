package org.huangry.colorful.geo.infrastructure.common.exceptions;

/**
 * 内容优化服务执行失败异常。
 *
 * <p>用于表达输入不满足 GEO 优化边界、策略不允许发布或大模型改写失败等业务失败语义。</p>
 *
 * @author huangry
 */
public class ContentOptimizationException extends RuntimeException {

    public ContentOptimizationException(String message) {
        super(message);
    }

    public ContentOptimizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
