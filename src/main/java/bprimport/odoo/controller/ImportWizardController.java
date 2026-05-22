package bprimport.odoo.controller;

import bprimport.odoo.repository.OdooConnectionRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ImportWizardController {

    private final OdooConnectionRepository connRepo;

    public ImportWizardController(OdooConnectionRepository connRepo) {
        this.connRepo = connRepo;
    }

    @GetMapping("/import")
    public String wizard(Model model) {
        model.addAttribute("connections", connRepo.findAllByActiveTrueOrderByNameAsc());
        model.addAttribute("page", "import");
        return "import/wizard";
    }
}
