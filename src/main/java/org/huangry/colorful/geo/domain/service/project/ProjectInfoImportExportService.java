package org.huangry.colorful.geo.domain.service.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.repository.ProjectInfoImportExportDao;
import org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable;
import org.springframework.stereotype.Service;
import org.huangry.colorful.geo.infrastructure.common.utils.JsonUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable.*;

/**
 * 项目导入导出的主流程：把一个项目及其诊断历史导出为 JSON 树，或把 JSON 树导入为一个新项目。
 * <p>导出在同一数据库快照内读取八张表，并把渠道 ID 换成可移植的渠道编码；导入在一个事务内按依赖顺序写入，重建全部 ID。</p>
 * <p>不调用模型、不覆盖已有项目；文件读写由 {@code ProjectInfoImportExportBusiness} 负责，文件校验由 {@link ProjectInfoImportExportValidator} 负责。</p>
 */
@Service
public class ProjectInfoImportExportService {
    @Resource
    private ProjectInfoImportExportDao importExportDao;
    @Resource
    private ProjectInfoImportExportValidator validator;
    /** 数据行的表级外键字段及其指向的表；导出时转成字符串，导入时换成新 ID。 */
    private static final Map<String, ProjectInfoImportExportTable> FOREIGN_KEYS = Map.of(
            "project_id", PROJECT, "plan_id", PLAN, "question_id", QUESTION,
            "task_id", TASK, "report_id", REPORT, "task_result_id", RESULT);
    /** 结构化快照及报告证据中的引用字段及其指向的表，不是通用 JSON 遍历规则。 */
    private static final Map<String, ProjectInfoImportExportTable> REFERENCE_FIELDS = Map.of(
            "projectId", PROJECT, "planId", PLAN, "questionId", QUESTION,
            "taskId", TASK, "resultId", RESULT, "taskResultId", RESULT, "reportId", REPORT);
    /** 全部文件分区名称，用于识别数据行自身的 id 字段。 */
    private static final Set<String> SECTIONS = Arrays.stream(values()).map(table -> table.section).collect(Collectors.toSet());

    /**
     * 一致性读取项目与七个诊断关联分区；有未结束任务、结果或报告时拒绝导出。
     * @param projectId 来源项目 ID
     * @return 不含渠道连接配置的版本化项目数据；文件序列化由应用层完成
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode exportProject(long projectId) {
        // 1. 在同一个数据库快照内加载固定关联范围，避免方案修改和后台执行造成混合版本
        ObjectNode archive = JsonNodeFactory.instance.objectNode().put("format", "colorful-geo-project").put("version", 1);
        for (ProjectInfoImportExportTable table : values()) {
            ArrayNode rows = importExportDao.listProjectRows(table, projectId);
            archive.set(table.section, rows);
        }
        if (archive.withArray(PROJECT.section).isEmpty()) {
            throw new NoSuchElementException("项目不存在");
        }
        // 2. 当前方案使用当前渠道编码，历史结果及报告使用各自任务的冻结编码
        Map<Long, String> channels = new LinkedHashMap<>(importExportDao.listChannelCodes());
        exportChannelReferences(archive, channels);
        // 3. 清除来源幂等键、处理结构化标识后校验闭环，保证导出的文件也满足导入约束
        archive.withArray(TASK.section).forEach(task -> ((ObjectNode) task).putNull("request_id"));
        validator.validateArchive(archive);
        return archive;
    }

    /**
     * 按当前方案及各轮任务分别解释渠道标识，避免渠道改码后覆盖旧任务的历史编码。
     * @param archive 来源文件树
     * @param channels 当前数据库渠道编码
     */
    private void exportChannelReferences(ObjectNode archive, Map<Long, String> channels) {
        // 1. 每个任务单独建立冻结渠道索引，同一个来源 ID 在不同时期可以对应不同编码
        Map<Long, Map<Long, String>> taskChannels = new LinkedHashMap<>();
        for (JsonNode task : archive.withArray(TASK.section)) {
            Map<Long, String> frozen = new LinkedHashMap<>(channels);
            collectHistoricalChannels(task.path("plan_snapshot"), frozen);
            taskChannels.put(JsonUtils.getPositiveLong(task, "id"), frozen);
        }
        // 2. 按所属任务转换历史行；当前方案渠道仍按当前配置匹配
        for (ProjectInfoImportExportTable table : values()) {
            for (JsonNode value : archive.withArray(table.section)) {
                ObjectNode row = (ObjectNode) value;
                Map<Long, String> selected = row.has("task_id") ? taskChannels.get(JsonUtils.getPositiveLong(row, "task_id")) : channels;
                if (selected == null) {
                    throw new IllegalArgumentException("诊断数据缺少所属任务，无法迁移");
                }
                if (row.has("channel_id")) {
                    String code = table == RESULT ? validator.getChannelCode(row.path("channel_snapshot"), "channelCode")
                            : requireChannelCode(selected, JsonUtils.getPositiveLong(row, "channel_id"));
                    row.put("channel_code", code);
                    row.remove("channel_id");
                }
                exportReferences(row, table.section, selected);
            }
        }
    }

