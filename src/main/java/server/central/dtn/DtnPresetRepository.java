package server.central.dtn;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DtnPresetRepository extends JpaRepository<DtnPreset, UUID> {
}
