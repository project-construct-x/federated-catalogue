package eu.xfsc.fc.core.dao.dcp;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DcpAccessRuleRepository extends JpaRepository<DcpAccessRule, Long> {

  List<DcpAccessRule> findByEnabledTrueOrderBySortOrderAscIdAsc();
}
