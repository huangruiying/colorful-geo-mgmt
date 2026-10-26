package org.huangry.colorful.geo.domain.service.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.common.utils.JsonUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable.*;

/** 项目迁移的版本、终态、字段及关联业务校验；复用项目和渠道服务的既有规则，不负责 JSON 读写或数据库写入。 */
@Service
public class ProjectInfoImportExportValidator {
    @Resource
    private GeoProjectInfoService projectService;
    @Resource
    private GeoLlmChannelService channelService;
    /** 迁移渠道快照允许保留的历史展示字段，连接配置不在迁移范围。 */
    public static final Set<String> CHANNEL_DISPLAY_FIELDS = Set.of("channelCode", "channelName", "providerCode", "modelName", "protocolType");

    /**
     * 校验版本、字段、唯一标识、终态及完整关联；任何错误使整个文件拒绝导入。
     * @param archive 项目文件
     */
    public void validateArchive(ObjectNode archive) {
        // 1. 只接受当前版本和固定分区，项目分区必须恰好一条
        Set<String> sections = new HashSet<>(Set.of("format", "version"));
        Arrays.stream(values()).forEach(table -> sections.add(table.section));
        validateFields(archive, sections, "项目文件");
        if (!"colorful-geo-project".equals(archive.path("format").asText())
                || !archive.path("version").isInt() || archive.path("version").intValue() != 1) {
            throw new IllegalArgumentException("不支持的项目文件格式或版本");
        }
        Map<ProjectInfoImportExportTable, Map<Long, ObjectNode>> rows = indexArchiveRows(archive);
        if (rows.get(PROJECT).size() != 1) {
            throw new IllegalArgumentException("一个文件必须且只能包含一个项目");
        }
        // 2. 校验行字段和终态；失败报告下未完成的回答分析保留，但不会自动领取
        for (ProjectInfoImportExportTable table : values()) {
            for (ObjectNode row : rows.get(table).values()) {
                validateArchiveRow(table, row);
            }
        }
        // 3. 核对跨表归属，避免文件引用现有项目或别的任务的数据
        validateRelations(rows);
        validateSnapshots(rows);
        validatePortableReferences(archive);
    }

    /**
     * 为每个分区建立来源 ID 索引，拒绝缺失分区、空行和重复 ID。
     * @param archive 文件对象
     * @return 各表的来源行索引
     */
    private Map<ProjectInfoImportExportTable, Map<Long, ObjectNode>> indexArchiveRows(ObjectNode archive) {
        Map<ProjectInfoImportExportTable, Map<Long, ObjectNode>> rows = new EnumMap<>(ProjectInfoImportExportTable.class);
        for (ProjectInfoImportExportTable table : values()) {
            if (!(archive.get(table.section) instanceof ArrayNode array)) {
                throw new IllegalArgumentException("文件缺少数据分区：" + table.section);
            }
            Map<Long, ObjectNode> indexed = new LinkedHashMap<>();
            for (JsonNode value : array) {
                if (!(value instanceof ObjectNode row) || indexed.putIfAbsent(JsonUtils.getPositiveLong(row, "id"), row) != null) {
                    throw new IllegalArgumentException("文件存在无效行或重复 ID：" + table.section);
                }
            }
            rows.put(table, indexed);
        }
        return rows;
    }

