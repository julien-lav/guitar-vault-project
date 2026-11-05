package com.guitarvault;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ContentVisibility {
    private ContentVisibility() { }
    public static void require(boolean visible) { if (!visible) throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
