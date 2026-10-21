package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSON;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.presentation.task.DiagnosisTaskWorker;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 执行故障隔离测试：模型失败继续执行诊断，保存失败停止外部调用。 */
class DiagnosisTaskWorkerTest {
    /** 多次启动事件只提交一个轮询，停机不会关闭统一线程池。 */
    @Test
    void shouldSubmitPollingOnceToSharedExecutor() {
        var worker = new DiagnosisTaskWorker();
        var executor = mock(org.springframework.core.task.TaskExecutor.class);
        ReflectionTestUtils.setField(worker, "enabled", true);
        ReflectionTestUtils.setField(worker, "taskExecutor", executor);
        worker.start();
        worker.start();
        worker.close();
        worker.start();
        verify(executor, times(1)).execute(any(Runnable.class));
        verifyNoMoreInteractions(executor);
    }

    /** 排队期间关闭的任务不执行数据库轮询，避免停机后领取任务。 */
    @Test
    void shouldSkipQueuedPollingAfterClose() {
        var worker = new DiagnosisTaskWorker();
        var executor = mock(org.springframework.core.task.TaskExecutor.class);
        var service = mock(GeoDiagnosisTaskService.class);
        ReflectionTestUtils.setField(worker, "enabled", true);
        ReflectionTestUtils.setField(worker, "taskExecutor", executor);
        ReflectionTestUtils.setField(worker, "service", service);
        worker.start();
        var runnable = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(runnable.capture());
        worker.close();
        runnable.getValue().run();
        verifyNoInteractions(service);
    }
    /** 空白回答不算成功的诊断结果，不自动重试，也不落成功响应。 */
    @Test
    void shouldRecordBlankAnswerAsFailureWithoutRetry() throws Exception {
        var service = mock(GeoDiagnosisTaskService.class);
        var client = mock(LLMClient.class);
        var worker = worker(service, client);
        var sample = sample(1L, "独立问题");
        var channel = new GeoLlmChannelEntity(); channel.setId(7L);
        when(service.claimNextResult(5L)).thenReturn(sample, null);
        when(service.confirmTaskChannel(sample)).thenReturn(channel);
        when(client.query(any(LLMQueryRequest.class), same(channel))).thenReturn(LLMResponse.withoutCitations(" "));
        ReflectionTestUtils.invokeMethod(worker, "execute", 5L);
        verify(service).updateResultFailed(eq(sample), eq("渠道不可用或模型未返回有效回答，请检查模型配置"), anyLong());
        verify(service, never()).updateResultSucceeded(any(), any(), anyLong());
        verify(client, times(1)).query(any(LLMQueryRequest.class), same(channel));
    }
    /** 每个问题独立请求，首个超时不重试，后续问题继续落库。 */
    @Test
    void shouldContinueAfterModelFailureWithoutRetryingOrAddingContext() throws Exception {
        var service = mock(GeoDiagnosisTaskService.class);
        var client = mock(LLMClient.class);
        var worker = worker(service, client);
        var first = sample(1L, "问题一");
        var second = sample(2L, "问题二");
        var channel = new GeoLlmChannelEntity(); channel.setId(7L);
        when(service.claimNextResult(5L)).thenReturn(first, second, null);
        when(service.confirmTaskChannel(any())).thenReturn(channel);
        when(client.query(new LLMQueryRequest("问题一"), channel)).thenThrow(
                new LLMClientException("private-remote-message", new SocketTimeoutException("private-key")));
        var response = LLMResponse.withoutCitations("独立回答");
        when(client.query(new LLMQueryRequest("问题二"), channel)).thenReturn(response);
        ReflectionTestUtils.invokeMethod(worker, "execute", 5L);
        verify(client, times(1)).query(new LLMQueryRequest("问题一"), channel);
        verify(client, times(1)).query(new LLMQueryRequest("问题二"), channel);
        verify(service).updateResultFailed(eq(first), eq("模型调用超时，请检查统一 HTTP 超时配置"), anyLong());
        verify(service).updateResultSucceeded(eq(second), same(response), anyLong());
        verify(service).finishTask(5L);
    }

    /** 模型已返回但落库失败时，不再次发出请求，也不领取后续诊断。 */
    @Test
    void shouldStopCallingModelsAfterPersistenceFailure() throws Exception {
        var service = mock(GeoDiagnosisTaskService.class);
        var client = mock(LLMClient.class);
        var worker = worker(service, client);
        var sample = sample(1L, "独立问题");
        var channel = new GeoLlmChannelEntity(); channel.setId(7L);
        var response = LLMResponse.withoutCitations("回答");
        when(service.claimNextResult(5L)).thenReturn(sample);
        when(service.confirmTaskChannel(sample)).thenReturn(channel);
        when(client.query(any(LLMQueryRequest.class), same(channel))).thenReturn(response);
        doThrow(new DataAccessResourceFailureException("private-sql"))
                .when(service).updateResultSucceeded(eq(sample), same(response), anyLong());
        assertThrows(DataAccessResourceFailureException.class, () -> ReflectionTestUtils.invokeMethod(worker, "execute", 5L));
        verify(service, times(1)).claimNextResult(5L);
        verify(client, times(1)).query(any(LLMQueryRequest.class), same(channel));
        verify(service, never()).updateResultFailed(any(), anyString(), anyLong());
    }

    /**
     * 用固定的有效会话锁测试执行循环，不创建调度线程或外部连接。
     * @param service 持久化替身
     * @param client 模型替身
     * @return 待测执行组件
     */
    private DiagnosisTaskWorker worker(GeoDiagnosisTaskService service, LLMClient client) throws Exception {
        var worker = new DiagnosisTaskWorker();
        var snapshots = new DiagnosisTaskSnapshots();

        ReflectionTestUtils.setField(worker, "service", service);
        ReflectionTestUtils.setField(worker, "llmClient", client);
        ReflectionTestUtils.setField(worker, "snapshots", snapshots);
        var lease = mock(Connection.class); when(lease.isValid(2)).thenReturn(true);
        ReflectionTestUtils.setField(worker, "lease", lease);
        return worker;
    }

    /**
     * 构造仅包含独立问题的冻结输入。
     * @param id 诊断结果 ID
     * @param text 问题原文
     * @return 诊断结果
     */
    private GeoDiagnosisTaskResultEntity sample(long id, String text) {
        var sample = new GeoDiagnosisTaskResultEntity();
        sample.setId(id); sample.setTaskId(5L); sample.setQuestionSnapshot("{\"text\":\"" + text + "\"}");
        return sample;
    }
}
