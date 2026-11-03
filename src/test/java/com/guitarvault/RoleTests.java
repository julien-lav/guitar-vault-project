package com.guitarvault;

import com.guitarvault.fixtures.AppFixtures;
import com.guitarvault.fixtures.FixtureLoadRepository;
import jakarta.persistence.EntityManager;
import java.util.Set;
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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class RoleTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired FixtureLoadRepository loads;
    @Autowired EntityManager entityManager;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void fixturesAreInactiveDuringOrdinaryStartup() {
        assertThat(context.getBeansOfType(AppFixtures.class)).isEmpty();
        assertThat(loads.count()).isZero();
        assertThat(users.existsByUsernameIgnoreCase("demo_superadmin")).isFalse();
    }

    @Test
    void signupCannotChoosePrivilegedRoles() throws Exception {
        mvc.perform(post("/signup").with(csrf()).param("fullName", "Role test").param("username", "roletest")
                        .param("email", "roles@example.test").param("password", "Secret123!").param("confirmPassword", "Secret123!")
                        .param("roles", "ROLE_SUPER_ADMIN", "ROLE_ADMIN").param("role", "ROLE_EDITOR").param("enabled", "true"))
                .andExpect(redirectedUrl("/login"));
        entityManager.clear();
        User user = users.findByEmail("roles@example.test").orElseThrow();
        assertThat(user.getRoles()).containsExactly(Role.ROLE_USER);
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void databaseRolesAreCumulativeAndOrdinaryAccountsStayMembers() {
        User user = new User(); user.setUsername("roles"); user.setEmail("persisted@example.test"); user.setPasswordHash("unused");
        user.setRoles(Set.of(Role.ROLE_EDITOR, Role.ROLE_SUPER_USER)); users.saveAndFlush(user);
        entityManager.clear();
        User stored = users.findByEmail(user.getEmail()).orElseThrow();
        assertThat(new AdminAccess("").roles(stored)).containsExactlyInAnyOrder(Role.ROLE_USER, Role.ROLE_EDITOR, Role.ROLE_SUPER_USER);
        assertThat(new AdminAccess("").allows(stored)).isFalse();
        stored.setRoles(Set.of(Role.ROLE_SUPER_ADMIN));
        assertThat(new AdminAccess("").allows(stored)).isTrue();
        stored.setEnabled(false);
        assertThat(new AdminAccess("").allows(stored)).isFalse();
    }

    @Test
    void legacyAccountWithoutRoleRowsKeepsUserAccess() {
        User user = new User(); user.setUsername("legacy"); user.setEmail("legacy@example.test"); user.setPasswordHash("unused");
        users.saveAndFlush(user);
        entityManager.createNativeQuery("delete from user_roles where user_id = :id").setParameter("id", user.getId()).executeUpdate();
        entityManager.clear();
        assertThat(users.findByEmail(user.getEmail()).orElseThrow().getRoles()).containsExactly(Role.ROLE_USER);
    }
}