    /**
     * 新建一份项目及完整诊断历史；项目一行，其余七表各按文件行数写入，状态保持终态。
     * 所有写入同一事务，失败全部回滚；问题历史 ID 临时预留后删除，不增加当前方案问题。
     * 任务幂等键清空，不恢复执行、不调用模型；重复成功导入会另建项目副本。
     * @param archive 已解析的项目 JSON
     * @return 新项目 ID；接口响应及文件分区数量由应用层组装
     */
    @Transactional(rollbackFor = Exception.class)
    public long importProject(ObjectNode archive) {
        // 1. 先校验完整文件并一次性匹配所有渠道，缺失时在任何写入之前拒绝
        validator.validateArchive(archive);
        Map<String, Long> channels = confirmTargetChannels(archive);
        Map<ProjectInfoImportExportTable, Map<Long, Long>> ids = new EnumMap<>(ProjectInfoImportExportTable.class);
        Arrays.stream(values()).forEach(table -> ids.put(table, new LinkedHashMap<>()));
        // 2. 依赖顺序插入项目、方案和当前问题，再为快照中已删除的问题预留新的追溯标识
        for (ProjectInfoImportExportTable table : List.of(PROJECT, PLAN, QUESTION)) {
            insertArchiveRows(archive, table, ids, channels);
        }
        reserveHistoricalQuestions(archive, ids);
        // 3. 插入渠道关联、历史任务、结果、报告和回答分析；所有嵌入引用随父行映射
        for (ProjectInfoImportExportTable table : List.of(PLAN_CHANNEL, TASK, RESULT, REPORT, ANALYSIS)) {
            insertArchiveRows(archive, table, ids, channels);
        }
        return ids.get(PROJECT).values().iterator().next();
    }

    /**
     * 收集并精确匹配方案及历史使用的全部渠道；缺失编码一次列出，不新建渠道或更改启用状态。
     * @param archive 文件对象
     * @return 编码与目标渠道 ID
     */
    private Map<String, Long> confirmTargetChannels(ObjectNode archive) {
        // 1. 覆盖方案、任务和报告历史使用的全部编码
        Set<String> codes = new TreeSet<>();
        collectChannelCodes(archive, codes);
        Map<String, Long> channels = new LinkedHashMap<>();
        // 2. 仅锁定目标环境的匹配渠道，避免校验后被删除或改码，不读取密钥
        importExportDao.lockChannelCodes(codes).forEach((id, code) -> channels.put(code, id));
        // 3. 缺失项一次提示，禁止静默跳过或自动创建渠道
        Set<String> missing = new TreeSet<>(codes);
        missing.removeAll(channels.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("请先在模型配置中配置以下渠道编码：" + String.join("、", missing));
        }
        return channels;
    }

    /**
     * 为一个分区映射外键与嵌入引用后逐行插入，并保存新 ID 供后续子表使用。
     * @param archive 来源文件
     * @param table 当前分区
     * @param ids 各表标识映射
     * @param channels 目标渠道索引
     */
    private void insertArchiveRows(ObjectNode archive, ProjectInfoImportExportTable table,
                                   Map<ProjectInfoImportExportTable, Map<Long, Long>> ids, Map<String, Long> channels) {
        for (JsonNode source : archive.withArray(table.section)) {
            // 1. 复制单行并重建表级外键，来源文件不被改写
            ObjectNode row = source.deepCopy();
            long oldId = JsonUtils.getPositiveLong(row, "id");
            mapRowForeignKeys(row, ids, channels);
            // 2. JSON 列均显式进入引用处理；回答原文和原始响应是文本，不改写其中的业务内容
            for (String column : JSON_COLUMNS) {
                if (row.hasNonNull(column)) {
                    importReferences(row.get(column), column, ids, channels);
                }
            }
            // 3. 保存新行后登记来源 ID 与新 ID，供后续子表引用
            ids.get(table).put(oldId, importExportDao.insertArchiveRow(table, row));
        }
    }

