package org.huangry.colorful.geo.infrastructure.repository;

import org.huangry.colorful.geo.infrastructure.common.enums.ProjectInfoImportExportTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 模拟 JDBC 驱动返回值差异，验证迁移文件时间格式；不以 H2 的 Timestamp 行为代替 MySQL 边界。 */
class ProjectInfoImportExportDaoTest {
    /**
     * 提供相同日期时间的两种 JDBC 表示及数据库空值。
     * @return 驱动值和预期文件文本
     */
    static Stream<Arguments> databaseDateTimes() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 10, 24, 12, 34, 56, 123000000);
        return Stream.of(Arguments.of(dateTime, dateTime.toString()),
                Arguments.of(Timestamp.valueOf(dateTime), dateTime.toString()), Arguments.of(null, null));
    }

    /**
     * 时间列无论由驱动表示为 Timestamp 或 LocalDateTime，均输出 ISO 本地时间文本，不改变精度或时区。
     * @param driverValue JDBC 返回的时间值
     * @param expectedText 预期文本，数据库空值时为空
     * @throws Exception 模拟 JDBC 调用失败
     */
    @ParameterizedTest
    @MethodSource("databaseDateTimes")
    void shouldExportBothJdbcDateTimeRepresentations(Object driverValue, String expectedText) throws Exception {
        // 1. 仅替换 JDBC 驱动边界，使用真实仓储读取和 JSON 转换逻辑
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("created_at");
        when(result.getObject(1)).thenReturn(13L);
        when(result.getObject(2)).thenReturn(driverValue);
        doAnswer(invocation -> {
            invocation.<RowCallbackHandler>getArgument(1).processRow(result);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        ProjectInfoImportExportDao importExportDao = new ProjectInfoImportExportDao();
        ReflectionTestUtils.setField(importExportDao, "jdbcTemplate", jdbc);
        // 2. 检查文件值，不能仅验证没有异常而放过数组时间或精度变化
        var row = importExportDao.listProjectRows(ProjectInfoImportExportTable.PROJECT, 13).get(0);
        assertEquals(13, row.path("id").asLong());
        if (expectedText == null) {
            assertTrue(row.path("created_at").isNull());
        } else {
            assertTrue(row.path("created_at").isTextual());
            assertEquals(expectedText, row.path("created_at").asText());
        }
    }
}
