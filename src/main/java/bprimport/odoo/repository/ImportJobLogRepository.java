package bprimport.odoo.repository;

import bprimport.odoo.model.ImportJobLog;
import bprimport.odoo.model.enums.LogLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ImportJobLogRepository extends JpaRepository<ImportJobLog, Long> {

    Page<ImportJobLog> findByJobIdOrderByRowNumberAsc(Long jobId, Pageable pageable);

    List<ImportJobLog> findByJobIdAndLevelOrderByRowNumberAsc(Long jobId, LogLevel level);

    long countByJobIdAndLevel(Long jobId, LogLevel level);

    void deleteByJobId(Long jobId);
}