    /**
     * 只重建声明的表级外键，不接受来源幂等键或目标数据库已有项目 ID。
     * @param row 当前文件行
     * @param ids 各表标识映射
     * @param channels 目标渠道索引
     */
    private void mapRowForeignKeys(ObjectNode row, Map<ProjectInfoImportExportTable, Map<Long, Long>> ids, Map<String, Long> channels) {
        // 1. 重建项目到回答分析的内部外键，不访问目标环境的既有项目
        FOREIGN_KEYS.forEach((field, parent) -> {
            if (row.has(field)) {
                row.put(field, requireMappedId(ids, parent, JsonUtils.getPositiveLong(row, field)));
            }
        });
        // 2. 渠道仅关联目标环境中已配置的相同编码
        if (row.has("channel_code")) {
            row.put("channel_id", channels.get(validator.getChannelCode(row, "channel_code")));
            row.remove("channel_code");
        }
        // 3. 来源任务的创建请求已结束，不能把其幂等键带到新环境
        if (row.has("request_id")) {
            row.putNull("request_id");
        }
    }

    /**
     * 为只在历史快照中存在的问题申请新 ID，随即删除临时行；无问题外键的历史结果仍可追溯。
     * <p>前置条件：校验器已确认快照结构完整，且每条结果的问题都在所属任务的快照中，所以遍历任务快照即可覆盖全部结果引用。</p>
     * @param archive 来源文件
     * @param ids 各表新旧标识
     */
    private void reserveHistoricalQuestions(ObjectNode archive, Map<ProjectInfoImportExportTable, Map<Long, Long>> ids) {
        // 按任务方案快照的整轮问题预留，不只扫描当前方案，已删除的问题同样拿到新标识
        for (JsonNode task : archive.withArray(TASK.section)) {
            long planId = requireMappedId(ids, PLAN, JsonUtils.getPositiveLong(task, "plan_id"));
            for (JsonNode question : task.path("plan_snapshot").path("questions")) {
                reserveHistoricalQuestion(question, planId, ids);
            }
        }
    }

    /**
     * 仅为当前问题表已不存在的历史问题预留自增标识，不向当前方案追加已删除问题。
     * @param question 历史问题快照
     * @param planId 新方案 ID
     * @param ids 标识映射
     */
    private void reserveHistoricalQuestion(JsonNode question, long planId, Map<ProjectInfoImportExportTable, Map<Long, Long>> ids) {
        // 1. 当前问题或已预留的历史问题复用同一个新标识
        long oldId = JsonUtils.getPositiveLong(question, "id");
        if (ids.get(QUESTION).containsKey(oldId)) {
            return;
        }
        // 2. 使用历史资料构造临时问题，特殊顺序不与当前方案的正常题序冲突
        ObjectNode reserved = JsonNodeFactory.instance.objectNode().put("plan_id", planId).put("sort_order", Integer.MIN_VALUE);
        reserved.set("question_text", question.path("text"));
        reserved.set("source", question.path("source"));
        reserved.set("intent", question.path("intent"));
        reserved.set("brand_scope", question.path("brandScope"));
        reserved.set("language", question.path("language"));
        // 3. 仅借助自增序列分配不会与真实问题冲突的追溯 ID，临时行不离开本事务
        long id = importExportDao.insertArchiveRow(QUESTION, reserved);
        importExportDao.deleteReservedQuestion(id);
        ids.get(QUESTION).put(oldId, id);
    }


    /**
     * 读取快照中的历史渠道编码，支持渠道已从当前配置删除的历史记录。
     * @param node 快照树
     * @param channels 来源渠道 ID 与编码，历史快照优先
     */
    private void collectHistoricalChannels(JsonNode node, Map<Long, String> channels) {
        JsonUtils.visitObjects(node, "", (context, object) -> {
            if (object.has("channelCode") && object.hasNonNull("id")) {
                channels.put(JsonUtils.getPositiveLong(object, "id"), validator.getChannelCode(object, "channelCode"));
            }
            return true;
        });
    }

