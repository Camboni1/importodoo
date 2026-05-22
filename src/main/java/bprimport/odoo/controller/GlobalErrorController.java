package bprimport.odoo.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class GlobalErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, Model model) {
        Object status  = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Object ex      = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

        int code = status != null ? Integer.parseInt(status.toString()) : 500;
        String msg = message != null && !message.toString().isBlank()
            ? message.toString()
            : (ex != null ? ex.toString() : "Erreur inattendue");

        model.addAttribute("code", code);
        model.addAttribute("message", msg);
        model.addAttribute("isNotFound", code == HttpStatus.NOT_FOUND.value());
        model.addAttribute("page", "");
        return "error";
    }
}
