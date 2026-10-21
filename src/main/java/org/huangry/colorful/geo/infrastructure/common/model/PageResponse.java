package org.huangry.colorful.geo.infrastructure.common.model;

import java.util.List;

/**
 * 所有列表分页接口统一返回的分页结构，页码从零开始。
 * <p>
 * 只承载当前页数据和总数；分页参数校验、筛选和排序由各模块 Business 负责。
 *
 * @param <T> 列表单条记录类型：列表专用行类 {@code XxxPageItem}，或与详情共用的响应对象
 */
public record PageResponse<T>(
		/** 当前页的数据，不包含其他页的记录。 */
		List<T> items,
		/** 符合本次筛选条件的记录总数，不是当前页条数。 */
		long total,
		/** 当前页码，从 0 开始。 */
		int page,
		/** 本次请求的每页条数。 */
		int size
) {
}
