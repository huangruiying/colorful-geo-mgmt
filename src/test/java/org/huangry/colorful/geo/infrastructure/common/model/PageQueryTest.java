package org.huangry.colorful.geo.infrastructure.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 列表分页参数的统一校验、关键词规范和分页片段。 */
class PageQueryTest {

    @Test
    void shouldRejectPageOrSizeOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(-1, 20));
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 101));
    }

    @Test
    void shouldAcceptSizeBoundaries() {
        assertEquals(1, new PageQuery(0, 1).size());
        assertEquals(100, new PageQuery(0, 100).size());
    }

    @Test
    void shouldStripKeywordAndTreatBlankAsAbsent() {
        assertEquals("产品", new PageQuery(0, 20, "  产品 ").keyword());
        assertNull(new PageQuery(0, 20, "   ").keyword());
        assertNull(new PageQuery(0, 20).keyword());
    }

    @Test
    void shouldRejectKeywordLongerThan200Characters() {
        String keyword = "关".repeat(201);

        assertThrows(IllegalArgumentException.class, () -> new PageQuery(0, 20, keyword));
    }

    @Test
    void shouldBuildOffsetAndLimitClauseWithoutOverflow() {
        var pageQuery = new PageQuery(Integer.MAX_VALUE, 100);

        assertEquals(214748364700L, pageQuery.offset());
        assertEquals("LIMIT 100 OFFSET 214748364700", pageQuery.limitClause());
    }

    @Test
    void shouldCarryPageAndSizeIntoResponse() {
        var response = new PageQuery(2, 20).toResponse(List.of("记录"), 45);

        assertEquals(new PageResponse<>(List.of("记录"), 45, 2, 20), response);
    }
}
