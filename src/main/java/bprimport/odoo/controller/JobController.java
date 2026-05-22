package bprimport.odoo.controller;

import bprimport.odoo.model.enums.LogLevel;
import bprimport.odoo.repository.ImportJobLogRepository;
import bprimport.odoo.repository.ImportJobRepository;
import bprimport.odoo.service.ImportJobService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/jobs")
public class JobController {

    private final ImportJobRepository jobRepo;
    private final ImportJobLogRepository logRepo;
    private final ImportJobService jobService;

    public JobController(ImportJobRepository jobRepo,
                         ImportJobLogRepository logRepo,
                         ImportJobService jobService) {
        this.jobRepo = jobRepo;
        this.logRepo = logRepo;
        this.jobService = jobService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        var jobs = jobRepo.findAllByOrderByCreatedAtDesc(PageRequest.of(page, 20));
        model.addAttribute("jobs", jobs);
        model.addAttribute("page", "jobs");
        return "jobs/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(defaultValue = "0") int logPage,
                         Model model) {
        var job = jobRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + id));

        var logs = logRepo.findByJobIdOrderByRowNumberAsc(job.getId(), PageRequest.of(logPage, 100));
        long errorCount = logRepo.countByJobIdAndLevel(id, LogLevel.ERROR);
        long warnCount = logRepo.countByJobIdAndLevel(id, LogLevel.WARNING);

        model.addAttribute("job", job);
        model.addAttribute("logs", logs);
        model.addAttribute("errorCount", errorCount);
        model.addAttribute("warnCount", warnCount);
        model.addAttribute("logPage", logPage);
        model.addAttribute("page", "jobs");
        return "jobs/detail";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes flash) {
        jobService.cancel(id);
        flash.addFlashAttribute("success", "Annulation demandée.");
        return "redirect:/jobs/" + id;
    }
}
