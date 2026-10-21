package org.huangry.colorful.geo.regtest;

import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一键回归入口：对每个"已走通"平台真实驱动浏览器客户端，逐节点打印 PASS/FAIL，
 * 最后给出汇总与退出码。后续你改了客户端/选择器，直接跑 run.sh 即可自检，不必再让我复跑。
 *
 * <p>用法：java ...RegressionRunner [平台key...]
 * 不带参数跑全部；带参数（EASTMONEY / JIANSHU，大小写不限）只跑指定平台，便于单平台快速验证。</p>
 *
 * <p>注意：本脚本会创建真实草稿到你的平台账号（标题带"[回归自测]"标记），跑完请按末尾清单定期清理。</p>
 */
public class RegressionRunner {

    private static final List<BrowserRegression> ALL = List.of(
            new EastmoneyDraftRegression(),
            new JianshuDraftRegression(),
            new XiaohongshuDraftRegression());

    public static void main(String[] args) throws Exception {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String content = "本草稿由 .test 回归脚本创建，请勿公开发布。\n"
                + "这是一段用于自动化回归校验的示例正文，用于确认打开草稿时内容可见。";

        List<BrowserRegression> selected = select(args);
        int platformTotal = selected.size();
        int platformPass = 0;
        int nodeTotal = 0;
        int nodeFail = 0;
        List<String> createdDrafts = new ArrayList<>();

        printLine('=');
        System.out.println(" 平台发布真实回归（草稿类 · 真实浏览器 + 真实 DB 登录态）");
        System.out.println(" 时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println(" JDK: " + System.getProperty("java.version")
                + "  PLAYWRIGHT_BROWSERS_PATH=" + System.getenv("PLAYWRIGHT_BROWSERS_PATH"));
        System.out.println(" 范围: " + (selected.equals(ALL) ? "全部" : String.join(", ", selected.stream().map(BrowserRegression::platformKey).toList())));
        printLine('=');

        for (BrowserRegression reg : selected) {
            String title = "[回归自测] " + reg.displayName() + " " + stamp;
            System.out.println();
            System.out.println("【" + reg.displayName() + " " + reg.platformKey() + "】");

            PlatformBrowserLoginEntity login = PlatformLoginState.load(reg.platformKey());
            List<NodeResult> nodes = reg.verify(login, title, content);
            boolean allPass = true;
            for (NodeResult n : nodes) {
                nodeTotal++;
                String mark;
                if (n.status() == NodeResult.Status.PASS) {
                    mark = "[OK]";
                } else if (n.status() == NodeResult.Status.FAIL) {
                    mark = "[XX]";
                    nodeFail++;
                    allPass = false;
                } else {
                    mark = "[--]";
                    allPass = false; // SKIP 视为未完全通过
                }
                System.out.println("  " + mark + " " + n.node() + "  " + n.detail());
            }
            if (allPass) {
                platformPass++;
                System.out.println("  结果: PASS");
                if (reg instanceof AbstractDraftRegression a && a.lastDraftUrl != null) {
                    createdDrafts.add(reg.displayName() + "  " + a.lastDraftUrl);
                }
            } else {
                System.out.println("  结果: FAIL（请查看上方失败节点）");
            }
        }

        printLine('-');
        System.out.println("汇总: " + platformPass + "/" + platformTotal + " 平台 PASS，节点失败 " + nodeFail + "/"
                + nodeTotal);
        if (!createdDrafts.isEmpty()) {
            System.out.println();
            System.out.println("本次创建的真实草稿（标题带[回归自测]，请定期清理，勿公开发布）:");
            for (String d : createdDrafts) {
                System.out.println("  - " + d);
            }
        }
        printLine('=');

        int code = (platformPass == platformTotal) ? 0 : 1;
        System.out.println("退出码: " + code);
        System.exit(code);
    }

    private static List<BrowserRegression> select(String[] args) {
        if (args == null || args.length == 0) {
            return ALL;
        }
        List<BrowserRegression> out = new ArrayList<>();
        for (String a : args) {
            String key = a.trim().toUpperCase(Locale.ROOT);
            for (BrowserRegression r : ALL) {
                if (r.platformKey().equals(key)) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static void printLine(char c) {
        System.out.println(String.valueOf(c).repeat(78));
    }
}