    /**
     * 读取必填渠道编码，沿用渠道配置入口的字符范围，不接受空白、控制字符或超长引用。
     * @param row 渠道引用对象
     * @param field 编码字段名
     * @return 原始渠道编码，按精确编码匹配
     */
    public String getChannelCode(JsonNode row, String field) {
        // 读取文件字段，拒绝将其他 JSON 类型隐式当作渠道编码
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("文件中存在无效渠道编码");
        }
        // 与模型配置复用同一个编码规则，保留精确匹配所需的原始值
        channelService.validateChannelCode(value.asText());
        return value.asText();
    }

    /**
     * 检查行白名单、常用类型和可被后台领取的状态。
     * @param table 数据分区
     * @param row 文件行
     */
    private void validateArchiveRow(ProjectInfoImportExportTable table, ObjectNode row) {
        // 1. 文件中的渠道外键只使用编码，既不导入配置也不信任来源数据库渠道 ID
        Set<String> fields = new HashSet<>(table.columns);
        if (fields.remove("channel_id")) {
            fields.add("channel_code");
            getChannelCode(row, "channel_code");
        }
        validateFields(row, fields, table.section);
        // 2. 每列必须显式出现并符合存储类型，项目额外校验列表与地址的展示边界
        for (String field : fields) {
            if (!row.has(field)) {
                throw new IllegalArgumentException("文件缺少字段：" + table.section + "." + field);
            }
            validateColumnType(field, row.get(field));
        }
        if (table == PROJECT) {
            validateProjectColumns(row);
        }
        // 3. 拒绝会被后台继续领取的主记录；失败报告的待判定子行可保留
        if (table == TASK || table == RESULT || table == REPORT) {
            Set<String> terminal = table == TASK ? Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED") : Set.of("SUCCEEDED", "FAILED");
            if (!terminal.contains(row.path("status").asText())) {
                throw new IllegalArgumentException("存在未结束或状态无效的诊断任务、结果或报告，请完成处理后再导出");
            }
        }
        if (table == ANALYSIS && !Set.of("PENDING", "SUCCEEDED", "FAILED").contains(row.path("status").asText())) {
            throw new IllegalArgumentException("回答分析状态无效");
        }
    }

    /**
     * 校验标量、时间及 JSON 类型，数据库约束继续负责长度、枚举及唯一性兜底。
     * @param field 白名单字段
     * @param value 文件字段值
     */
    private void validateColumnType(String field, JsonNode value) {
        if (value.isNull()) {
            return;
        }
        if (JSON_COLUMNS.contains(field)) {
            validateJsonColumn(field, value);
        } else if (field.equals("id") || field.endsWith("_id") && !field.equals("request_id")) {
            JsonUtils.getPositiveLong(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().set(field, value), field);
        } else if (field.endsWith("_at")) {
            try {
                LocalDateTime.parse(value.asText());
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("日期时间格式无效：" + field, exception);
            }
        } else if (Set.of("repeat_count", "sort_order", "question_sort_order", "repeat_index", "duration_ms").contains(field)) {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalArgumentException("字段必须是整数：" + field);
            }
        } else if (Set.of("description_accurate", "manually_corrected").contains(field)) {
            if (!value.isBoolean() && !(value.isInt() && (value.intValue() == 0 || value.intValue() == 1))) {
                throw new IllegalArgumentException("字段必须是布尔值：" + field);
            }
        } else if (!value.isTextual()) {
            throw new IllegalArgumentException("字段必须是文本：" + field);
        }
    }

    /**
     * 校验 JSON 列的容器类型，避免导入后 ORM 或报告页面无法读取。
     * @param field JSON 列名
     * @param value 非空字段值
     */
    private void validateJsonColumn(String field, JsonNode value) {
        boolean array = Set.of("aliases", "competitors", "keywords", "citations", "brands", "points", "sources").contains(field);
        if (array ? !value.isArray() : !value.isObject()) {
            throw new IllegalArgumentException("JSON 字段类型不符合要求：" + field);
        }
    }

    /**
     * 保持项目资料的可展示类型和 HTTP(S) 地址边界，不在导入时请求来源地址。
     * @param row 项目文件行
     */
    private void validateProjectColumns(ObjectNode row) {
        // 列表列会由 ORM 读取为字符串数组，不接受结构化对象混入品牌或关键词
        for (String field : List.of("aliases", "competitors", "keywords")) {
            if (!row.path(field).isArray()) {
                throw new IllegalArgumentException("项目列表字段必须是文本数组：" + field);
            }
            for (JsonNode entry : row.path(field)) {
                if (!entry.isTextual()) {
                    throw new IllegalArgumentException("项目列表字段必须是文本数组：" + field);
                }
            }
        }
        // 资料地址保持与项目创建入口相同的协议限制，避免文件绕过脚本地址校验
        projectService.validateProjectWebsiteUrl(row.path("website_url").asText());
    }

    /**
     * 确认项目、方案、任务、报告及回答分析全部在同一文件内闭合。
     * @param rows 来源行索引
     */
    private void validateRelations(Map<ProjectInfoImportExportTable, Map<Long, ObjectNode>> rows) {
        // 主链外键必须存在；历史问题允许只留快照，因为编辑方案会删除原问题
        for (ObjectNode plan : rows.get(PLAN).values()) {
            requireParent(rows, PROJECT, plan, "project_id");
        }
        for (ProjectInfoImportExportTable table : List.of(QUESTION, PLAN_CHANNEL, TASK)) {
            for (ObjectNode row : rows.get(table).values()) {
                requireParent(rows, PLAN, row, "plan_id");
            }
        }
        for (ObjectNode task : rows.get(TASK).values()) {
            requireParent(rows, PROJECT, task, "project_id");
        }
        for (ProjectInfoImportExportTable table : List.of(RESULT, REPORT)) {
            for (ObjectNode row : rows.get(table).values()) {
                requireParent(rows, TASK, row, "task_id");
            }
        }
        // 回答分析不仅要指向存在的结果，还必须属于同一诊断任务
        for (ObjectNode analysis : rows.get(ANALYSIS).values()) {
            ObjectNode report = requireParent(rows, REPORT, analysis, "report_id");
            ObjectNode result = requireParent(rows, RESULT, analysis, "task_result_id");
            if (JsonUtils.getPositiveLong(report, "task_id") != JsonUtils.getPositiveLong(result, "task_id")) {
                throw new IllegalArgumentException("回答分析引用了其他任务的诊断结果");
            }
        }
    }

    /**
     * 取得文件内父行，拒绝悬空或跨项目外键。
     * @param rows 文件行索引
     * @param parent 父分区
     * @param child 子行
     * @param field 外键字段
     * @return 父行
     */
    private ObjectNode requireParent(Map<ProjectInfoImportExportTable, Map<Long, ObjectNode>> rows,
                                     ProjectInfoImportExportTable parent, ObjectNode child, String field) {
        ObjectNode row = rows.get(parent).get(JsonUtils.getPositiveLong(child, field));
        if (row == null) {
            throw new IllegalArgumentException("项目文件关联不完整或跨项目：" + field);
        }
        return row;
    }

    /**
     * 校验历史快照与外键一致，结果引用的问题和渠道必须出现在所属任务快照中。
     * @param rows 文件行索引
     */
    private void validateSnapshots(Map<ProjectInfoImportExportTable, Map<Long, ObjectNode>> rows) {
        // 1. 任务快照保留创建时的配置，但归属不能与文件行外键矛盾
        for (ObjectNode task : rows.get(TASK).values()) {
            JsonNode snapshot = task.path("plan_snapshot");
            if (!snapshot.isObject() || !snapshot.path("questions").isArray() || !snapshot.path("channels").isArray()
                    || !snapshot.path("project").isObject() || JsonUtils.getPositiveLong(snapshot, "planId") != JsonUtils.getPositiveLong(task, "plan_id")
                    || JsonUtils.getPositiveLong(snapshot.path("project"), "id") != JsonUtils.getPositiveLong(task, "project_id")) {
                throw new IllegalArgumentException("任务方案快照结构或项目关联不一致");
            }
        }
        // 2. 每条结果的问题及渠道必须来自该任务的冻结配置
        for (ObjectNode result : rows.get(RESULT).values()) {
            ObjectNode task = rows.get(TASK).get(JsonUtils.getPositiveLong(result, "task_id"));
            validateResultSnapshot(result, task.path("plan_snapshot"));
        }
        // 3. 成功报告不应包含待判定回答，失败报告的残留待判定记录不会被后台单独领取
        for (ObjectNode analysis : rows.get(ANALYSIS).values()) {
            ObjectNode report = rows.get(REPORT).get(JsonUtils.getPositiveLong(analysis, "report_id"));
            if ("SUCCEEDED".equals(report.path("status").asText()) && "PENDING".equals(analysis.path("status").asText())) {
                throw new IllegalArgumentException("成功报告不能包含待判定的回答分析");
            }
        }
    }

    /**
     * 检查单条回答的问题标识和渠道编码均来自任务创建时的配置。
     * @param result 单条诊断结果
     * @param snapshot 所属任务的方案快照
     */
    private void validateResultSnapshot(ObjectNode result, JsonNode snapshot) {
        // 先确认结果行与单条快照的标识一致
        long questionId = JsonUtils.getPositiveLong(result, "question_id");
        String code = getChannelCode(result, "channel_code");
        if (JsonUtils.getPositiveLong(result.path("question_snapshot"), "id") != questionId
                || !code.equals(getChannelCode(result.path("channel_snapshot"), "channelCode"))) {
            throw new IllegalArgumentException("诊断结果与问题或渠道快照不一致");
        }
        // 再确认这些引用存在于任务的完整方案快照中
        boolean hasQuestion = false;
        for (JsonNode question : snapshot.path("questions")) {
            hasQuestion |= JsonUtils.getPositiveLong(question, "id") == questionId;
        }
        boolean hasChannel = false;
        for (JsonNode channel : snapshot.path("channels")) {
            hasChannel |= code.equals(getChannelCode(channel, "channelCode"));
        }
        if (!hasQuestion || !hasChannel) {
            throw new IllegalArgumentException("诊断结果引用了任务快照之外的问题或渠道");
        }
    }

    /**
     * 文件只能含可移植渠道引用，不允许原始渠道 ID、凭据或类型元数据进入存储。
     * @param node 待校验文件树
     */
    private void validatePortableReferences(JsonNode node) {
        JsonUtils.visitObjects(node, "", (context, object) -> {
            var fields = object.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                String normalized = field.replace("_", "").toLowerCase(Locale.ROOT);
                if (Set.of("apikey", "authorization", "cookie", "accesstoken", "refreshtoken", "@type", "@class").contains(normalized)
                        || field.equals("channelId") || field.equals("channel_id")) {
                    throw new IllegalArgumentException("项目文件含不允许迁移的渠道标识或认证字段");
                }
            }
            return true;
        });
    }

    /**
     * 拒绝未声明字段，防止文件携带渠道配置或被忽略的错误字段。
     * @param node 被校验对象
     * @param allowed 固定字段集合
     * @param section 所属分区
     */
    private void validateFields(ObjectNode node, Set<String> allowed, String section) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw new IllegalArgumentException("项目文件含不支持的字段：" + section);
            }
        }
    }
    /**
     * 限制渠道快照字段，拒绝向历史存储注入密钥或连接配置。
     * @param snapshot 文件中的渠道快照
     */
    public void validateChannelSnapshot(ObjectNode snapshot) {
        var fields = snapshot.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!CHANNEL_DISPLAY_FIELDS.contains(field) || !snapshot.get(field).isValueNode()) {
                throw new IllegalArgumentException("渠道快照只能包含编码及历史展示资料");
            }
        }
    }
}
