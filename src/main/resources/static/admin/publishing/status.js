"use strict";
/** 内容优化与发布记录共用的发布状态文案；不决定可重试、可删除等业务规则。 */
window.PublishStatus = Object.freeze({labels:Object.freeze({
    INITIALIZED:"待预发布", PRE_PUBLISHING:"预发布中", PRE_PUBLISHED:"预发布成功",
    PUBLISHING:"实际发布中", PUBLISHED:"实际发布成功",
    PRE_PUBLISH_FAILED:"预发布失败", PUBLISH_FAILED:"实际发布失败", CANCELLED:"已取消"
})});
