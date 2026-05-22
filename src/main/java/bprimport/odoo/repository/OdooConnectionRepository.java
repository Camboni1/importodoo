package bprimport.odoo.repository;

import bprimport.odoo.model.OdooConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OdooConnectionRepository extends JpaRepository<OdooConnection, Long> {
    List<OdooConnection> findAllByActiveTrueOrderByNameAsc();
}
