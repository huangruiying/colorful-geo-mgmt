package org.huangry.colorful.geo.regtest;

/**
 * 客户端 createDraft 的统一产物，抹平各平台 DraftCreated 的内部类型差异，
 * 让抽象基类只依赖 remoteContentId 与 draftUrl 两个字段。
 */
public record DraftCreatedResult(String remoteContentId, String draftUrl) {
}
