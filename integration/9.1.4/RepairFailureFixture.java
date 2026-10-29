package integration;

import java.util.UUID;
import org.springframework.boot.SpringApplication;
import com.oficina.execution.ExecutionApplication;
import com.oficina.execution.application.port.in.RepairUseCase;
import com.oficina.execution.domain.enums.RepairStatus;

public class RepairFailureFixture {
    public static void main(String[] args) {
        if (!"e2e_execution".equals(System.getenv("MONGO_DATABASE"))) {
            throw new IllegalStateException("Isolated fixture database required");
        }
        UUID repairId = UUID.fromString(args[0]);
        try (var context = SpringApplication.run(ExecutionApplication.class, "--server.port=0")) {
            var useCase = context.getBean(RepairUseCase.class);
            if (useCase.findById(repairId).status() != RepairStatus.IN_PROGRESS) {
                throw new IllegalStateException("Fixture requires started physical repair");
            }
            var failed = useCase.fail(repairId, "Falha fisica controlada E2E 9.1.4", UUID.randomUUID());
            if (failed.status() != RepairStatus.FAILED) {
                throw new IllegalStateException("Expected domain failure");
            }
            System.out.println("REPAIR_FAILURE_FIXTURE_PASSED " + repairId);
        }
    }
}