    /**
     * 导出时把结构化引用改为可移植编码，ID 写成字符串；仅操作明确的标识字段。
     * @param node 当前 JSON 值
     * @param context 当前字段名，用于识别快照内的独立 id
     * @param channels 来源渠道 ID 与编码
     */
    private void exportReferences(JsonNode node, String context, Map<Long, String> channels) {
        JsonUtils.visitObjects(node, context, (fieldContext, object) -> {
            // 1. 渠道快照只保留历史展示资料，地址、摘要、策略、生成参数和密钥均不迁移
            if (object.has("channelCode")) {
                validator.getChannelCode(object, "channelCode");
                object.retain(ProjectInfoImportExportValidator.CHANNEL_DISPLAY_FIELDS);
            }
            // 2. 报告汇总中的渠道引用也使用编码，避免目标环境 ID 冲突
            if (object.has("channelId")) {
                if (object.get("channelId").isNull()) {
                    object.putNull("channelRef");
                } else {
                    object.put("channelRef", requireChannelCode(channels, JsonUtils.getPositiveLong(object, "channelId")));
                }
                object.remove("channelId");
            }
            // 3. 已知标识以字符串传输，递归处理快照和报告证据，原文保持文本
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                JsonNode value = object.get(field);
                boolean knownId = field.equals("id") && (SECTIONS.contains(fieldContext) || fieldContext.equals("question_snapshot"));
                if ((knownId || FOREIGN_KEYS.containsKey(field) || REFERENCE_FIELDS.containsKey(field)) && !value.isNull()) {
                    object.put(field, Long.toString(JsonUtils.getPositiveLong(object, field)));
                }
            }
            return true;
        });
    }

    /**
     * 收集文件中所有渠道引用，覆盖方案、结果、判定渠道及报告汇总。
     * @param node 文件树
     * @param codes 去重后的渠道编码
     */
    private void collectChannelCodes(JsonNode node, Set<String> codes) {
        JsonUtils.visitObjects(node, "", (context, object) -> {
            for (String field : List.of("channel_code", "channelCode", "channelRef")) {
                if (object.hasNonNull(field)) {
                    codes.add(validator.getChannelCode(object, field));
                }
            }
            return true;
        });
    }

    /**
     * 将结构化快照与汇总中的内部标识改为新环境标识；渠道历史描述保持原值。
     * @param node 当前 JSON 值
     * @param context 当前字段名
     * @param ids 各表来源 ID 与新 ID
     * @param channels 目标渠道编码与 ID
     */
    private void importReferences(JsonNode node, String context, Map<ProjectInfoImportExportTable, Map<Long, Long>> ids,
                                 Map<String, Long> channels) {
        JsonUtils.visitObjects(node, context, (fieldContext, object) -> {
            // 1. 渠道按编码恢复目标 ID，不把目标环境配置覆盖到历史展示资料
            if (object.has("channelCode")) {
                validator.validateChannelSnapshot(object);
                object.put("id", channels.get(validator.getChannelCode(object, "channelCode")));
                return false;
            }
            // 2. 恢复汇总中的渠道引用字段，与现有报告消费者保持一致
            if (object.has("channelRef")) {
                if (object.get("channelRef").isNull()) {
                    object.putNull("channelId");
                } else {
                    object.put("channelId", channels.get(validator.getChannelCode(object, "channelRef")));
                }
                object.remove("channelRef");
            }
            // 3. 根据引用所属对象映射项目、问题及证据 ID，缺失时拒绝沿用旧值
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                ProjectInfoImportExportTable table = REFERENCE_FIELDS.get(field);
                if (field.equals("id")) {
                    table = switch (fieldContext) {
                        case "project" -> PROJECT;
                        case "questions", "question_snapshot" -> QUESTION;
                        default -> null;
                    };
                }
                if (table != null && object.hasNonNull(field)) {
                    object.put(field, requireMappedId(ids, table, JsonUtils.getPositiveLong(object, field)));
                }
            }
            return true;
        });
    }

    /**
     * 取得来源渠道编码；历史快照和现有配置均缺失时拒绝生成不完整文件。
     * @param channels 来源渠道索引
     * @param id 来源渠道 ID
     * @return 渠道编码
     */
    private String requireChannelCode(Map<Long, String> channels, long id) {
        String code = channels.get(id);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("历史诊断引用的渠道缺少编码，请先修复来源数据");
        }
        return code;
    }

    /**
     * 取得新生成的标识，禁止回退使用来源 ID。
     * @param ids 各表标识映射
     * @param table 关联表
     * @param oldId 来源 ID
     * @return 新 ID
     */
    private long requireMappedId(Map<ProjectInfoImportExportTable, Map<Long, Long>> ids, ProjectInfoImportExportTable table, long oldId) {
        Long id = ids.get(table).get(oldId);
        if (id == null) {
            throw new IllegalArgumentException("快照或报告引用了文件之外的数据：" + table.section);
        }
        return id;
    }

}
