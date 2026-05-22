package bprimport.odoo.controller;

import bprimport.odoo.model.enums.ImportStatus;
import bprimport.odoo.repository.ImportJobRepository;
import bprimport.odoo.repository.OdooConnectionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    private final ImportJobRepository jobRepo;
    private final OdooConnectionRepository connRepo;

    public DashboardController(ImportJobRepository jobRepo, OdooConnectionRepository connRepo) {
        this.jobRepo = jobRepo;
        this.connRepo = connRepo;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("recentJobs", jobRepo.findTop5ByOrderByCreatedAtDesc());
        model.addAttribute("totalJobs", jobRepo.count());
        model.addAttribute("runningJobs", jobRepo.countByStatus(ImportStatus.RUNNING));
        model.addAttribute("failedJobs", jobRepo.countByStatus(ImportStatus.FAILED));
        model.addAttribute("connections", connRepo.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("page", "dashboard");
        return "dashboard";
    }
}
