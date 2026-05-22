package bprimport.odoo.controller;

import bprimport.odoo.dto.OdooConnectionDto;
import bprimport.odoo.model.OdooConnection;
import bprimport.odoo.repository.OdooConnectionRepository;
import bprimport.odoo.service.OdooApiService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

@Controller
@RequestMapping("/connections")
public class ConnectionController {

    private final OdooConnectionRepository connRepo;
    private final OdooApiService odooApi;

    public ConnectionController(OdooConnectionRepository connRepo, OdooApiService odooApi) {
        this.connRepo = connRepo;
        this.odooApi = odooApi;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("connections", connRepo.findAll());
        model.addAttribute("page", "connections");
        return "connections/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("connection", new OdooConnection());
        model.addAttribute("page", "connections");
        return "connections/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        OdooConnection conn = connRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Not found: " + id));
        model.addAttribute("connection", conn);
        model.addAttribute("page", "connections");
        return "connections/form";
    }

    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam String url,
                       @RequestParam String database,
                       @RequestParam String login,
                       @RequestParam String apiKey,
                       @RequestParam(required = false) String platformSessionCookie,
                       RedirectAttributes flash) {
        OdooConnection conn = id != null ? connRepo.findById(id).orElse(new OdooConnection()) : new OdooConnection();
        conn.setName(name);
        conn.setUrl(url.stripTrailing());
        conn.setDatabase(database);
        conn.setLogin(login);
        String cookie = (platformSessionCookie != null && !platformSessionCookie.isBlank())
            ? platformSessionCookie.trim() : null;
        conn.setPlatformSessionCookie(cookie);
        conn.setApiKey(apiKey);
        connRepo.save(conn);
        flash.addFlashAttribute("success", "Connexion '" + name + "' enregistrée.");
        return "redirect:/connections";
    }

    @PostMapping("/{id}/test")
    public String test(@PathVariable Long id, RedirectAttributes flash) {
        OdooConnection conn = connRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Not found: " + id));
        try {
            int uid = odooApi.authenticate(conn);
            conn.setLastTestSuccess(true);
            conn.setLastTestMessage("Connexion réussie (uid=" + uid + ")");
        } catch (Exception e) {
            conn.setLastTestSuccess(false);
            conn.setLastTestMessage(e.getMessage());
        }
        conn.setLastTestedAt(LocalDateTime.now());
        connRepo.save(conn);
        if (Boolean.TRUE.equals(conn.getLastTestSuccess())) {
            flash.addFlashAttribute("success", conn.getLastTestMessage());
        } else {
            flash.addFlashAttribute("error", "Échec: " + conn.getLastTestMessage());
        }
        return "redirect:/connections";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes flash) {
        connRepo.deleteById(id);
        flash.addFlashAttribute("success", "Connexion supprimée.");
        return "redirect:/connections";
    }
}
