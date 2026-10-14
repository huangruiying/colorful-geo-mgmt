import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Set;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** 独立运行已编译的本地单测，不启动应用或连接真实平台。 */
public class FastjsonMigrationTestRunner {
    /**
     * 扫描独立测试目录并在失败时返回非零退出码。
     * @param args 独立测试 class 目录
     */
    public static void main(String[] args) {
        var listener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(Path.of(args[0])))).build();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        listener.getSummary().printTo(new PrintWriter(System.out));
        listener.getSummary().getFailures().forEach(failure -> {
            System.out.println(failure.getTestIdentifier().getDisplayName());
            failure.getException().printStackTrace(System.out);
        });
        if (listener.getSummary().getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
