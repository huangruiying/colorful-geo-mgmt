package org.huangry.colorful.geo.application.project;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.project.model.ProjectImportResponse;
import org.huangry.colorful.geo.domain.service.project.ProjectInfoImportExportService;
import org.huangry.colorful.geo.infrastructure.common.utils.JsonUtils;
import org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 项目迁移的文件输入输出及接口模型组装；事务和业务关联重建交给领域服务，不直接操作数据库。 */
@Component
@Slf4j
public class ProjectInfoImportExportBusiness {
    /** 单个项目文件最大 50 MiB，HTTP 有界读取与文件生成共用此上限。 */
    public static final int MAX_FILE_BYTES = 50 * 1024 * 1024;
    @Resource
    private ProjectInfoImportExportService importExportService;

    /**
     * 取得一致性项目数据后生成版本化 JSON 文件，超出文件上限则拒绝下载。
     * @param projectId 来源项目 ID
     * @return JSON 文件字节
     */
    public byte[] exportProject(long projectId) {
        // 取得领域服务已校验的完整快照，序列化不延长数据库读事务
        ObjectNode archive = importExportService.exportProject(projectId);
        // 使用通用 JSON 能力生成实际文件，按字节限制导出大小
        byte[] bytes = JsonUtils.toPrettyBytes(archive);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("项目数据超过 50 MiB，暂不支持导出");
        }
        log.info("项目导出完成 projectId={} bytes={}", projectId, bytes.length);
        return bytes;
    }

    /**
     * 解析文件并调用事务服务新建项目，提交成功后组装接口响应；不覆盖既有项目。
     * @param bytes 项目 JSON 文件
     * @return 新项目及各分区实际导入数量
     */
    public ProjectImportResponse importProject(byte[] bytes) {
        // 1. 文件大小、JSON 语法和根节点属于输入边界，业务规则由领域层继续检查
        ObjectNode archive = parseArchive(bytes);
        // 2. 在领域服务代理内完成完整事务，任何写入失败都不组装成功响应
        long projectId = importExportService.importProject(archive);
        // 3. 只在应用层构造接口 Response，领域层不依赖接口模型
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ProjectInfoImportExportTable table : ProjectInfoImportExportTable.values()) {
            counts.put(table.section, archive.withArray(table.section).size());
        }
        return new ProjectImportResponse(Long.toString(projectId), archive.withArray("project").get(0).path("name").asText(), counts);
    }

    /**
     * 校验项目文件的体积、JSON 语法及根对象，不在通用 JSON 工具中引入项目提示或限制。
     * @param bytes UTF-8 文件内容
     * @return 文件根对象
     * @throws IllegalArgumentException 文件为空、超限或 JSON 格式错误
     */
    public ObjectNode parseArchive(byte[] bytes) {
        // 实际字节数决定是否接受文件，不信任请求声明的长度
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("请选择非空且不超过 50 MiB 的项目 JSON 文件");
        }
        // 通用解析异常在应用边界转换为项目文件提示，不回显正文
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = JsonUtils.readTree(bytes);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("项目文件不是有效的 JSON", exception);
        }
        if (!(root instanceof ObjectNode archive)) {
            throw new IllegalArgumentException("项目文件根节点必须是对象");
        }
        return archive;
    }
}
