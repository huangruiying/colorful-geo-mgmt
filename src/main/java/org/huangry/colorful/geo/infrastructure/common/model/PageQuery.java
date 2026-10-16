package org.huangry.colorful.geo.infrastructure.common.model;

import java.util.List;

/**
 * 所有列表分页接口统一使用的查询参数，创建时完成分页和关键词校验。
 * <p>
 * Business 用接口入参构造后传给 Service；筛选、排序仍由各模块自行决定。
 *
 * @param page 从零开始的页码
 * @param size 每页条数，1～100
 * @param keyword 去掉首尾空白的查询关键词，未填写时为 null
 */
public record PageQuery(int page, int size, String keyword) {

    /** 单页条数上限，避免一次查询过多记录。 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 关键词长度上限，与名称类字段的长度限制一致。 */
    private static final int MAX_KEYWORD_LENGTH = 200;

    /**
     * 校验分页参数并规范关键词，非法输入直接拒绝。
     *
     * @throws IllegalArgumentException 页码为负、每页条数越界或关键词超长
     */
    public PageQuery {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("页码不能小于 0，每页条数必须在 1 到 100 之间");
        }
        // 空白关键词等同未填写，列表不按名称筛选
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("查询关键词最多 200 字符");
        }
    }

    /**
     * 不带关键词的分页查询。
     *
     * @param page 从零开始的页码
     * @param size 每页条数，1～100
     */
    public PageQuery(int page, int size) {
        this(page, size, null);
    }

    /**
     * 计算当前页的起始偏移，使用 long 防止大页码溢出。
     *
     * @return 起始偏移
     */
    public long offset() {
        return (long) page * size;
    }

    /**
     * 生成 MyBatis-Plus {@code last()} 使用的分页片段；只拼接已校验的数字。
     *
     * @return 形如 {@code LIMIT 20 OFFSET 40} 的 SQL 片段
     */
    public String limitClause() {
        return "LIMIT " + size + " OFFSET " + offset();
    }

    /**
     * 用当前页码和条数组装分页结果。
     *
     * @param items 当前页记录
     * @param total 符合筛选条件的记录总数
     * @param <T> 列表单条记录类型
     * @return 分页结果
     */
    public <T> PageResponse<T> toResponse(List<T> items, long total) {
        return new PageResponse<>(items, total, page, size);
    }
}
