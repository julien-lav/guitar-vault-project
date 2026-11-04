package com.guitarvault;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class WishlistTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired WishlistRepository wishlist;
    @Autowired InstrumentRepository instruments;
    @MockitoBean JavaMailSender mail;
    private MockMvc mvc;
    private User alice;
    private User bob;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        alice = account("wishlist-alice");
        bob = account("wishlist-bob");
    }

    @Test
    void rendersEmptyStateNavigationAndLocalizedForms() throws Exception {
        mvc.perform(get("/wishlist").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(model().attribute("items", hasSize(0)))
                .andExpect(content().string(containsString("Imaginez votre prochain coup de cœur")));
        mvc.perform(get("/wishlist/new").param("lang", "en").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Add to wishlist")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/dashboard").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("href=\"/wishlist\"")));
    }

    @Test
    void createsPrivateEntriesWithoutChangingOwnedCollection() throws Exception {
        long ownedCount = instruments.count();
        mvc.perform(post("/wishlist").with(user(alice.getEmail())).with(csrf())
                        .param("name", "  Jazzmaster  ").param("brand", " Fender ").param("year", "1965")
                        .param("notes", "  Olympic white\n<script>hello</script> ")
                        .param("ownerId", bob.getId().toString()))
                .andExpect(redirectedUrl("/wishlist")).andExpect(flash().attribute("success", "wishlist.added"));
        wishlist.flush();
        var saved = wishlist.findByOwnerIdOrderByIdDesc(alice.getId()).getFirst();
        assertThat(saved.getName()).isEqualTo("Jazzmaster");
        assertThat(saved.getBrand()).isEqualTo("Fender");
        assertThat(saved.getYear()).isEqualTo(1965);
        assertThat(saved.getNotes()).isEqualTo("Olympic white\n<script>hello</script>");
        assertThat(wishlist.findByOwnerIdOrderByIdDesc(bob.getId())).isEmpty();
        assertThat(instruments.count()).isEqualTo(ownedCount);
        mvc.perform(get("/wishlist").with(user(alice.getEmail())))
                .andExpect(content().string(containsString("Jazzmaster")))
                .andExpect(content().string(containsString("&lt;script&gt;hello&lt;/script&gt;")));
        mvc.perform(get("/wishlist").with(user(bob.getEmail())))
                .andExpect(content().string(not(containsString("Jazzmaster"))));
    }

    @Test
    void editsAndDeletesAnOwnedEntryWithConfirmation() throws Exception {
        var item = wishlist.saveAndFlush(new WishlistItem(alice, "Telecaster", "Fender", 1952, "Original"));
        String path = "/wishlist/" + item.getId();
        mvc.perform(get(path + "/edit").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Telecaster")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(post(path).with(user(alice.getEmail())).with(csrf())
                        .param("name", "Custom Telecaster").param("brand", "Fender"))
                .andExpect(redirectedUrl("/wishlist"));
        wishlist.flush();
        assertThat(wishlist.findById(item.getId()).orElseThrow().getName()).isEqualTo("Custom Telecaster");
        assertThat(item.getYear()).isNull();
        assertThat(item.getNotes()).isEmpty();
        mvc.perform(get(path + "/delete").with(user(alice.getEmail())))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Custom Telecaster")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        assertThat(wishlist.existsById(item.getId())).isTrue();
        mvc.perform(post(path + "/delete").with(user(alice.getEmail())).with(csrf()))
                .andExpect(redirectedUrl("/wishlist")).andExpect(flash().attribute("success", "wishlist.deleted"));
        assertThat(wishlist.existsById(item.getId())).isFalse();
    }

    @Test
    void preventsAccessToAnotherUsersEntriesAndUnknownIds() throws Exception {
        var item = wishlist.saveAndFlush(new WishlistItem(alice, "Private guitar", "Fender", null, ""));
        for (long id : List.of(item.getId(), Long.MAX_VALUE)) {
            String path = "/wishlist/" + id;
            for (String suffix : List.of("/edit", "/delete")) {
                mvc.perform(get(path + suffix).with(user(bob.getEmail()))).andExpect(status().isNotFound());
            }
            mvc.perform(post(path).with(user(bob.getEmail())).with(csrf())
                            .param("name", "Stolen").param("brand", "Other"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(path + "/delete").with(user(bob.getEmail())).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        assertThat(wishlist.findById(item.getId()).orElseThrow().getName()).isEqualTo("Private guitar");
    }

    @Test
    void validatesInputsPreservesFormAndDoesNotChangeExistingEntry() throws Exception {
        var item = wishlist.saveAndFlush(new WishlistItem(alice, "Original", "Fender", 2000, "Notes"));
        for (String path : List.of("/wishlist", "/wishlist/" + item.getId())) {
            for (String field : List.of("name", "brand", "year", "notes")) {
                for (String value : switch (field) {
                    case "name", "brand" -> List.of(" ", "x".repeat(101));
                    case "year" -> List.of("abc", "0", "9999", "999999999999");
                    default -> List.of("x".repeat(5001));
                }) {
                    var params = new java.util.HashMap<>(java.util.Map.of("name", "Stratocaster", "brand", "Fender", "notes", "Keep me"));
                    params.put(field, value);
                    var request = post(path).with(user(alice.getEmail())).with(csrf());
                    params.forEach(request::param);
                    mvc.perform(request).andExpect(status().isOk())
                            .andExpect(model().attributeExists("error", "form"))
                            .andExpect(content().string(containsString("role=\"alert\"")));
                }
            }
        }
        assertThat(wishlist.findByOwnerIdOrderByIdDesc(alice.getId())).hasSize(1);
        assertThat(item.getName()).isEqualTo("Original");
        assertThat(item.getYear()).isEqualTo(2000);
    }

    @Test
    void requiresLoginAndCsrfForMutations() throws Exception {
        var item = wishlist.saveAndFlush(new WishlistItem(alice, "Guitar", "Fender", null, ""));
        for (String path : List.of("/wishlist", "/wishlist/new", "/wishlist/" + item.getId() + "/edit", "/wishlist/" + item.getId() + "/delete")) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        for (String path : List.of("/wishlist", "/wishlist/" + item.getId(), "/wishlist/" + item.getId() + "/delete")) {
            mvc.perform(post(path).with(csrf())).andExpect(redirectedUrl("/login"));
            mvc.perform(post(path).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        }
    }

    private User account(String name) {
        User account = new User();
        account.setUsername(name);
        account.setEmail(name + "@example.com");
        account.setPasswordHash("unused-test-hash");
        account.setEnabled(true);
        return users.saveAndFlush(account);
    }
}
