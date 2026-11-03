package com.guitarvault;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InstrumentService {
    private final UserRepository users;
    private final InstrumentRepository instruments;

    public InstrumentService(UserRepository users, InstrumentRepository instruments) {
        this.users = users;
        this.instruments = instruments;
    }

    public User currentUser(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return users.findByEmailIgnoreCase(principal.getName())
                .filter(User::isEnabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public List<Instrument> instrumentsFor(User owner) {
        return instruments.findByOwnerIdOrderByIdDesc(owner.getId());
    }

    public Instrument ownedInstrument(User owner, Long id) {
        return instruments.findById(id)
                .filter(instrument -> instrument.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public void save(Instrument instrument) {
        instruments.save(instrument);
    }

    public void add(User owner, String name, String brand, Integer year, String status) {
        instruments.save(new Instrument(name, brand, year, owner, status));
    }
}
