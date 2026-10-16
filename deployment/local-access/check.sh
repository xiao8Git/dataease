#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
check_dir="$(mktemp -d)"
trap 'rm -rf "$check_dir"' EXIT
cd "$repo_dir"
mvn -q -pl sdk/api/api-base,sdk/extensions/extensions-datasource -am install -DskipTests -Dmaven.test.skip=true
mvn -q -f core/core-backend/pom.xml test-compile dependency:build-classpath -Dmaven.test.skip=false -DskipTests -Dmdep.outputFile="$check_dir/classpath"
cd core/core-backend
java_bin=java
if [ -n "${JAVA_HOME:-}" ]; then java_bin="$JAVA_HOME/bin/java"; fi
"$java_bin" -cp "target/test-classes:target/classes:$(cat "$check_dir/classpath")" org.junit.runner.JUnitCore \
  io.dataease.visualization.manage.CoreBusiManageTest \
  io.dataease.localaccess.LocalTenantSqlTest \
  io.dataease.localaccess.LocalAccessManageTest \
  io.dataease.localaccess.security.LocalResourcePermissionTest \
  io.dataease.share.manage.TicketPreviewScopeTest \
  io.dataease.system.manage.IframeWhitelistTest \
  io.dataease.system.manage.EmbedKeyManageTest \
  io.dataease.exportCenter.manage.CommunityExportListTest

# Existing embed tests use JUnit Jupiter, separately from the project's JUnit 4 tests.
mvn -q dependency:copy -Dartifact=org.junit.platform:junit-platform-launcher:1.12.2 -DoutputDirectory="$check_dir"
cat > "$check_dir/JupiterChecks.java" <<'JAVA'
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
class JupiterChecks {
    public static void main(String[] args) {
        var request = LauncherDiscoveryRequestBuilder.request().selectors(
            selectClass("io.dataease.share.manage.EmbedScopeVerifierTest"),
            selectClass("io.dataease.share.manage.EmbedNonceStoreTest"),
            selectClass("io.dataease.share.interceptor.TicketScopeAopTest")).build();
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        var summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out));
        summary.printFailuresTo(new java.io.PrintWriter(System.out));
        if (summary.getTestsFoundCount() == 0 || summary.getTotalFailureCount() != 0) System.exit(1);
    }
}
JAVA
"$java_bin" -cp "target/test-classes:target/classes:$(cat "$check_dir/classpath"):$check_dir/junit-platform-launcher-1.12.2.jar" "$check_dir/JupiterChecks.java"
