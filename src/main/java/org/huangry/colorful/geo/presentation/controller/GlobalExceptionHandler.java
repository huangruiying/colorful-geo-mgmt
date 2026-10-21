package org.huangry.colorful.geo.presentation.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * 管理后台接口共用的异常映射，统一返回 {@link ProblemDetail}，前端读取 {@code detail} 展示原因。
 * <p>
 * 只处理各模块语义一致的两类异常：输入不合法返回 400，记录不存在返回 404。
 * 状态冲突、存储失败、模型调用失败等含模块语义的异常仍由各 Controller 自行映射，Controller 内的处理优先于本类。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 输入校验失败，原因由业务代码写明，直接返回给页面。
     *
     * @param exception 校验异常
     * @param request 当前请求，用于记录接口位置
     * @return HTTP 400 及失败原因
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidRequest(IllegalArgumentException exception, HttpServletRequest request) {
        log.warn("接口输入校验失败 httpStatus=400 method={} uri={} reason={}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * 请求的记录不存在。
     *
     * @param exception 记录缺失异常
     * @param request 当前请求，用于记录接口位置
     * @return HTTP 404 及缺失说明
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail missingRecord(NoSuchElementException exception, HttpServletRequest request) {
        log.warn("接口请求记录不存在 httpStatus=404 method={} uri={} reason={}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }
}
