package eu.xfsc.fc.core.dao.dcp;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DcpPresentationRequestDefinitionRepository
    extends JpaRepository<DcpPresentationRequestDefinition, String> {

  Optional<DcpPresentationRequestDefinition> findFirstByPurposeAndEnabledTrueOrderByIdAsc(String purpose);

  List<DcpPresentationRequestDefinition> findByPurposeAndEnabledTrueOrderByIdAsc(String purpose);
}
