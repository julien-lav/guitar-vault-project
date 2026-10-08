package com.guitarvault;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class InstrumentController {

    @GetMapping("/instruments")
    public String instruments(Model model) {
        List<Instrument> instruments = List.of(
                new Instrument(1L, "Fender Stratocaster", "Fender", 1987, "Julien", "Actif"),
                new Instrument(2L, "Gibson Les Paul", "Gibson", 1974, "Thomas", "À réparer"),
                new Instrument(3L, "PRS Custom 24", "PRS", 2019, "Emma", "En vente"),
                new Instrument(4L, "Yamaha Pacifica", "Yamaha", 2008, "Léo", "Actif")
        );

        model.addAttribute("title", "Mes instruments");
        model.addAttribute("instruments", instruments);
        return "instruments";
    }
}
