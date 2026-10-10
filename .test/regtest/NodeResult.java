package org.huangry.colorful.geo.regtest;

/**
 * 单个回归节点的结果。状态分 PASS / FAIL / SKIP 三类，detail 记录可定位的信息
 * （捕获到的平台草稿 ID、异常根因、登录态账号等），供一键运行后直接读报告，无需再让 AI 复跑。
 */
public record NodeResult(String node, Status status, String detail) {

    public enum Status { PASS, FAIL, SKIP }

    public static NodeResult pass(String node, String detail) {
        return new NodeResult(node, Status.PASS, detail);
    }

    public static NodeResult fail(String node, String detail) {
        return new NodeResult(node, Status.FAIL, detail);
    }

    public static NodeResult skip(String node, String detail) {
        return new NodeResult(node, Status.SKIP, detail);
    }

    public boolean ok() {
        return status == Status.PASS;
    }
}
