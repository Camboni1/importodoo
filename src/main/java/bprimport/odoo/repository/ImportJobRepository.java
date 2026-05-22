package bprimport.odoo.repository;

import bprimport.odoo.model.ImportJob;
import bprimport.odoo.model.enums.ImportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Page<ImportJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<ImportJob> findTop5ByOrderByCreatedAtDesc();

    @Query("SELECT j FROM ImportJob j WHERE j.status IN :statuses")
    List<ImportJob> findByStatuses(List<ImportStatus> statuses);

    long countByStatus(ImportStatus status);
}
