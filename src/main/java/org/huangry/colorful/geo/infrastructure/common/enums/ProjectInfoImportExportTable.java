package org.huangry.colorful.geo.infrastructure.common.enums;

import java.util.List;
import java.util.Set;

/**
 * 项目导入导出包含哪些表、每张表带哪些字段。
 * <p>每个枚举值对应导出文件中的一个分区（如 {@code "plans"}）和数据库中的一张表（如 {@code geo_diagnosis_plan}）：
 * 导出按这里的字段读库，校验按这里的字段检查文件，导入按这里的字段写库。</p>
 * <p>约束：表名和字段只取自这里的白名单，不接受文件传入 SQL 标识符；声明顺序父表在前，满足外键依赖。
 * 数据库新增列须在此补充才会被导入导出。</p>
 */
public enum ProjectInfoImportExportTable {
	/**
	 * 项目基础资料。
	 */
	PROJECT("project", "geo_project_info", "id,name,website_url,industry,core_features,target_audience,aliases,competitors,target_market,target_language,selling_points,pricing,keywords,created_at,updated_at"),
	/**
	 * 项目当前诊断方案。
	 */
	PLAN("plans", "geo_diagnosis_plan", "id,name,project_id,repeat_count,created_at,updated_at"),
	/**
	 * 方案当前问题，已删除问题通过历史快照保留。
	 */
	QUESTION("questions", "geo_diagnosis_plan_question", "id,plan_id,question_text,source,intent,brand_scope,language,sort_order"),
	/**
	 * 方案选择的渠道关联，不包含渠道配置。
	 */
	PLAN_CHANNEL("planChannels", "geo_diagnosis_plan_channel", "id,plan_id,channel_id"),
	/**
	 * 已结束的诊断任务及其冻结配置。
	 */
	TASK("tasks", "geo_diagnosis_task", "id,request_id,plan_id,project_id,plan_snapshot,status,started_at,finished_at,created_at,updated_at"),
	/**
	 * 单次调用的回答、引用和原始响应。
	 */
	RESULT("results", "geo_diagnosis_task_result", "id,task_id,question_id,question_sort_order,question_snapshot,channel_id,channel_snapshot,repeat_index,status,answer_text,citations,model_name,usage_info,duration_ms,finish_reason,raw_response,error_message,started_at,finished_at,created_at,updated_at"),
	/**
	 * 已结束的诊断报告及冻结汇总。
	 */
	REPORT("reports", "geo_diagnosis_task_report", "id,task_id,status,judge_channel_snapshot,prompt_version,summary,error_message,started_at,finished_at,created_at,updated_at"),
	/**
	 * 报告中的回答判定和人工修正。
	 */
	ANALYSIS("analyses", "geo_diagnosis_task_result_analysis", "id,report_id,task_result_id,status,brand_sentiment,description_accurate,brands,points,sources,error_message,manually_corrected,created_at,updated_at");

	/**
	 * JSON 列在文件中保存为 JSON 值，写回数据库时显式序列化。
	 */
	public static final Set<String> JSON_COLUMNS = Set.of("aliases", "competitors", "keywords", "plan_snapshot",
			"question_snapshot", "channel_snapshot", "citations", "usage_info", "judge_channel_snapshot",
			"summary", "brands", "points", "sources");
	/**
	 * 导出文件中的分区名称，属于文件格式；改名会导致已导出的文件无法导入。
	 */
	public final String section;
	/**
	 * 固定数据库表名。
	 */
	public final String table;
	/**
	 * 显式选择的字段及写入顺序，新增数据库列不会自动进入导出文件。
	 */
	public final List<String> columns;

	/**
	 * 声明一个迁移分区的固定存储边界。
	 *
	 * @param section 文件中的分区名称
	 * @param table   数据库表名
	 * @param columns 按写入顺序排列的字段
	 */
	ProjectInfoImportExportTable(String section, String table, String columns) {
		this.section = section;
		this.table = table;
		this.columns = List.of(columns.split(","));
	}
}
