package org.huangry.colorful.geo.infrastructure.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable;
import org.huangry.colorful.geo.infrastructure.common.utils.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable.*;

/**
 * 项目导入导出的数据库读写：按白名单字段读取一个项目的八张表，并把导入数据逐行插入为新记录。
 * <p>表名和字段只取自 {@link ProjectInfoImportExportTable}，SQL 值全部参数绑定。</p>
 * <p>事务、文件校验、ID 映射和幂等键清理由领域服务负责。</p>
 */
@Repository
public class ProjectInfoImportExportDao {
	@Resource
	private JdbcTemplate jdbcTemplate;

	/**
	 * 从同一个事务快照读取项目关联行；任务同时按方案和历史项目筛选，交叉归属由上层拒绝。
	 *
	 * @param table     固定迁移表
	 * @param projectId 来源项目 ID
	 * @return 固定字段构成的数据行，JSON 列已恢复为 JSON 值
	 */
	public ArrayNode listProjectRows(ProjectInfoImportExportTable table, long projectId) {
		// 组装固定的项目归属路径，不接受请求传入表名或筛选表达式
		String plans = "SELECT id FROM geo_diagnosis_plan WHERE project_id = ?";
		String tasks = "SELECT id FROM geo_diagnosis_task WHERE project_id = ? OR plan_id IN (" + plans + ")";
		String condition = switch (table) {
			case PROJECT -> "id = ?";
			case PLAN -> "project_id = ?";
			case QUESTION, PLAN_CHANNEL -> "plan_id IN (" + plans + ")";
			case TASK -> "project_id = ? OR plan_id IN (" + plans + ")";
			case RESULT, REPORT -> "task_id IN (" + tasks + ")";
			case ANALYSIS -> "report_id IN (SELECT id FROM geo_diagnosis_task_report WHERE task_id IN (" + tasks + "))";
		};
		Object[] parameters = switch (table) {
			case TASK, RESULT, REPORT, ANALYSIS -> new Object[]{projectId, projectId};
			default -> new Object[]{projectId};
		};
		// 将项目 ID 作为绑定值执行，只读取迁移白名单字段
		return readRows("SELECT " + String.join(",", table.columns) + " FROM " + table.table
				+ " WHERE " + condition + " ORDER BY id", parameters);
	}

	/**
	 * 仅查询渠道标识和编码，认证信息不会进入迁移链路。
	 *
	 * @return 渠道 ID 与编码
	 */
	public Map<Long, String> listChannelCodes() {
		Map<Long, String> channels = new LinkedHashMap<>();
		jdbcTemplate.query("SELECT id, channel_code FROM geo_llm_channel ORDER BY id", (RowCallbackHandler) result -> {
			channels.put(result.getLong("id"), result.getString("channel_code"));
		});
		return channels;
	}

	/**
	 * 在导入事务中锁住实际引用的渠道，避免校验后被删除或改码；不读取认证字段。
	 *
	 * @param codes 文件引用的全部编码
	 * @return 匹配的渠道 ID 与编码
	 */
	public Map<Long, String> lockChannelCodes(Set<String> codes) {
		// 没有渠道引用的基础项目不需要访问或锁定渠道表
		if (codes.isEmpty()) {
			return Map.of();
		}
		String placeholders = codes.stream().map(code -> "?").collect(Collectors.joining(","));
		Map<Long, String> channels = new LinkedHashMap<>();
		// 固定顺序加锁，锁的释放跟随调用者事务提交或回滚
		jdbcTemplate.query("SELECT id, channel_code FROM geo_llm_channel WHERE channel_code IN (" + placeholders
				+ ") ORDER BY id FOR UPDATE", (RowCallbackHandler) result -> {
			channels.put(result.getLong("id"), result.getString("channel_code"));
		}, codes.toArray());
		return channels;
	}

	/**
	 * 按白名单写入一行并获取新 ID；保留历史时间，不写入来源主键，其余字段按行内值原样写入。
	 *
	 * @param table 固定迁移表
	 * @param row   已校验及映射的行
	 * @return 新生成的数据库 ID
	 */
	public long insertArchiveRow(ProjectInfoImportExportTable table, ObjectNode row) {
		// 1. 排除来源主键，按显式字段构造参数化插入语句
		var columns = table.columns.stream().filter(column -> ! column.equals("id")).toList();
		String placeholders = columns.stream().map(column -> "?").collect(Collectors.joining(","));
		String sql = "INSERT INTO " + table.table + " (" + String.join(",", columns) + ") VALUES (" + placeholders + ")";
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		// 2. 在调用者事务中绑定列值并写入，只申请生成的主键列
		jdbcTemplate.update(connection -> {
			var statement = connection.prepareStatement(sql, new String[]{"id"});
			for (int index = 0; index < columns.size(); index++) {
				statement.setObject(index + 1, columnValue(columns.get(index), row.get(columns.get(index))));
			}
			return statement;
		}, key);
		// 3. 生成标识缺失时中止整次导入，不能回退使用来源 ID
		if (key.getKey() == null) {
			throw new IllegalStateException("导入未取得新记录标识");
		}
		return key.getKey().longValue();
	}

	/**
	 * 删除为历史问题预留 ID 的临时行；仅在当前导入事务内使用，不改变当前方案问题。
	 *
	 * @param questionId 本事务刚生成的历史问题 ID
	 */
	public void deleteReservedQuestion(long questionId) {
		jdbcTemplate.update("DELETE FROM geo_diagnosis_plan_question WHERE id = ?", questionId);
	}

	/**
	 * 执行内部固定查询，时间转换为不依赖 JVM 时区的本地日期时间。
	 *
	 * @param sql        内部白名单生成的 SQL
	 * @param parameters 绑定值
	 * @return 文件行数组
	 */
	private ArrayNode readRows(String sql, Object[] parameters) {
		ArrayNode rows = JsonNodeFactory.instance.arrayNode();
		jdbcTemplate.query(sql, (RowCallbackHandler) result -> {
			ObjectNode row = rows.addObject();
			var metadata = result.getMetaData();
			for (int index = 1; index <= metadata.getColumnCount(); index++) {
				Object value = result.getObject(index);
				if (value instanceof Timestamp timestamp) {
					value = timestamp.toLocalDateTime().toString();
				} else if (value instanceof LocalDateTime dateTime) {
					// MySQL 驱动可直接返回 LocalDateTime，与 Timestamp 统一为本地时间文本
					value = dateTime.toString();
				}
				String column = metadata.getColumnLabel(index).toLowerCase(Locale.ROOT);
				row.set(column, JSON_COLUMNS.contains(column) && value instanceof String json
						? JsonUtils.readTree(json) : JsonUtils.toTree(value));
			}
		}, parameters);
		return rows;
	}

	/**
	 * 按声明的列类型转换绑定值，不解释文件中的类型名或 SQL 表达式。
	 *
	 * @param column 白名单字段
	 * @param value  文件字段值
	 * @return JDBC 绑定值
	 */
	private Object columnValue(String column, JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (JSON_COLUMNS.contains(column)) {
			return value.toString();
		}
		if (column.endsWith("_at")) {
			return Timestamp.valueOf(LocalDateTime.parse(value.asText()));
		}
		if (value.isIntegralNumber()) {
			return value.longValue();
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		return value.asText();
	}
}
