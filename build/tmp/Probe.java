import com.agentlens.adapters.MockAgentAdapter;
import com.agentlens.core.AgentRun;
import com.agentlens.core.AgentTask;
import com.agentlens.eval.SqliteRunStore;

import java.nio.file.Path;
import java.util.Map;

public class Probe {
    public static void main(String[] args) throws Exception {
        var adapter = new MockAgentAdapter();
        var task = new AgentTask("probe-task", "probe the store",
                Map.of("agentBehavior", "correct"), 8);
        AgentRun run = adapter.run(task);
        System.out.printf("events in probe run: %d%n", run.events().size());
        var store = new SqliteRunStore(Path.of(args[0]));
        for (int i = 0; i < 5; i++) {
            long t0 = System.nanoTime();
            store.saveRun(run, "probe");
            System.out.printf("saveRun #%d: %.1f ms%n", i, (System.nanoTime() - t0) / 1e6);
        }
    }
}
