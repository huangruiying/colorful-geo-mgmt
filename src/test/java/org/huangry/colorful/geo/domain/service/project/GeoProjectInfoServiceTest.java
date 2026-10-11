package org.huangry.colorful.geo.domain.service.project;

import org.huangry.colorful.geo.domain.model.Project;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoProjectInfoEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 项目创建规则回归；模拟持久化边界，验证无效输入不写库与可选列表规范化。 */
class GeoProjectInfoServiceTest {
    /** 完整基础资料产生项目 ID，列表去重并保持中文与英文内容。 */
    @Test
    void shouldCreateProjectWithNormalizedLists() {
        GeoProjectInfoService service = spy(new GeoProjectInfoService());
        Project project = validProject();
        project.setAliases(List.of(" 品牌 ", "品牌", "Brand"));
        Project saved = new Project(); saved.setId(12L);
        doAnswer(invocation -> {
            invocation.getArgument(0, GeoProjectInfoEntity.class).setId(12L);
            return true;
        }).when(service).save(any(GeoProjectInfoEntity.class));
        doReturn(saved).when(service).getProject(anyLong());
        assertEquals(12L, service.createProject(project).getId());
        ArgumentCaptor<GeoProjectInfoEntity> entity = ArgumentCaptor.forClass(GeoProjectInfoEntity.class);
        verify(service).save(entity.capture());
        assertEquals(List.of("品牌", "Brand"), entity.getValue().getAliases());
        assertEquals(List.of(), entity.getValue().getKeywords());
        assertEquals("测试产品", entity.getValue().getName());
    }

    /** 所有基础必填字段均在写库前校验。 */
    @ParameterizedTest
    @ValueSource(strings = {"name", "websiteUrl", "industry", "coreFeatures", "targetAudience"})
    void shouldRejectMissingRequiredInformation(String field) throws Exception {
        GeoProjectInfoService service = spy(new GeoProjectInfoService());
        Project project = validProject();
        Project.class.getMethod("set" + Character.toUpperCase(field.charAt(0)) + field.substring(1), String.class)
                .invoke(project, " ");
        assertThrows(IllegalArgumentException.class, () -> service.createProject(project));
        verify(service, never()).save(any(GeoProjectInfoEntity.class));
    }

    /** 脚本协议和不完整的地址不可作为资料来源。 */
    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///tmp/source", "https://", "https://example.com/a b"})
    void shouldRejectUnsafeOrInvalidWebsiteUrl(String url) {
        GeoProjectInfoService service = spy(new GeoProjectInfoService());
        Project project = validProject(); project.setWebsiteUrl(url);
        assertThrows(IllegalArgumentException.class, () -> service.createProject(project));
        verify(service, never()).save(any(GeoProjectInfoEntity.class));
    }

    /** 字段容量限制应返回输入错误，不等到数据库截断资料。 */
    @Test
    void shouldRejectOversizedFieldsAndBlankListEntry() {
        GeoProjectInfoService service = new GeoProjectInfoService();
        Project project = validProject(); project.setName("长".repeat(201));
        assertThrows(IllegalArgumentException.class, () -> service.createProject(project));
        project.setName("产品"); project.setKeywords(List.of(" "));
        assertThrows(IllegalArgumentException.class, () -> service.createProject(project));
    }

    /** 提供不依赖真实数据库的合法基础输入。 */
    private Project validProject() {
        Project project = new Project();
        project.setName(" 测试产品 "); project.setWebsiteUrl("https://example.com");
        project.setIndustry("软件"); project.setCoreFeatures("资料管理"); project.setTargetAudience("运营人员");
        return project;
    }
}
